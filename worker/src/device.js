// API used by the phones: pairing and heartbeat.
//
// Built to stay inside Cloudflare's free tier:
//  - one heartbeat = one Worker request, 2 D1 reads, and usually 0 D1 writes;
//  - the status row is only rewritten when something changed or every 5 minutes;
//  - the config is only sent when the phone's configVersion is behind.

import { alert, alertForEvent } from './alerts.js';
import { HttpError, json, randomToken, readJson, sha256hex } from './util.js';

const LAST_SEEN_WRITE_MS = 5 * 60_000;
const MAX_EVENTS = 200;

export async function pair(request, env) {
  const { code } = await readJson(request, 4096);
  const normalized = String(code || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
  if (!normalized) throw new HttpError(400, 'code missing');

  const row = await env.DB.prepare(
    'SELECT id, name, config, config_version, pair_expires FROM automats WHERE pair_code = ?',
  ).bind(normalized).first();
  if (!row || row.pair_expires < Date.now()) throw new HttpError(404, 'unknown or expired pairing code');

  const token = randomToken();
  const now = Date.now();
  const [update] = await env.DB.batch([
    env.DB.prepare(
      'UPDATE automats SET token_hash = ?, pair_code = NULL, pair_expires = NULL WHERE id = ? AND pair_code = ?',
    ).bind(await sha256hex(token), row.id, normalized),
    env.DB.prepare('INSERT INTO events (automat_id, ts, type, body) VALUES (?, ?, ?, ?)')
      .bind(row.id, now, 'paired', JSON.stringify({ ts: now, type: 'paired' })),
  ]);
  if (update.meta.changes !== 1) throw new HttpError(409, 'pairing code already used');

  return json({
    token,
    automatId: row.id,
    name: row.name,
    config: JSON.parse(row.config),
    configVersion: row.config_version,
  });
}

/** Everything in the heartbeat except fields that change on every beat. */
function stableStatus(body) {
  const { time, phoneUptimeSec, battery, events, configVersion, ...rest } = body;
  return JSON.stringify({ ...rest, battery: { ...battery, temperatureC: undefined } });
}

export async function heartbeat(request, env, ctx) {
  const auth = request.headers.get('Authorization') || '';
  if (!auth.startsWith('Bearer ')) throw new HttpError(401, 'token missing');
  const tokenHash = await sha256hex(auth.slice(7));
  const automat = await env.DB.prepare(
    'SELECT id, name, config_version, status_hash, last_seen, offline_alerted, ntfy_topic FROM automats WHERE token_hash = ?',
  ).bind(tokenHash).first();
  if (!automat) throw new HttpError(401, 'unknown device token');

  const body = await readJson(request);
  const events = Array.isArray(body.events) ? body.events.slice(-MAX_EVENTS) : [];
  const phoneConfigVersion = Number(body.configVersion || 0);
  delete body.events;

  const now = Date.now();
  const statusHash = await sha256hex(stableStatus(body));
  const writes = [];

  if (statusHash !== automat.status_hash || automat.offline_alerted || !automat.last_seen || now - automat.last_seen > LAST_SEEN_WRITE_MS) {
    writes.push(
      env.DB.prepare('UPDATE automats SET status = ?, status_hash = ?, last_seen = ?, offline_alerted = 0 WHERE id = ?')
        .bind(JSON.stringify(body), statusHash, now, automat.id),
    );
  }
  for (const e of events) {
    if (!e || typeof e !== 'object') continue;
    writes.push(
      env.DB.prepare('INSERT INTO events (automat_id, ts, type, body) VALUES (?, ?, ?, ?)')
        .bind(automat.id, Number(e.ts) || now, String(e.type || 'unknown').slice(0, 40), JSON.stringify(e).slice(0, 8000)),
    );
    const message = alertForEvent(automat.name, e);
    if (message) alert(ctx, automat.ntfy_topic, message);
  }
  if (automat.offline_alerted) alert(ctx, automat.ntfy_topic, `${automat.name}: online igen`);

  // Commands: read and delete in the same batch, so each is delivered at most once.
  const pending = await env.DB.prepare('SELECT id, body FROM commands WHERE automat_id = ? ORDER BY created')
    .bind(automat.id).all();
  const commands = pending.results.map((c) => ({ ...JSON.parse(c.body), id: c.id }));
  if (commands.length) {
    writes.push(
      env.DB.prepare(`DELETE FROM commands WHERE id IN (${commands.map(() => '?').join(',')})`)
        .bind(...commands.map((c) => c.id)),
    );
  }
  if (writes.length) await env.DB.batch(writes);

  const reply = { commands, configVersion: automat.config_version };
  if (phoneConfigVersion !== automat.config_version) {
    const row = await env.DB.prepare('SELECT config FROM automats WHERE id = ?').bind(automat.id).first();
    reply.config = JSON.parse(row.config);
  }
  return json(reply);
}

/** Cron: alert once when an automat stops sending heartbeats; prune old events. */
export async function scheduled(env, ctx) {
  const now = Date.now();
  const stale = await env.DB.prepare(
    `SELECT id, name, ntfy_topic, last_seen FROM automats
     WHERE offline_alerted = 0 AND last_seen IS NOT NULL AND last_seen < ? - offline_after * 1000`,
  ).bind(now).all();
  if (stale.results.length) {
    await env.DB.batch(stale.results.map((a) => env.DB.prepare('UPDATE automats SET offline_alerted = 1 WHERE id = ?').bind(a.id)));
    for (const a of stale.results) {
      alert(ctx, a.ntfy_topic, `${a.name}: ingen heartbeat i ${Math.round((now - a.last_seen) / 60_000)} min`);
    }
  }

  // Once a day (the 03:00–03:04 UTC run): drop events older than the retention period.
  const d = new Date(now);
  if (d.getUTCHours() === 3 && d.getUTCMinutes() < 5) {
    const days = Number(env.EVENT_RETENTION_DAYS || 90);
    await env.DB.prepare('DELETE FROM events WHERE ts < ?').bind(now - days * 86_400_000).run();
  }
}
