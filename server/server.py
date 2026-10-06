#!/usr/bin/env python3
"""
Reference-server for Automat-telefoner. Kun Pythons standardbibliotek.

- POST /api/heartbeat  (telefonen, Bearer-token)  -> gemmer status + hændelser, svarer med kommandoer
- GET  /               (ejeren, Basic-auth)       -> statusside for alle automater
- POST /admin/command  (ejeren, Basic-auth)       -> læg en kommando i kø til en automat
- Vagthund: ingen heartbeat i OFFLINE_AFTER sekunder -> push-besked via ntfy.sh

Kør bag en HTTPS-proxy (f.eks. Caddy) – telefonen kræver https.

Miljøvariabler:
  AUTOMAT_TOKEN    token telefonerne sender (samme som server.token i config.json)  [krævet]
  ADMIN_PASSWORD   kodeord til statussiden (brugernavn: admin)                       [krævet]
  PORT             default 8080
  DATA_DIR         default ./data
  OFFLINE_AFTER    sekunder uden heartbeat før alarm, default 300
  NTFY_TOPIC       ntfy.sh-emne til alarmer (valgfri)
"""

import base64
import hmac
import html
import json
import os
import threading
import time
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

TOKEN = os.environ.get("AUTOMAT_TOKEN", "")
ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "")
PORT = int(os.environ.get("PORT", "8080"))
DATA_DIR = os.environ.get("DATA_DIR", "data")
OFFLINE_AFTER = int(os.environ.get("OFFLINE_AFTER", "300"))
NTFY_TOPIC = os.environ.get("NTFY_TOPIC", "")

ALLOWED_COMMANDS = {"refill", "clearFault", "reloadConfig", "setStock", "setConfig", "simulate"}

lock = threading.Lock()
devices = {}   # deviceId -> {"status": {...}, "seen": epoch, "offline": bool}
commands = {}  # deviceId -> [command, ...]


def load():
    os.makedirs(DATA_DIR, exist_ok=True)
    path = os.path.join(DATA_DIR, "devices.json")
    if os.path.exists(path):
        with open(path) as f:
            devices.update(json.load(f))


def save():
    path = os.path.join(DATA_DIR, "devices.json")
    with open(path + ".tmp", "w") as f:
        json.dump(devices, f)
    os.replace(path + ".tmp", path)


def alert(message):
    print("ALARM:", message, flush=True)
    if not NTFY_TOPIC:
        return
    try:
        urllib.request.urlopen(
            urllib.request.Request(f"https://ntfy.sh/{NTFY_TOPIC}", data=message.encode(), method="POST"),
            timeout=10,
        )
    except Exception as e:  # noqa: BLE001
        print("ntfy fejlede:", e, flush=True)


def handle_heartbeat(body):
    device_id = str(body.get("deviceId", ""))
    if not device_id:
        raise ValueError("deviceId mangler")
    events = body.pop("events", []) or []
    name = body.get("name", device_id)
    now = time.time()
    with lock:
        prev = devices.get(device_id)
        if prev and prev.get("offline"):
            alert(f"{name}: online igen")
        devices[device_id] = {"status": body, "seen": now, "offline": False}
        save()
        pending = commands.pop(device_id, [])
    with open(os.path.join(DATA_DIR, "events.jsonl"), "a") as f:
        for e in events:
            f.write(json.dumps({"deviceId": device_id, "name": name, **e}, ensure_ascii=False) + "\n")
    for e in events:
        t = e.get("type")
        if t == "refund":
            alert(f"{name}: refundér {e.get('amountOre', 0) / 100:.2f} kr – {e.get('reason')}")
        elif t == "fault":
            alert(f"{name}: FEJL {e.get('error')}")
        elif t == "start" and e.get("previousCrash"):
            alert(f"{name}: appen genstartede efter nedbrud")
        elif t == "command" and e.get("result") != "ok":
            alert(f"{name}: kommando {e.get('cmd')} fejlede: {e.get('result')}")
    return {"commands": pending}


def watchdog():
    while True:
        time.sleep(30)
        now = time.time()
        with lock:
            for d in devices.values():
                if not d.get("offline") and now - d["seen"] > OFFLINE_AFTER:
                    d["offline"] = True
                    alert(f"{d['status'].get('name')}: intet heartbeat i {int(now - d['seen'])} s")
            save()


def recent_events(n=50):
    path = os.path.join(DATA_DIR, "events.jsonl")
    if not os.path.exists(path):
        return []
    with open(path) as f:
        lines = f.readlines()[-n:]
    return [json.loads(line) for line in reversed(lines)]


