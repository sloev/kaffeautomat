// Automat dashboard. Plain JavaScript, no build step. Talks to /api/* on the same origin.
'use strict';

const $main = document.getElementById('main');
const $nav = document.getElementById('nav');
let me = null;
let refreshTimer = null;

// ---- Helpers ------------------------------------------------------------------------------

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
    else if (k === 'class') node.className = v;
    else node.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) {
    if (c == null || c === false) continue;
    node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return node;
}

async function api(method, path, body) {
  const res = await fetch(`/api${path}`, {
    method,
    headers: method === 'GET' ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: 'same-origin',
  });
  const data = res.headers.get('Content-Type')?.includes('json') ? await res.json() : null;
  if (res.status === 401 && path !== '/login') {
    me = null;
    location.hash = '#/login';
    throw new Error('Log ind igen');
  }
  if (!res.ok) throw new Error(data?.error || `HTTP ${res.status}`);
  return data;
}

function ago(ms, now = Date.now()) {
  if (!ms) return 'aldrig';
  const s = Math.max(0, Math.round((now - ms) / 1000));
  if (s < 90) return `${s} s siden`;
  if (s < 5400) return `${Math.round(s / 60)} min siden`;
  if (s < 172800) return `${Math.round(s / 3600)} t siden`;
  return `${Math.round(s / 86400)} dage siden`;
}

function when(ms) {
  return new Date(ms).toLocaleString('da-DK', { dateStyle: 'short', timeStyle: 'medium' });
}

const kr = (ore) => `${(ore / 100).toLocaleString('da-DK', { minimumFractionDigits: ore % 100 ? 2 : 0 })} kr`;

/** ok / warn / bad / idle and a list of problems, from the latest heartbeat. */
function health(a) {
  if (!a.paired) return { level: 'idle', problems: ['ikke parret'] };
  if (!a.lastSeen) return { level: 'idle', problems: ['aldrig set'] };
  if (a.offline) return { level: 'bad', problems: ['ingen heartbeat'] };
  const h = a.status?.health || {};
  const problems = [];
  if (h.fault) problems.push(`fejl: ${h.fault}`);
  if (h.configError) problems.push(`config: ${h.configError}`);
  if (h.notificationAccess === false) problems.push('mangler notifikationsadgang');
  if (h.motorLink === false) problems.push('ingen motorstyring');
  if (h.online === false) problems.push('offline');
  if (h.uiVisible === false) problems.push('skærm lukket');
  if (h.camera === false) problems.push('kamera slukket');
  if (a.status?.battery && a.status.battery.charging === false) problems.push('lader ikke');
  const bad = h.fault || h.configError || h.notificationAccess === false;
  return { level: bad ? 'bad' : problems.length ? 'warn' : 'ok', problems };
}

/** Short message above the page; survives the page re-rendering itself. */
function flash(_container, text, kind = 'ok') {
  const m = el('div', { class: `msg ${kind}`, role: kind === 'error' ? 'alert' : 'status' }, text);
  document.getElementById('flash').append(m);
  setTimeout(() => m.remove(), kind === 'error' ? 8000 : 5000);
}

function guard(container, fn) {
  return async (event) => {
    event?.preventDefault?.();
    try {
      await fn(event);
    } catch (e) {
      flash(container, e.message, 'error');
    }
  };
}

function render(...nodes) {
  clearInterval(refreshTimer);
  $main.replaceChildren(...nodes);
}

function renderNav() {
  $nav.replaceChildren(
    ...(me
      ? [
          el('a', { href: '#/' }, 'Oversigt'),
          me.owner && el('a', { href: '#/ny' }, 'Ny automat'),
          me.owner && el('a', { href: '#/brugere' }, 'Admins'),
          !me.owner && el('a', { href: '#/konto' }, 'Konto'),
          el('span', { class: 'muted small' }, me.username),
          el('button', { class: 'secondary', onclick: logout }, 'Log ud'),
        ].filter(Boolean)
      : []),
  );
}

async function logout() {
  await api('POST', '/logout').catch(() => {});
  me = null;
  location.hash = '#/login';
}

