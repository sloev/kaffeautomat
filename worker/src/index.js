// Automat server on Cloudflare Workers + D1.
//
//   /api/pair, /api/heartbeat   phones (device token)
//   /api/*                      dashboard API (session cookie)
//   everything else             static dashboard from ./public, served by the edge without
//                               running this code (see "assets" in wrangler.jsonc)

import { handleAdmin } from './admin.js';
import { heartbeat, pair, scheduled } from './device.js';
import { HttpError, json } from './util.js';

export default {
  async fetch(request, env, ctx) {
    const path = new URL(request.url).pathname;
    try {
      if (path === '/api/heartbeat' && request.method === 'POST') return await heartbeat(request, env, ctx);
      if (path === '/api/pair' && request.method === 'POST') return await pair(request, env);
      if (path.startsWith('/api/')) return await handleAdmin(request, env, ctx, path);
      return env.ASSETS.fetch(request);
    } catch (e) {
      if (e instanceof HttpError) return json({ error: e.message }, e.status);
      console.error(e);
      return json({ error: 'internal error' }, 500);
    }
  },

  async scheduled(event, env, ctx) {
    await scheduled(env, ctx);
  },
};
