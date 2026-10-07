// API used by the dashboard. Everything requires a session; provisioning requires the owner.

import { clearSessionCookie, hashPassword, login, readSession, validatePassword, verifyPassword, createSession } from './auth.js';
import { configFromTemplate, TEMPLATES, validateConfig } from './config.js';
import { qrSvg } from './qr.js';
import { HttpError, json, randomCode, randomId, readJson } from './util.js';

const PAIR_CODE_HOURS = 24;
const COMMANDS = {
  refill: () => ({}),
  clearFault: () => ({}),
  reloadConfig: () => ({}),
  setStock: (a) => {
    if (typeof a.productId !== 'string' || !Number.isInteger(a.count) || a.count < 0) throw new HttpError(400, 'productId and count required');
    return { productId: a.productId, count: a.count };
  },
  simulate: (a) => {
    if (!Number.isInteger(a.amountOre) || a.amountOre <= 0) throw new HttpError(400, 'amountOre required');
    return { amountOre: a.amountOre };
  },
};

async function requireSession(request, env) {
  const session = await readSession(request, env);
  if (!session) throw new HttpError(401, 'not logged in');
  // Cookies are SameSite=Strict; requiring a JSON content type on writes also blocks plain HTML form posts.
  if (request.method !== 'GET' && !(request.headers.get('Content-Type') || '').startsWith('application/json')) {
    throw new HttpError(415, 'use application/json');
  }
  return session;
}

function requireOwner(session) {
  if (!session.owner) throw new HttpError(403, 'owner only');
}

async function requireAccess(env, session, automatId) {
  if (session.owner) return;
  const row = await env.DB.prepare('SELECT 1 FROM automat_admins WHERE automat_id = ? AND user_id = ?')
    .bind(automatId, session.userId).first();
  if (!row) throw new HttpError(404, 'automat not found');
}

function newPairCode() {
  return { code: randomCode(10), expires: Date.now() + PAIR_CODE_HOURS * 3_600_000 };
}

function pairPayload(request, code) {
  // What the phone's QR scanner reads: where the API is, and the one-time code.
  return JSON.stringify({ url: `${new URL(request.url).origin}/api`, code });
}

function summary(row) {
  return {
    id: row.id,
    name: row.name,
    lastSeen: row.last_seen,
    offline: !!row.offline_alerted,
    paired: !!row.paired,
    pairing: !!row.pairing,
    configVersion: row.config_version,
    status: row.status ? JSON.parse(row.status) : null,
  };
}

const SUMMARY_COLUMNS = `a.id, a.name, a.last_seen, a.offline_alerted, a.config_version, a.status,
  a.token_hash IS NOT NULL AS paired, a.pair_code IS NOT NULL AS pairing`;