// ---- Pages --------------------------------------------------------------------------------

function loginPage() {
  const box = el('div', { class: 'card narrow' });
  const form = el('form', {},
    el('label', {}, 'Brugernavn', el('input', { name: 'username', autocomplete: 'username', required: true })),
    el('label', {}, 'Kodeord', el('input', { name: 'password', type: 'password', autocomplete: 'current-password', required: true })),
    el('button', { type: 'submit' }, 'Log ind'),
  );
  form.addEventListener('submit', guard(box, async () => {
    await api('POST', '/login', { username: form.username.value, password: form.password.value });
    me = await api('GET', '/me');
    location.hash = '#/';
  }));
  box.append(form);
  render(el('h1', {}, 'Log ind'), box);
}

async function listPage() {
  const container = el('div');
  render(el('h1', {}, 'Automater'), container);
  const load = async () => {
    const { automats, now } = await api('GET', '/automats');
    if (!automats.length) {
      container.replaceChildren(el('p', { class: 'muted' }, me.owner ? 'Ingen automater endnu. ' : 'Du har ingen automater.',
        me.owner && el('a', { href: '#/ny' }, 'Opret den første')));
      return;
    }
    container.replaceChildren(el('div', { class: 'grid' }, automats.map((a) => {
      const h = health(a);
      const s = a.status;
      return el('a', { class: 'card', href: `#/a/${a.id}` },
        el('div', { class: 'row' }, el('span', { class: `dot ${h.level}` }), el('b', {}, a.name)),
        el('div', { class: 'muted small' }, `Sidst set ${ago(a.lastSeen, now)}`),
        s?.screen && el('div', { class: `screen ${s.screen.mood}` }, s.screen.lines.join('\n')),
        el('div', { class: 'small' }, h.problems.join(' · ') || 'Alt ok'),
        s?.battery && el('div', { class: 'muted small' }, `Batteri ${s.battery.level} %${s.battery.charging ? ' ⚡' : ''}`),
      );
    })));
  };
  await load();
  refreshTimer = setInterval(() => load().catch(() => {}), 30_000);
}

