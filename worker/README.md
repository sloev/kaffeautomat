# Automat server (Cloudflare Worker)

One deployment serves all your automats: provisioning, phone pairing, heartbeats,
dashboard, alerts, remote commands and config. Runs on the **Cloudflare free plan**
(Workers + D1 + static assets + cron) – no subscription.

- `public/` – the dashboard (plain HTML/JS/CSS, no build step), served from Cloudflare's
  edge cache without running the Worker.
- `src/` – the API: `device.js` (phones), `admin.js` (dashboard), `auth.js` (passwords, sessions).
- `migrations/` – D1 schema.
- New automats start from the configs in [`../examples`](../examples).

## Deploy

```sh
cd worker
npm install
npx wrangler login
npx wrangler d1 create automat              # put the printed database_id in wrangler.jsonc
npm run db:migrate                          # create tables in the remote database
npx wrangler secret put OWNER_PASSWORD      # the provisioning login (username: owner)
npx wrangler secret put SESSION_SECRET      # 32+ random characters, e.g. `openssl rand -base64 48`
npm run deploy
```

Open `https://automat.<your-subdomain>.workers.dev/`, log in as `owner`, and create
your first automat. Phones pair by scanning the QR code shown on the automat's page.

Change the owner username with the `OWNER_USERNAME` var in `wrangler.jsonc`.
Rotating `OWNER_PASSWORD` or `SESSION_SECRET` logs everyone out.

## Develop

```sh
cp .dev.vars.example .dev.vars
npm run db:migrate:local
npm run dev                                 # http://localhost:8787
npm test                                    # end-to-end test against a throwaway local D1
```

## Cost on the free plan

| | Free allowance | One automat at `heartbeatSec: 60` |
|---|---|---|
| Worker requests | 100,000 / day | ~1,440 / day |
| D1 rows read | 5,000,000 / day | ~3,000 / day |
| D1 rows written | 100,000 / day | ~300 / day + events |

A heartbeat reads the automat and its pending commands and normally writes nothing:
the status row is only rewritten when something changed or every 5 minutes, and the
config is only sent when the phone is behind. That leaves room for roughly 60 automats;
raise `heartbeatSec` in a config to fit more. Dashboard files never hit the Worker.

## API

See [`../docs/design.md`](../docs/design.md) section 8 for the phone protocol
(`/api/pair`, `/api/heartbeat`). Dashboard endpoints (`/api/login`, `/api/automats`, …)
need a session cookie and `Content-Type: application/json` on writes.

## Security notes

- Device tokens and pairing codes are random; only SHA-256 of the device token is stored.
  Pairing codes are single use and expire after 24 hours.
- Admin passwords: PBKDF2-SHA256, 100,000 iterations. Sessions: HMAC-signed cookies,
  `HttpOnly; Secure; SameSite=Strict`, invalidated by password changes.
- The dashboard sends a strict Content-Security-Policy (`public/_headers`).
- There is no login rate limit in the code. Consider a
  [Cloudflare rate limiting rule](https://developers.cloudflare.com/waf/rate-limiting-rules/)
  on `/api/login` (one rule is included in the free plan).
