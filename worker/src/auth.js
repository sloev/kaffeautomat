// Passwords (PBKDF2) and stateless signed session cookies.
//
// Two kinds of accounts:
//  - the owner: one account defined by Worker secrets (OWNER_USERNAME / OWNER_PASSWORD).
//    Can provision automats and manage admins. Rotating OWNER_PASSWORD logs the owner out.
//  - admins: rows in `users`, assigned to specific automats. Changing a password or
//    deleting the user invalidates their sessions.

import { b64url, fromB64url, HttpError, safeEqual, sha256hex } from './util.js';

const enc = new TextEncoder();
const ITERATIONS = 100_000; // Workers' PBKDF2 maximum
const SESSION_DAYS = 7;
export const COOKIE = 'automat_session';

export async function hashPassword(password, salt = crypto.getRandomValues(new Uint8Array(16))) {
  const key = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: ITERATIONS }, key, 256);
  return `pbkdf2$${ITERATIONS}$${b64url(salt)}$${b64url(bits)}`;
}

export async function verifyPassword(password, stored) {
  const [scheme, iterations, salt, expected] = String(stored).split('$');
  if (scheme !== 'pbkdf2' || Number(iterations) !== ITERATIONS) return false;
  const actual = (await hashPassword(password, fromB64url(salt))).split('$')[3];
  return safeEqual(actual, expected);
}

export function validatePassword(password) {
  if (typeof password !== 'string' || password.length < 10) throw new HttpError(400, 'password must be at least 10 characters');
}

async function hmacKey(secret) {
  if (!secret || secret.length < 32) throw new HttpError(500, 'SESSION_SECRET must be set (32+ characters)');
  return crypto.subtle.importKey('raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign', 'verify']);
}

/** A short fingerprint of a credential: when it changes, old sessions stop working. */
async function fingerprint(secret) {
  return (await sha256hex(String(secret))).slice(0, 16);
}

export async function createSession(env, { owner, userId, username, credential }) {
  const payload = {
    o: owner ? 1 : 0,
    uid: userId || null,
    u: username,
    v: await fingerprint(credential),
    exp: Date.now() + SESSION_DAYS * 86_400_000,
  };
  const body = b64url(enc.encode(JSON.stringify(payload)));
  const sig = b64url(await crypto.subtle.sign('HMAC', await hmacKey(env.SESSION_SECRET), enc.encode(body)));
  return `${COOKIE}=${body}.${sig}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=${SESSION_DAYS * 86400}`;
}

export function clearSessionCookie() {
  return `${COOKIE}=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0`;
}

/** Returns { owner, userId, username } or null. Checks signature, expiry and credential fingerprint. */
export async function readSession(request, env) {
  const cookie = request.headers.get('Cookie') || '';
  const raw = cookie.split(/;\s*/).find((c) => c.startsWith(`${COOKIE}=`))?.slice(COOKIE.length + 1);
  if (!raw || !raw.includes('.')) return null;
  const [body, sig] = raw.split('.');
  let ok = false;
  try {
    ok = await crypto.subtle.verify('HMAC', await hmacKey(env.SESSION_SECRET), fromB64url(sig), enc.encode(body));
  } catch {
    return null;
  }
  if (!ok) return null;
  const p = JSON.parse(new TextDecoder().decode(fromB64url(body)));
  if (p.exp < Date.now()) return null;

  if (p.o) {
    if (p.v !== (await fingerprint(env.OWNER_PASSWORD))) return null;
    return { owner: true, userId: null, username: p.u };
  }
  const user = await env.DB.prepare('SELECT id, username, pw_hash FROM users WHERE id = ?').bind(p.uid).first();
  if (!user || p.v !== (await fingerprint(user.pw_hash))) return null;
  return { owner: false, userId: user.id, username: user.username };
}

export async function login(env, username, password) {
  if (typeof username !== 'string' || typeof password !== 'string') throw new HttpError(400, 'username and password required');
  const ownerName = env.OWNER_USERNAME || 'owner';
  if (username === ownerName) {
    if (!env.OWNER_PASSWORD) throw new HttpError(500, 'OWNER_PASSWORD is not set');
    // Hash both sides so the comparison is constant-time regardless of length.
    const ok = safeEqual(await sha256hex(password), await sha256hex(env.OWNER_PASSWORD));
    if (!ok) throw new HttpError(401, 'wrong username or password');
    return createSession(env, { owner: true, username, credential: env.OWNER_PASSWORD });
  }
  const user = await env.DB.prepare('SELECT id, username, pw_hash FROM users WHERE username = ?').bind(username).first();
  if (!user) {
    // Spend the same PBKDF2 time, so response time does not reveal whether the user exists.
    await hashPassword(password);
    throw new HttpError(401, 'wrong username or password');
  }
  if (!(await verifyPassword(password, user.pw_hash))) throw new HttpError(401, 'wrong username or password');
  return createSession(env, { owner: false, userId: user.id, username, credential: user.pw_hash });
}