async function automatPage(id) {
  const top = el('div');
  render(top);
  const a = await api('GET', `/automats/${id}`);
  const h = health(a);
  const s = a.status || {};
  const reload = () => automatPage(id);
  const command = (body, label) => guard(top, async () => {
    await api('POST', `/automats/${id}/commands`, body);
    flash(top, `${label}: sendes ved næste heartbeat`);
    reload();
  });

  // Status
  const statusRows = [
    ['Sidst set', `${ago(a.lastSeen, a.now)}${a.lastSeen ? ` (${when(a.lastSeen)})` : ''}`],
    ['Tilstand', h.problems.join(' · ') || 'Alt ok'],
    s.battery && ['Batteri', `${s.battery.level} % · ${s.battery.charging ? 'lader' : 'LADER IKKE'} · ${s.battery.temperatureC} °C`],
    s.appVersion && ['App', `v${s.appVersion} · startet ${ago(s.appStartedAt, a.now)}`],
    s.counts && ['Siden start', `${s.counts.payments} betalinger · ${s.counts.ok} ok · ${s.counts.refund} refunderinger`],
    ['Config', `version ${a.configVersion}${s.configVersion != null && s.configVersion !== a.configVersion ? ` (telefonen har ${s.configVersion})` : ''}`],
    a.pendingCommands && ['Kommandoer i kø', a.pendingCommands],
  ].filter(Boolean);

  // Stock
  const products = a.config.products || [];
  const stock = s.stock || {};
  const stockTable = el('table', {},
    el('tr', {}, el('th', {}, 'Vare'), el('th', {}, 'Søjle'), el('th', {}, 'Pris'), el('th', {}, 'Lager'), el('th', {})),
    products.map((p) => {
      const input = el('input', { type: 'number', min: 0, value: stock[p.id] ?? '' , class: 'num' });
      return el('tr', {},
        el('td', {}, p.name), el('td', {}, p.slot ?? 0), el('td', {}, kr(p.priceOre)),
        el('td', {}, `${stock[p.id] ?? '?'} / ${p.capacity ?? '?'}`),
        el('td', {}, el('div', { class: 'row' }, input,
          el('button', { class: 'secondary', onclick: (e) => command({ cmd: 'setStock', productId: p.id, count: Number(input.value) }, 'Lager')(e) }, 'Sæt'))),
      );
    }),
  );

  // Config editor
  const editor = el('textarea', { spellcheck: 'false' }, JSON.stringify(a.config, null, 2));
  const saveConfig = guard(top, async () => {
    let config;
    try { config = JSON.parse(editor.value); } catch (e) { throw new Error(`Ugyldig JSON: ${e.message}`); }
    const r = await api('PUT', `/automats/${id}/config`, { config });
    flash(top, `Gemt som version ${r.configVersion} – telefonen henter den ved næste heartbeat`);
    reload();
  });

  // Settings
  const settings = el('form', { class: 'inline' },
    el('label', {}, 'Navn', el('input', { name: 'name', value: a.name })),
    el('label', {}, 'ntfy-emne til alarmer', el('input', { name: 'ntfy', value: a.ntfyTopic, placeholder: 'svært-at-gætte-emne' })),
    el('label', {}, 'Alarm efter (min uden heartbeat)', el('input', { name: 'offline', type: 'number', min: 10, value: Math.round(a.offlineAfterSec / 60), class: 'num' })),
    el('button', { type: 'submit' }, 'Gem'),
  );
  settings.addEventListener('submit', guard(top, async () => {
    await api('PATCH', `/automats/${id}`, { name: settings.name.value, ntfyTopic: settings.ntfy.value, offlineAfterSec: Number(settings.offline.value) * 60 });
    flash(top, 'Gemt');
    reload();
  }));

  // Events
  const events = el('table', { class: 'events' },
    el('tr', {}, el('th', {}, 'Tid'), el('th', {}, 'Type'), el('th', {}, 'Detaljer')),
    a.events.map((e) => {
      const { ts, type, ...rest } = e;
      return el('tr', {}, el('td', {}, when(ts)), el('td', {}, type), el('td', {}, JSON.stringify(rest)));
    }),
  );

  top.append(...[
    el('h1', { class: 'row' }, el('span', { class: `dot ${h.level}` }), a.name),
    s.screen && el('div', { class: `screen ${s.screen.mood}` }, `${s.screen.title}\n${s.screen.lines.join('\n')}`),
    el('div', { class: 'card' }, el('table', {}, statusRows.map(([k, v]) => el('tr', {}, el('th', {}, k), el('td', {}, v))))),
    el('h2', {}, 'Handlinger'),
    el('div', { class: 'row' },
      el('button', { onclick: command({ cmd: 'refill' }, 'Genopfyld') }, 'Genopfyld alle søjler'),
      el('button', { class: 'secondary', onclick: command({ cmd: 'clearFault' }, 'Nulstil fejl') }, 'Nulstil fejl'),
      el('button', { class: 'secondary', onclick: command({ cmd: 'reloadConfig' }, 'Genindlæs') }, 'Genindlæs config på telefonen'),
      products[0] && el('button', { class: 'secondary', onclick: (e) => {
        if (confirm(`Udlever ${products[0].name} uden betaling?`)) command({ cmd: 'simulate', amountOre: products[0].priceOre }, 'Testudlevering')(e);
      } }, 'Testudlevering'),
    ),
    el('h2', {}, 'Lager'), el('div', { class: 'card' }, stockTable),
    el('h2', {}, 'Indstillinger'), el('div', { class: 'card' }, settings),
    me.owner && await pairingSection(a, top, reload),
    me.owner && await adminsSection(a, top, reload),
    el('h2', {}, 'config.json'),
    el('p', { class: 'muted small' }, 'Varer, priser, tekster, statusvindue og kamera. Se docs/design.md.'),
    editor, el('div', { class: 'row mt' }, el('button', { onclick: saveConfig }, 'Gem config')),
    el('h2', {}, 'Seneste hændelser'), el('div', { class: 'card' }, events),
    me.owner && el('h2', {}, 'Slet'),
    me.owner && el('button', { class: 'danger', onclick: guard(top, async () => {
      if (!confirm(`Slet ${a.name} og alle dens hændelser? Telefonen mister forbindelsen.`)) return;
      await api('DELETE', `/automats/${id}`);
      location.hash = '#/';
    }) }, 'Slet automat'),
  ].flat().filter(Boolean));
}