export async function handleAdmin(request, env, ctx, path) {
  const method = request.method;
  let m;

  // ---- Session -------------------------------------------------------------------------
  if (path === '/api/login' && method === 'POST') {
    const { username, password } = await readJson(request, 4096);
    const cookie = await login(env, username, password);
    return json({ ok: true }, 200, { 'Set-Cookie': cookie });
  }
  if (path === '/api/logout' && method === 'POST') {
    return json({ ok: true }, 200, { 'Set-Cookie': clearSessionCookie() });
  }

  const session = await requireSession(request, env);

  if (path === '/api/me' && method === 'GET') {
    return json({ username: session.username, owner: session.owner, templates: Object.keys(TEMPLATES) });
  }
  if (path === '/api/me/password' && method === 'POST') {
    if (session.owner) throw new HttpError(400, 'the owner password is a Worker secret: npx wrangler secret put OWNER_PASSWORD');
    const { oldPassword, newPassword } = await readJson(request, 4096);
    validatePassword(newPassword);
    const user = await env.DB.prepare('SELECT pw_hash FROM users WHERE id = ?').bind(session.userId).first();
    if (!(await verifyPassword(oldPassword, user.pw_hash))) throw new HttpError(401, 'wrong password');
    const pwHash = await hashPassword(newPassword);
    await env.DB.prepare('UPDATE users SET pw_hash = ? WHERE id = ?').bind(pwHash, session.userId).run();
    const cookie = await createSession(env, { owner: false, userId: session.userId, username: session.username, credential: pwHash });
    return json({ ok: true }, 200, { 'Set-Cookie': cookie });
  }

  // ---- Automats ------------------------------------------------------------------------
  if (path === '/api/automats' && method === 'GET') {
    const stmt = session.owner
      ? env.DB.prepare(`SELECT ${SUMMARY_COLUMNS} FROM automats a ORDER BY a.name`)
      : env.DB.prepare(`SELECT ${SUMMARY_COLUMNS} FROM automats a JOIN automat_admins aa ON aa.automat_id = a.id
                        WHERE aa.user_id = ? ORDER BY a.name`).bind(session.userId);
    const { results } = await stmt.all();
    return json({ automats: results.map(summary), now: Date.now() });
  }

  if (path === '/api/automats' && method === 'POST') {
    requireOwner(session);
    const { name, template = 'kaffeautomat', ntfyTopic = '' } = await readJson(request, 4096);
    if (typeof name !== 'string' || !name.trim()) throw new HttpError(400, 'name required');
    const config = configFromTemplate(template, name.trim());
    const id = randomId();
    const pair = newPairCode();
    await env.DB.prepare(
      `INSERT INTO automats (id, name, pair_code, pair_expires, config, ntfy_topic, created)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
    ).bind(id, name.trim(), pair.code, pair.expires, JSON.stringify(config), ntfyTopic || null, Date.now()).run();
    return json({ id, pairCode: pair.code, pairExpires: pair.expires, pairPayload: pairPayload(request, pair.code) }, 201);
  }

  if ((m = path.match(/^\/api\/automats\/([a-z0-9]+)(\/.*)?$/))) {
    const id = m[1];
    const sub = m[2] || '';
    await requireAccess(env, session, id);
    const automat = await env.DB.prepare('SELECT * FROM automats WHERE id = ?').bind(id).first();
    if (!automat) throw new HttpError(404, 'automat not found');

    if (sub === '' && method === 'GET') {
      const [events, admins, pending] = await env.DB.batch([
        env.DB.prepare('SELECT ts, type, body FROM events WHERE automat_id = ? ORDER BY ts DESC LIMIT 100').bind(id),
        env.DB.prepare('SELECT u.id, u.username FROM users u JOIN automat_admins aa ON aa.user_id = u.id WHERE aa.automat_id = ? ORDER BY u.username').bind(id),
        env.DB.prepare('SELECT COUNT(*) AS n FROM commands WHERE automat_id = ?').bind(id),
      ]);
      const pairingActive = automat.pair_code && automat.pair_expires > Date.now();
      return json({
        ...summary({ ...automat, paired: automat.token_hash != null, pairing: pairingActive }),
        now: Date.now(),
        config: JSON.parse(automat.config),
        ntfyTopic: automat.ntfy_topic || '',
        offlineAfterSec: automat.offline_after,
        pendingCommands: pending.results[0].n,
        events: events.results.map((e) => ({ ts: e.ts, type: e.type, ...JSON.parse(e.body) })),
        admins: admins.results,
        pair: session.owner && pairingActive
          ? { code: automat.pair_code, expires: automat.pair_expires, payload: pairPayload(request, automat.pair_code) }
          : null,
      });
    }

    if (sub === '' && method === 'PATCH') {
      const body = await readJson(request, 4096);
      const name = typeof body.name === 'string' && body.name.trim() ? body.name.trim() : automat.name;
      const ntfy = typeof body.ntfyTopic === 'string' ? body.ntfyTopic.trim() || null : automat.ntfy_topic;
      const offline = Number.isInteger(body.offlineAfterSec) ? Math.max(600, body.offlineAfterSec) : automat.offline_after;
      await env.DB.prepare('UPDATE automats SET name = ?, ntfy_topic = ?, offline_after = ? WHERE id = ?')
        .bind(name, ntfy, offline, id).run();
      return json({ ok: true });
    }

    if (sub === '/config' && method === 'PUT') {
      const { config } = await readJson(request, 128 * 1024);
      const clean = validateConfig(config);
      // The phone picks up the new version on its next heartbeat.
      await env.DB.prepare('UPDATE automats SET config = ?, config_version = config_version + 1 WHERE id = ?')
        .bind(JSON.stringify(clean), id).run();
      return json({ ok: true, configVersion: automat.config_version + 1 });
    }

    if (sub === '/commands' && method === 'POST') {
      const body = await readJson(request, 4096);
      const make = COMMANDS[body.cmd];
      if (!make) throw new HttpError(400, `unknown command; use one of: ${Object.keys(COMMANDS).join(', ')}`);
      const command = { cmd: body.cmd, ...make(body) };
      await env.DB.prepare('INSERT INTO commands (id, automat_id, body, created) VALUES (?, ?, ?, ?)')
        .bind(randomId(), id, JSON.stringify(command), Date.now()).run();
      return json({ ok: true }, 201);
    }

    // ---- Owner only: pairing, admins, delete ---------------------------------------------
    requireOwner(session);

    if (sub === '/pair' && method === 'POST') {
      // New code; the old phone keeps working until the new one has paired.
      const pair = newPairCode();
      await env.DB.prepare('UPDATE automats SET pair_code = ?, pair_expires = ? WHERE id = ?').bind(pair.code, pair.expires, id).run();
      return json({ pairCode: pair.code, pairExpires: pair.expires, pairPayload: pairPayload(request, pair.code) });
    }

    if (sub === '/pair/qr.svg' && method === 'GET') {
      if (!automat.pair_code || automat.pair_expires < Date.now()) throw new HttpError(404, 'no active pairing code');
      return new Response(qrSvg(pairPayload(request, automat.pair_code)), {
        headers: { 'Content-Type': 'image/svg+xml', 'Cache-Control': 'private, no-store' },
      });
    }

    if (sub === '/unpair' && method === 'POST') {
      await env.DB.prepare('UPDATE automats SET token_hash = NULL WHERE id = ?').bind(id).run();
      return json({ ok: true });
    }

    if (sub === '/admins' && method === 'POST') {
      const { userId } = await readJson(request, 4096);
      const user = await env.DB.prepare('SELECT id FROM users WHERE id = ?').bind(userId).first();
      if (!user) throw new HttpError(404, 'user not found');
      await env.DB.prepare('INSERT OR IGNORE INTO automat_admins (automat_id, user_id) VALUES (?, ?)').bind(id, userId).run();
      return json({ ok: true });
    }

    if ((m = sub.match(/^\/admins\/([a-z0-9]+)$/)) && method === 'DELETE') {
      await env.DB.prepare('DELETE FROM automat_admins WHERE automat_id = ? AND user_id = ?').bind(id, m[1]).run();
      return json({ ok: true });
    }

    if (sub === '' && method === 'DELETE') {
      await env.DB.batch(
        ['events', 'commands', 'automat_admins'].map((t) => env.DB.prepare(`DELETE FROM ${t} WHERE automat_id = ?`).bind(id))
          .concat(env.DB.prepare('DELETE FROM automats WHERE id = ?').bind(id)),
      );
      return json({ ok: true });
    }
  }

  // ---- Users (owner only) --------------------------------------------------------------
  if (path === '/api/users' && method === 'GET') {
    requireOwner(session);
    const { results } = await env.DB.prepare(
      `SELECT u.id, u.username, u.created, GROUP_CONCAT(aa.automat_id) AS automats
       FROM users u LEFT JOIN automat_admins aa ON aa.user_id = u.id GROUP BY u.id ORDER BY u.username`,
    ).all();
    return json({ users: results.map((u) => ({ ...u, automats: u.automats ? u.automats.split(',') : [] })) });
  }

  if (path === '/api/users' && method === 'POST') {
    requireOwner(session);
    const { username, password } = await readJson(request, 4096);
    if (typeof username !== 'string' || !/^[a-zA-Z0-9._@-]{3,64}$/.test(username)) throw new HttpError(400, 'username: 3-64 letters, digits or . _ @ -');
    if (username === (env.OWNER_USERNAME || 'owner')) throw new HttpError(400, 'that username is reserved');
    validatePassword(password);
    const id = randomId();
    try {
      await env.DB.prepare('INSERT INTO users (id, username, pw_hash, created) VALUES (?, ?, ?, ?)')
        .bind(id, username, await hashPassword(password), Date.now()).run();
    } catch {
      throw new HttpError(409, 'username already exists');
    }
    return json({ id }, 201);
  }

  if ((m = path.match(/^\/api\/users\/([a-z0-9]+)(\/password)?$/))) {
    requireOwner(session);
    const userId = m[1];
    if (m[2] && method === 'PUT') {
      const { password } = await readJson(request, 4096);
      validatePassword(password);
      await env.DB.prepare('UPDATE users SET pw_hash = ? WHERE id = ?').bind(await hashPassword(password), userId).run();
      return json({ ok: true });
    }
    if (!m[2] && method === 'DELETE') {
      await env.DB.batch([
        env.DB.prepare('DELETE FROM automat_admins WHERE user_id = ?').bind(userId),
        env.DB.prepare('DELETE FROM users WHERE id = ?').bind(userId),
      ]);
      return json({ ok: true });
    }
  }

  throw new HttpError(404, 'not found');
}
