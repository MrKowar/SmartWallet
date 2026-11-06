// SmartWallet — сервер тестовых кодов и админ-панель (Cloudflare Workers).
//
// Как это работает:
//  • Коды вида SWT-XXXX-XXXX-XXXX вычисляются из закрытого ключа (HMAC), поэтому их не нужно
//    нигде хранить: сервер узнаёт свой код по подписи, подобрать чужой невозможно.
//  • Человек вводит код в приложении → POST /redeem {code, device} → сервер один раз привязывает
//    код к телефону и выдаёт обычный ключ SW-… на N дней, подписанный тем же ключом, что KeyGen.
//    Дальше приложение проверяет лицензию само, без интернета.
//  • Админ-панель живёт по секретному адресу /a/<токен>. Токен тоже вычисляется из закрытого
//    ключа (java tools/KeyGen.java codes его печатает). Любой другой адрес отвечает 404,
//    в APK о панели нет ни строчки.
//
// Переменные окружения:
//   LICENSE_PRIVATE_KEY — секрет: содержимое %USERPROFILE%\SmartWalletKeys\private_key.txt
//   BATCHES             — партии кодов «буква:количество:дней», через запятую. Пример: "A:100:30"
//   CODES_KV            — KV namespace, где хранится, какой код к какому телефону привязан

const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const DEVICE_CODE = /^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$/;
const enc = new TextEncoder();

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    try {
      if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors() });
      if (url.pathname === '/redeem' && request.method === 'POST') return await redeem(request, env);

      const admin = url.pathname.match(/^\/a\/([A-Za-z0-9]+)(\/api)?$/);
      if (admin && request.method === 'GET') {
        const secrets = await deriveSecrets(env);
        if (!safeEqual(admin[1], secrets.adminToken)) return notFound();
        return admin[2] ? await adminApi(env, secrets) : adminPage();
      }
      return notFound();
    } catch (e) {
      return json({ ok: false, message: 'Ошибка сервера, попробуйте позже' }, 500);
    }
  }
};

// ---------- Активация ----------

async function redeem(request, env) {
  let body;
  try { body = await request.json(); } catch { return json({ ok: false, message: 'Неверный запрос' }, 400); }
  const code = normalizeCode(String(body.code || ''));
  const device = String(body.device || '').toUpperCase();
  if (!DEVICE_CODE.test(device)) return json({ ok: false, message: 'Неверный код устройства' }, 400);

  const secrets = await deriveSecrets(env);
  const info = code && await checkCode(code, secrets, batches(env));
  if (!info) return json({ ok: false, message: 'Такого кода нет. Проверьте, что он введён без ошибок' }, 404);

  const kvKey = 'r:' + info.code;
  const existing = await env.CODES_KV.get(kvKey, 'json');
  if (existing) {
    if (existing.device !== device) {
      return json({ ok: false, message: 'Этот код уже активирован на другом телефоне' }, 409);
    }
    return json({ ok: true, key: existing.key, expiry: existing.expiry });
  }

  const expiry = todayEpochDay() + info.days;
  const key = await signLicense(secrets.privateKey, device, 'T', expiry);
  const record = { device, key, expiry, at: new Date().toISOString(), batch: info.batch };
  await env.CODES_KV.put(kvKey, JSON.stringify(record));
  return json({ ok: true, key, expiry });
}

// ---------- Админ-панель ----------

async function adminApi(env, secrets) {
  const used = new Map();
  let cursor;
  do {
    const page = await env.CODES_KV.list({ prefix: 'r:', cursor });
    await Promise.all(page.keys.map(async k => {
      const v = await env.CODES_KV.get(k.name, 'json');
      if (v) used.set(k.name.slice(2), v);
    }));
    cursor = page.list_complete ? null : page.cursor;
  } while (cursor);

  const codes = [];
  for (const b of batches(env)) {
    for (let n = 0; n < b.count; n++) {
      const code = await makeCode(secrets.codeKey, b.letter, n);
      const r = used.get(code);
      codes.push({
        code, batch: b.letter, days: b.days, n: n + 1,
        device: r ? r.device : null, at: r ? r.at : null, expiry: r ? r.expiry : null
      });
    }
  }
  return new Response(JSON.stringify({ today: todayEpochDay(), codes }), {
    headers: { 'content-type': 'application/json; charset=utf-8', ...privateHeaders() }
  });
}