async function pairingSection(a, top, reload) {
  const box = el('div', { class: 'card' });
  const newCode = guard(top, async () => {
    if (a.paired && !confirm('Lav en ny parringskode? Den nuværende telefon virker, indtil en ny telefon er parret.')) return;
    await api('POST', `/automats/${a.id}/pair`);
    reload();
  });
  if (a.pair) {
    box.append(
      el('p', {}, 'Åbn Automat-appen på telefonen, langt tryk → ', el('b', {}, 'Forbind til server'), ', og scan koden:'),
      el('img', { class: 'qr', src: `/api/automats/${a.id}/pair/qr.svg?c=${a.pair.code}`, alt: 'QR-kode til parring' }),
      el('p', {}, 'Eller indtast manuelt: ', el('span', { class: 'code' }, a.pair.code),
        el('br'), el('span', { class: 'muted small' }, `Server: ${JSON.parse(a.pair.payload).url} · gælder til ${when(a.pair.expires)} · kan bruges én gang`)),
    );
  } else {
    box.append(el('p', {}, a.paired ? 'Telefonen er parret.' : 'Ikke parret, og ingen aktiv kode.'));
  }
  box.append(el('div', { class: 'row' },
    el('button', { class: a.pair ? 'secondary' : '', onclick: newCode }, a.pair ? 'Ny kode' : 'Lav parringskode'),
    a.paired && el('button', { class: 'secondary', onclick: guard(top, async () => {
      if (!confirm('Afbryd forbindelsen til den nuværende telefon?')) return;
      await api('POST', `/automats/${a.id}/unpair`);
      reload();
    }) }, 'Afbryd telefon'),
  ));
  return [el('h2', {}, 'Telefon'), box];
}

async function adminsSection(a, top, reload) {
  const { users } = await api('GET', '/users');
  const assigned = new Set(a.admins.map((u) => u.id));
  const select = el('select', {}, el('option', { value: '' }, 'Vælg admin…'),
    users.filter((u) => !assigned.has(u.id)).map((u) => el('option', { value: u.id }, u.username)));
  return [
    el('h2', {}, 'Admins'),
    el('div', { class: 'card' },
      a.admins.length ? el('table', {}, a.admins.map((u) => el('tr', {}, el('td', {}, u.username), el('td', {},
        el('button', { class: 'secondary', onclick: guard(top, async () => {
          await api('DELETE', `/automats/${a.id}/admins/${u.id}`);
          reload();
        }) }, 'Fjern'))))) : el('p', { class: 'muted' }, 'Ingen admins – kun ejeren kan se automaten.'),
      el('div', { class: 'row mt' }, select,
        el('button', { onclick: guard(top, async () => {
          if (!select.value) return;
          await api('POST', `/automats/${a.id}/admins`, { userId: select.value });
          reload();
        }) }, 'Tilføj'),
        el('a', { href: '#/brugere', class: 'small' }, 'Opret ny admin')),
    ),
  ];
}

async function newAutomatPage() {
  const box = el('div', { class: 'card narrow' });
  const form = el('form', {},
    el('label', {}, 'Navn', el('input', { name: 'name', required: true, placeholder: 'Kaffeautomaten, Nørrebro' })),
    el('label', {}, 'Start fra eksempel', el('select', { name: 'template' }, me.templates.map((t) => el('option', { value: t }, t)))),
    el('label', {}, 'ntfy-emne til alarmer (valgfrit)', el('input', { name: 'ntfy', placeholder: 'svært-at-gætte-emne' })),
    el('button', { type: 'submit' }, 'Opret og vis parringskode'),
  );
  form.addEventListener('submit', guard(box, async () => {
    const r = await api('POST', '/automats', { name: form.name.value, template: form.template.value, ntfyTopic: form.ntfy.value });
    location.hash = `#/a/${r.id}`;
  }));
  box.append(form);
  render(el('h1', {}, 'Ny automat'),
    el('p', { class: 'muted' }, 'Automaten oprettes med en config fra eksemplet. Bagefter scanner telefonen en QR-kode og henter selv sin config.'),
    box);
}

