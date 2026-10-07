// End-to-end test against `wrangler dev` with a throwaway local D1 database.
// Run: npm test   (needs no Cloudflare account)

import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const PORT = 8790 + Math.floor(Math.random() * 100);
const BASE = `http://127.0.0.1:${PORT}`;
const OWNER_PASSWORD = 'owner-test-password';
const persist = mkdtempSync(join(tmpdir(), 'automat-d1-'));
let dev;

before(async () => {
  execFileSync('npx', ['wrangler', 'd1', 'migrations', 'apply', 'automat', '--local', '--persist-to', persist], { stdio: 'ignore' });
  dev = spawn('npx', [
    'wrangler', 'dev', '--port', String(PORT), '--ip', '127.0.0.1', '--persist-to', persist,
    '--var', `OWNER_PASSWORD:${OWNER_PASSWORD}`, '--var', 'SESSION_SECRET:test-secret-test-secret-test-secret-123',
    '--test-scheduled',
  ], { stdio: ['ignore', 'pipe', 'pipe'], detached: true });
  dev.stderr.on('data', (d) => process.env.DEBUG && process.stderr.write(d));
  for (let i = 0; i < 120; i++) {
    try {
      if ((await fetch(`${BASE}/`)).ok) return;
    } catch {}
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error('wrangler dev did not start');
});

after(() => {
  try { process.kill(-dev.pid); } catch {}
  rmSync(persist, { recursive: true, force: true });
});

/** Tiny client that keeps the session cookie. */
function client() {
  let cookie = '';
  return async (method, path, body, headers = {}) => {
    const res = await fetch(`${BASE}${path}`, {
      method,
      headers: { ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...(cookie ? { Cookie: cookie } : {}), ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const set = res.headers.get('Set-Cookie');
    if (set) cookie = set.split(';')[0];
    const text = await res.text();
    let data = text;
    try { data = JSON.parse(text); } catch {}
    return { status: res.status, data, headers: res.headers };
  };
}

async function beat(token, body = {}) {
  const res = await fetch(`${BASE}/api/heartbeat`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId: 'phone-1', name: 'x', configVersion: 0, health: { online: true }, events: [], ...body }),
  });
  return { status: res.status, data: await res.json() };
}

test('dashboard is served as static assets', async () => {
  const res = await fetch(`${BASE}/`);
  assert.equal(res.status, 200);
  assert.match(await res.text(), /<script src="\/app.js"/);
});

test('full flow: provision, pair, heartbeat, commands, config, admins', async () => {
  const owner = client();
  assert.equal((await owner('GET', '/api/automats')).status, 401);
  assert.equal((await owner('POST', '/api/login', { username: 'owner', password: 'wrong' })).status, 401);
  assert.equal((await owner('POST', '/api/login', { username: 'owner', password: OWNER_PASSWORD })).status, 200);

  // Writes need a JSON content type (CSRF guard).
  const form = await owner('POST', '/api/automats', undefined, { 'Content-Type': 'application/x-www-form-urlencoded' });
  assert.equal(form.status, 415);

  // Provision from the kaffeautomat example.
  const created = await owner('POST', '/api/automats', { name: 'Kaffeautomaten test', template: 'kaffeautomat' });
  assert.equal(created.status, 201);
  const { id, pairCode, pairPayload } = created.data;
  assert.deepEqual(JSON.parse(pairPayload), { url: `${BASE}/api`, code: pairCode });
  const qr = await owner('GET', `/api/automats/${id}/pair/qr.svg`);
  assert.equal(qr.status, 200);
  assert.match(qr.data, /<svg/);

  // The phone pairs once with the code (dashes/lowercase are fine).
  const typed = pairCode.toLowerCase().replace(/(.{5})/, '$1-');
  const paired = await fetch(`${BASE}/api/pair`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code: typed }) });
  assert.equal(paired.status, 200);
  const p = await paired.json();
  assert.equal(p.automatId, id);
  assert.equal(p.config.name, 'Kaffeautomaten test');
  assert.equal(p.config.products[0].priceOre, 6000);
  const again = await fetch(`${BASE}/api/pair`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code: pairCode }) });
  assert.equal(again.status, 404);

  // Heartbeat with the device token.
  assert.equal((await beat('wrong-token')).status, 401);
  let hb = await beat(p.token, { configVersion: p.configVersion, events: [{ ts: 1, type: 'sale', amountOre: 6000 }] });
  assert.equal(hb.status, 200);
  assert.deepEqual(hb.data.commands, []);
  assert.equal(hb.data.config, undefined, 'config is only sent when the phone is behind');

  // Owner queues a command; it is delivered exactly once.
  assert.equal((await owner('POST', `/api/automats/${id}/commands`, { cmd: 'refill' })).status, 201);
  assert.equal((await owner('POST', `/api/automats/${id}/commands`, { cmd: 'rm -rf' })).status, 400);
  hb = await beat(p.token, { configVersion: p.configVersion });
  assert.deepEqual(hb.data.commands.map((c) => c.cmd), ['refill']);
  hb = await beat(p.token, { configVersion: p.configVersion });
  assert.deepEqual(hb.data.commands, []);

  // Config change: validated, version bumped, pushed on next heartbeat.
  const config = { ...p.config, products: [{ ...p.config.products[0], priceOre: 6500 }] };
  assert.equal((await owner('PUT', `/api/automats/${id}/config`, { config: { products: [] } })).status, 400);
  const saved = await owner('PUT', `/api/automats/${id}/config`, { config });
  assert.equal(saved.data.configVersion, p.configVersion + 1);
  hb = await beat(p.token, { configVersion: p.configVersion });
  assert.equal(hb.data.configVersion, p.configVersion + 1);
  assert.equal(hb.data.config.products[0].priceOre, 6500);

  // Dashboard sees status and events.
  const detail = await owner('GET', `/api/automats/${id}`);
  assert.equal(detail.data.paired, true);
  assert.ok(detail.data.lastSeen);
  assert.ok(detail.data.events.some((e) => e.type === 'sale'));
  assert.ok(detail.data.events.some((e) => e.type === 'paired'));

  // Admin account: sees only assigned automats, cannot provision.
  assert.equal((await owner('POST', '/api/users', { username: 'anna', password: 'short' })).status, 400);
  const user = await owner('POST', '/api/users', { username: 'anna', password: 'anna-password-1' });
  assert.equal(user.status, 201);
  const other = await owner('POST', '/api/automats', { name: 'Æg', template: 'aeg' });

  const anna = client();
  assert.equal((await anna('POST', '/api/login', { username: 'anna', password: 'anna-password-1' })).status, 200);
  assert.deepEqual((await anna('GET', '/api/automats')).data.automats, []);
  assert.equal((await anna('GET', `/api/automats/${id}`)).status, 404);

  await owner('POST', `/api/automats/${id}/admins`, { userId: user.data.id });
  const list = (await anna('GET', '/api/automats')).data.automats;
  assert.deepEqual(list.map((a) => a.id), [id]);
  assert.equal((await anna('GET', `/api/automats/${other.data.id}`)).status, 404);
  assert.equal((await anna('POST', `/api/automats/${id}/commands`, { cmd: 'clearFault' })).status, 201);
  assert.equal((await anna('POST', '/api/automats', { name: 'nope' })).status, 403);
  assert.equal((await anna('POST', `/api/automats/${id}/pair`, {})).status, 403);
  assert.equal((await anna('GET', '/api/users')).status, 403);
  assert.equal((await anna('GET', `/api/automats/${id}`)).data.pair, null);

  // Password change by the owner logs the admin out.
  await owner('PUT', `/api/users/${user.data.id}/password`, { password: 'anna-password-2' });
  assert.equal((await anna('GET', '/api/automats')).status, 401);

  // Unpair: old token stops working.
  await owner('POST', `/api/automats/${id}/unpair`, {});
  assert.equal((await beat(p.token)).status, 401);

  // Delete.
  assert.equal((await owner('DELETE', `/api/automats/${other.data.id}`, {})).status, 200);
  assert.equal((await owner('GET', `/api/automats/${other.data.id}`)).status, 404);
});

test('cron runs', async () => {
  const res = await fetch(`${BASE}/__scheduled?cron=*/5+*+*+*+*`);
  assert.equal(res.status, 200);
});