function adminPage() {
  return new Response(ADMIN_HTML, { headers: { 'content-type': 'text/html; charset=utf-8', ...privateHeaders() } });
}

// ---------- Коды ----------

function batches(env) {
  return String(env.BATCHES || 'A:100:30').split(',').map(s => s.trim()).filter(Boolean).map(s => {
    const [letter, count, days] = s.split(':');
    return { letter: letter.toUpperCase(), count: Math.min(Number(count) || 0, 1024), days: Number(days) || 30 };
  }).filter(b => b.letter.length === 1 && ALPHABET.includes(b.letter) && b.count > 0);
}

function normalizeCode(raw) {
  let s = raw.toUpperCase().replace(/[^A-Z0-9]/g, '');
  if (s.startsWith('SWT')) s = s.slice(3);
  if (s.length !== 12 || [...s].some(c => !ALPHABET.includes(c))) return null;
  return 'SWT-' + s.slice(0, 4) + '-' + s.slice(4, 8) + '-' + s.slice(8);
}

/** Код = буква партии + номер (2 символа) + 9 символов HMAC. */
async function makeCode(codeKey, letter, n) {
  const body = letter + ALPHABET[n >> 5] + ALPHABET[n & 31];
  const mac = await hmac(codeKey, 'code|' + body);
  const s = body + toBase32(mac, 9);
  return 'SWT-' + s.slice(0, 4) + '-' + s.slice(4, 8) + '-' + s.slice(8);
}

async function checkCode(code, secrets, list) {
  const s = code.slice(4).replace(/-/g, '');
  const batch = list.find(b => b.letter === s[0]);
  if (!batch) return null;
  const n = ALPHABET.indexOf(s[1]) * 32 + ALPHABET.indexOf(s[2]);
  if (n >= batch.count) return null;
  const expected = await makeCode(secrets.codeKey, batch.letter, n);
  if (!safeEqual(expected, code)) return null;
  return { code, batch: batch.letter, days: batch.days };
}

// ---------- Криптография ----------

let cachedSecrets = null;

async function deriveSecrets(env) {
  const pem = String(env.LICENSE_PRIVATE_KEY || '').replace(/\s+/g, '');
  if (!pem) throw new Error('LICENSE_PRIVATE_KEY не задан');
  if (cachedSecrets && cachedSecrets.pem === pem) return cachedSecrets;

  const der = Uint8Array.from(atob(pem), c => c.charCodeAt(0));
  const privateKey = await crypto.subtle.importKey('pkcs8', der, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);
  const seed = new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode('SWT1|' + pem)));
  const codeKey = await crypto.subtle.importKey('raw', seed, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const adminToken = toBase32(await hmac(codeKey, 'admin'), 26);
  cachedSecrets = { pem, privateKey, codeKey, adminToken };
  return cachedSecrets;
}

async function hmac(key, text) {
  return new Uint8Array(await crypto.subtle.sign('HMAC', key, enc.encode(text)));
}

/** Тот же формат, что у tools/KeyGen.java issue: "SW-" + base64url(1, план, срок(2 байта), подпись DER). */
async function signLicense(privateKey, device, plan, expiry) {
  const message = enc.encode(`SW1|${device}|${plan}|${expiry}`);
  const raw = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, privateKey, message));
  const der = rawToDer(raw);
  const payload = new Uint8Array(4 + der.length);
  payload.set([1, plan.charCodeAt(0), (expiry >> 8) & 255, expiry & 255]);
  payload.set(der, 4);
  let bin = '';
  payload.forEach(b => { bin += String.fromCharCode(b); });
  return 'SW-' + btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function rawToDer(raw) {
  const part = bytes => {
    let i = 0;
    while (i < bytes.length - 1 && bytes[i] === 0) i++;
    let v = bytes.slice(i);
    if (v[0] & 0x80) v = Uint8Array.from([0, ...v]);
    return [0x02, v.length, ...v];
  };
  const r = part(raw.slice(0, 32));
  const s = part(raw.slice(32));
  return Uint8Array.from([0x30, r.length + s.length, ...r, ...s]);
}