def status_page():
    now = time.time()
    rows = []
    with lock:
        items = sorted(devices.items(), key=lambda kv: kv[1]["status"].get("name", ""))
        queued = {k: len(v) for k, v in commands.items()}
    for device_id, d in items:
        s = d["status"]
        h = s.get("health", {})
        age = int(now - d["seen"])
        alive = age <= OFFLINE_AFTER
        bad = [k for k in ("online", "motorLink", "notificationAccess", "uiVisible") if not h.get(k)]
        if h.get("fault"):
            bad.append(f"fejl: {h['fault']}")
        if h.get("configError"):
            bad.append(f"config: {h['configError']}")
        screen = s.get("screen", {})
        battery = s.get("battery", {})
        stock = ", ".join(f"{k}: {v}" for k, v in (s.get("stock") or {}).items())
        buttons = "".join(
            f'<form method="post" action="/admin/command"><input type="hidden" name="deviceId" value="{html.escape(device_id)}">'
            f'<button name="cmd" value="{c}">{label}</button></form>'
            for c, label in (("refill", "Genopfyld"), ("clearFault", "Nulstil fejl"), ("reloadConfig", "Genindlæs config"))
        )
        rows.append(
            f"<tr class={'ok' if alive and not bad else 'bad'}>"
            f"<td><b>{html.escape(s.get('name', ''))}</b><br><small>{html.escape(device_id)}<br>v{html.escape(str(s.get('appVersion')))}</small></td>"
            f"<td>{age} s siden{'' if alive else ' <b>OFFLINE</b>'}</td>"
            f"<td>{html.escape(' / '.join(screen.get('lines', [])))}</td>"
            f"<td>{html.escape(', '.join(bad) or 'alt ok')}</td>"
            f"<td>{battery.get('level')} % {'⚡' if battery.get('charging') else 'IKKE LADER'} {battery.get('temperatureC')}°C</td>"
            f"<td>{html.escape(stock)}</td>"
            f"<td>{html.escape(json.dumps(s.get('counts', {})))}</td>"
            f"<td>{buttons}{' (' + str(queued[device_id]) + ' i kø)' if queued.get(device_id) else ''}</td></tr>"
        )
    events = "".join(
        f"<tr><td>{time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(e.get('ts', 0) / 1000))}</td>"
        f"<td>{html.escape(str(e.get('name')))}</td><td>{html.escape(str(e.get('type')))}</td>"
        f"<td><small>{html.escape(json.dumps({k: v for k, v in e.items() if k not in ('ts', 'type', 'name', 'deviceId')}, ensure_ascii=False))[:300]}</small></td></tr>"
        for e in recent_events()
    )
    return f"""<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width">
<meta http-equiv=refresh content=30><title>Automater</title>
<style>body{{font-family:system-ui;margin:16px}}table{{border-collapse:collapse;width:100%}}
td,th{{border-bottom:1px solid #ccc;padding:6px;text-align:left;vertical-align:top}}
tr.bad td:first-child{{border-left:6px solid #d33}}tr.ok td:first-child{{border-left:6px solid #3a3}}
form{{display:inline}}</style>
<h1>Automater</h1><table><tr><th>Automat</th><th>Sidst set</th><th>Skærm</th><th>Problemer</th>
<th>Batteri</th><th>Lager</th><th>Tællere</th><th></th></tr>{''.join(rows)}</table>
<h2>Seneste hændelser</h2><table>{events}</table>"""


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="application/json"):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        return self.rfile.read(int(self.headers.get("Content-Length", 0) or 0))

    def _admin_ok(self):
        expected = "Basic " + base64.b64encode(f"admin:{ADMIN_PASSWORD}".encode()).decode()
        if hmac.compare_digest(self.headers.get("Authorization", ""), expected):
            return True
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="Automat"')
        self.send_header("Content-Length", "0")
        self.end_headers()
        return False

    def do_GET(self):
        if self.path != "/":
            return self._send(404, "{}")
        if self._admin_ok():
            self._send(200, status_page(), "text/html; charset=utf-8")

    def do_POST(self):
        if self.path == "/api/heartbeat":
            if not hmac.compare_digest(self.headers.get("Authorization", ""), f"Bearer {TOKEN}"):
                return self._send(401, '{"error":"token"}')
            try:
                reply = handle_heartbeat(json.loads(self._body()))
            except (ValueError, json.JSONDecodeError) as e:
                return self._send(400, json.dumps({"error": str(e)}))
            return self._send(200, json.dumps(reply))

        if self.path == "/admin/command":
            if not self._admin_ok():
                return
            form = parse_qs(self._body().decode())
            device_id = form.get("deviceId", [""])[0]
            cmd = form.get("cmd", [""])[0]
            if cmd not in ALLOWED_COMMANDS or device_id not in devices:
                return self._send(400, '{"error":"ukendt automat eller kommando"}')
            try:
                extra = json.loads(form.get("args", ["{}"])[0] or "{}")
                assert isinstance(extra, dict)
            except (ValueError, AssertionError):
                return self._send(400, '{"error":"args skal være et JSON-objekt"}')
            with lock:
                commands.setdefault(device_id, []).append({**extra, "id": uuid.uuid4().hex[:8], "cmd": cmd})
            self.send_response(303)
            self.send_header("Location", "/")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        self._send(404, "{}")

    def log_message(self, fmt, *args):
        pass


def main():
    if not TOKEN or not ADMIN_PASSWORD:
        raise SystemExit("Sæt AUTOMAT_TOKEN og ADMIN_PASSWORD")
    load()
    threading.Thread(target=watchdog, daemon=True).start()
    print(f"Lytter på :{PORT}", flush=True)
    ThreadingHTTPServer(("", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