async function usersPage() {
  const top = el('div');
  render(el('h1', {}, 'Admins'), top);
  const { users } = await api('GET', '/users');
  const { automats } = await api('GET', '/automats');
  const names = Object.fromEntries(automats.map((a) => [a.id, a.name]));
  const form = el('form', { class: 'inline' },
    el('label', {}, 'Brugernavn', el('input', { name: 'username', required: true, autocomplete: 'off' })),
    el('label', {}, 'Kodeord (min. 10 tegn)', el('input', { name: 'password', required: true, minlength: 10, autocomplete: 'new-password' })),
    el('button', { type: 'submit' }, 'Opret admin'),
  );
  form.addEventListener('submit', guard(top, async () => {
    await api('POST', '/users', { username: form.username.value, password: form.password.value });
    usersPage();
  }));
  top.append(
    el('div', { class: 'card' }, form,
      el('p', { class: 'muted small' }, 'Giv admin adgang til en automat fra automatens side. Del kodeordet sikkert – admin kan selv skifte det under Konto.')),
    el('h2', {}, 'Alle admins'),
    el('div', { class: 'card' }, users.length ? el('table', {},
      el('tr', {}, el('th', {}, 'Brugernavn'), el('th', {}, 'Automater'), el('th', {})),
      users.map((u) => el('tr', {},
        el('td', {}, u.username),
        el('td', {}, u.automats.map((id) => names[id] || id).join(', ') || '–'),
        el('td', {}, el('div', { class: 'row' },
          el('button', { class: 'secondary', onclick: guard(top, async () => {
            const password = prompt(`Nyt kodeord til ${u.username} (min. 10 tegn):`);
            if (!password) return;
            await api('PUT', `/users/${u.id}/password`, { password });
            flash(top, 'Kodeord skiftet – admin er logget ud');
          }) }, 'Nyt kodeord'),
          el('button', { class: 'danger', onclick: guard(top, async () => {
            if (!confirm(`Slet ${u.username}?`)) return;
            await api('DELETE', `/users/${u.id}`);
            usersPage();
          }) }, 'Slet'))),
      ))) : el('p', { class: 'muted' }, 'Ingen admins endnu.')),
  );
}

function accountPage() {
  const box = el('div', { class: 'card narrow' });
  const form = el('form', {},
    el('label', {}, 'Nuværende kodeord', el('input', { name: 'old', type: 'password', autocomplete: 'current-password', required: true })),
    el('label', {}, 'Nyt kodeord (min. 10 tegn)', el('input', { name: 'new', type: 'password', autocomplete: 'new-password', minlength: 10, required: true })),
    el('button', { type: 'submit' }, 'Skift kodeord'),
  );
  form.addEventListener('submit', guard(box, async () => {
    await api('POST', '/me/password', { oldPassword: form.old.value, newPassword: form.new.value });
    form.reset();
    flash(box, 'Kodeord skiftet');
  }));
  box.append(form);
  render(el('h1', {}, 'Konto'), box);
}

// ---- Router -------------------------------------------------------------------------------

async function route() {
  const hash = location.hash || '#/';
  if (!me && hash !== '#/login') {
    try {
      me = await api('GET', '/me');
    } catch {
      return;
    }
  }
  renderNav();
  let m;
  try {
    if (hash === '#/login') loginPage();
    else if (hash === '#/ny' && me.owner) await newAutomatPage();
    else if (hash === '#/brugere' && me.owner) await usersPage();
    else if (hash === '#/konto') accountPage();
    else if ((m = hash.match(/^#\/a\/([a-z0-9]+)$/))) await automatPage(m[1]);
    else await listPage();
  } catch (e) {
    render(el('div', { class: 'msg error' }, e.message));
  }
}

window.addEventListener('hashchange', route);
route();