function toBase32(bytes, chars) {
  let out = '', buffer = 0, bits = 0, i = 0;
  while (out.length < chars) {
    if (bits < 5) { buffer = (buffer << 8) | bytes[i++]; bits += 8; }
    bits -= 5;
    out += ALPHABET[(buffer >> bits) & 31];
  }
  return out;
}

function safeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function todayEpochDay() {
  return Math.floor(Date.now() / 86400000);
}

// ---------- HTTP ----------

function cors() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'content-type'
  };
}

function privateHeaders() {
  return {
    'cache-control': 'no-store',
    'x-robots-tag': 'noindex, nofollow',
    'referrer-policy': 'no-referrer',
    'x-frame-options': 'DENY'
  };
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', ...cors() }
  });
}

function notFound() {
  return new Response('Not found', { status: 404 });
}

const ADMIN_HTML = `<!doctype html>
<html lang="ru"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="robots" content="noindex, nofollow">
<meta name="theme-color" content="#0A1124">
<title>SW · Коды</title>
<style>
:root{--bg:#0A1124;--card:#111C38;--line:#1E2C52;--text:#EAF0FF;--muted:#8A9AC4;--blue:#2F7BFF;--orange:#FF8A2B;--green:#2BD49A;--red:#FF5C6C;--ease:cubic-bezier(0.23,1,0.32,1)}
*{box-sizing:border-box}
html{-webkit-tap-highlight-color:transparent;-webkit-text-size-adjust:100%}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.4 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;padding:calc(16px + env(safe-area-inset-top)) 16px calc(24px + env(safe-area-inset-bottom))}
h1{font-size:20px;margin:0 0 14px;display:flex;align-items:center;gap:10px}
h1 b{background:linear-gradient(135deg,var(--blue),#5AA0FF);border-radius:10px;padding:4px 8px;font-size:13px;letter-spacing:.5px}
.stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;margin-bottom:12px}
.stat{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:10px 12px}
.stat div{font-size:22px;font-weight:700;font-variant-numeric:tabular-nums}
.stat span{font-size:12px;color:var(--muted)}
.tabs{display:flex;gap:6px;margin-bottom:10px;overflow-x:auto}
.tab{flex:0 0 auto;border:1px solid var(--line);background:transparent;color:var(--muted);border-radius:999px;padding:7px 14px;font:inherit;font-size:13px;touch-action:manipulation;user-select:none;-webkit-user-select:none;transition:transform 120ms var(--ease),background-color 160ms ease,color 160ms ease}
.tab:active{transform:scale(.97)}
.tab.on{background:var(--blue);border-color:var(--blue);color:#fff}
input{width:100%;font:inherit;font-size:16px;background:var(--card);color:var(--text);border:1px solid var(--line);border-radius:12px;padding:10px 12px;margin-bottom:10px;outline:none}
input:focus{border-color:var(--blue)}
.list{display:flex;flex-direction:column;gap:6px}
.row{display:flex;align-items:center;gap:10px;background:var(--card);border:1px solid var(--line);border-radius:14px;padding:10px 12px}
.n{flex-shrink:0;width:30px;color:var(--muted);font-size:12px;font-variant-numeric:tabular-nums}
.main{flex:1;min-width:0}
.code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:14px;letter-spacing:.3px;overflow-wrap:anywhere}
.meta{font-size:12px;color:var(--muted);margin-top:2px}
.badge{flex-shrink:0;white-space:nowrap;font-size:11px;font-weight:600;border-radius:999px;padding:3px 8px}
.free{background:rgba(47,123,255,.15);color:#7FB0FF}
.used{background:rgba(43,212,154,.14);color:var(--green)}
.exp{background:rgba(255,92,108,.14);color:var(--red)}
.copy{flex-shrink:0;border:0;background:rgba(255,138,43,.14);color:var(--orange);border-radius:10px;padding:8px 10px;font:inherit;font-size:13px;touch-action:manipulation;user-select:none;-webkit-user-select:none;transition:transform 120ms var(--ease)}
.copy:active{transform:scale(.95)}
.empty,.err{color:var(--muted);text-align:center;padding:30px 0}
.toast{position:fixed;left:50%;bottom:calc(20px + env(safe-area-inset-bottom));transform:translate(-50%,16px);opacity:0;background:#1B2A55;border:1px solid var(--line);padding:10px 16px;border-radius:12px;font-size:14px;pointer-events:none;transition:transform 220ms var(--ease),opacity 220ms var(--ease)}
.toast.show{transform:translate(-50%,0);opacity:1}
@media (prefers-reduced-motion:reduce){.toast{transition:opacity 150ms ease;transform:translate(-50%,0)}}
</style></head>
<body>
<h1><b>SW</b> Тестовые коды</h1>
<div class="stats">
  <div class="stat"><div id="s-all">—</div><span>всего</span></div>
  <div class="stat"><div id="s-used">—</div><span>активировано</span></div>
  <div class="stat"><div id="s-free">—</div><span>свободно</span></div>
</div>
<div class="tabs">
  <button class="tab on" data-f="all">Все</button>
  <button class="tab" data-f="free">Свободные</button>
  <button class="tab" data-f="used">Активированные</button>
  <button class="tab" data-f="exp">Истёкшие</button>
</div>
<input id="q" placeholder="Поиск по коду или коду устройства" autocomplete="off" autocapitalize="characters" spellcheck="false">
<div class="list" id="list"><div class="empty">Загрузка…</div></div>
<div class="toast" id="toast"></div>
<script>
let data = null, filter = 'all';
const $ = id => document.getElementById(id);
const fmt = d => { const t = new Date(d * 86400000); return t.toLocaleDateString('ru-RU', { timeZone: 'UTC' }); };
const esc = s => String(s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
function state(c) { return !c.device ? 'free' : (c.expiry < data.today ? 'exp' : 'used'); }
function render() {
  if (!data) return;
  const q = $('q').value.trim().toUpperCase();
  const used = data.codes.filter(c => c.device).length;
  $('s-all').textContent = data.codes.length;
  $('s-used').textContent = used;
  $('s-free').textContent = data.codes.length - used;
  const rows = data.codes.filter(c => {
    const st = state(c);
    if (filter === 'used' && st === 'free') return false;
    if (filter !== 'all' && filter !== 'used' && st !== filter) return false;
    return !q || c.code.includes(q) || (c.device || '').includes(q);
  });
  $('list').innerHTML = rows.length ? rows.map(c => {
    const st = state(c);
    const badge = st === 'free' ? '<span class="badge free">свободен</span>'
      : st === 'exp' ? '<span class="badge exp">истёк</span>' : '<span class="badge used">активирован</span>';
    const meta = c.device
      ? 'Телефон ' + esc(c.device) + ' · с ' + new Date(c.at).toLocaleDateString('ru-RU') + ' до ' + fmt(c.expiry)
      : c.days + ' дней с момента активации';
    return '<div class="row"><div class="n">' + c.batch + c.n + '</div><div class="main"><div class="code">' + esc(c.code) +
      '</div><div class="meta">' + meta + '</div></div>' + badge +
      (c.device ? '' : '<button class="copy" data-code="' + esc(c.code) + '">Копировать</button>') + '</div>';
  }).join('') : '<div class="empty">Ничего не найдено</div>';
}
function toast(t) {
  const el = $('toast'); el.textContent = t; el.classList.add('show');
  clearTimeout(toast.t); toast.t = setTimeout(() => el.classList.remove('show'), 1600);
}
document.querySelector('.tabs').addEventListener('click', e => {
  const b = e.target.closest('.tab'); if (!b) return;
  document.querySelectorAll('.tab').forEach(t => t.classList.toggle('on', t === b));
  filter = b.dataset.f; render();
});
$('list').addEventListener('click', async e => {
  const b = e.target.closest('.copy'); if (!b) return;
  try { await navigator.clipboard.writeText(b.dataset.code); toast('Код скопирован'); }
  catch { prompt('Скопируйте код', b.dataset.code); }
});
$('q').addEventListener('input', render);
async function load() {
  try {
    const r = await fetch(location.pathname + '/api', { cache: 'no-store' });
    if (!r.ok) throw new Error(r.status);
    data = await r.json(); render();
  } catch (e) { $('list').innerHTML = '<div class="err">Не удалось загрузить список</div>'; }
}
load();
document.addEventListener('visibilitychange', () => { if (!document.hidden) load(); });
</script>
</body></html>`;
