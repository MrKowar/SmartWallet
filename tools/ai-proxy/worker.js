// SmartWallet AI-прокси на Cloudflare Workers.
// Ключ Gemini хранится только здесь (секрет GEMINI_API_KEY), в APK его нет.
// Лимит запросов считается по коду устройства; с действующим ключом SmartWallet лимит выше.
//
// Переменные окружения (wrangler.toml / панель Cloudflare):
//   GEMINI_API_KEY        — секрет, ключ Google AI Studio (бесплатный тариф)
//   OPENROUTER_API_KEY    — секрет, необязательный запасной провайдер
//   LICENSE_PUBLIC_KEY    — открытый ключ лицензий (LicenseVerifier.PUBLIC_KEY_BASE64)
//   GEMINI_MODEL          — модель Gemini
//   DAILY_LIMIT_LICENSED  — запросов в сутки с ключом (по умолчанию 100)
//   DAILY_LIMIT_TRIAL     — запросов в сутки в пробном периоде (по умолчанию 15)
//   RATE_KV               — KV namespace для счётчиков (без него лимитов нет)

const DEVICE_CODE = /^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$/;
const MAX_PROMPT = 4000;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== 'POST' || url.pathname !== '/ai') {
      return json({ error: 'Not found' }, 404);
    }

    let body;
    try { body = await request.json(); } catch { return json({ error: 'Неверный запрос' }, 400); }

    const deviceCode = String(body.deviceCode || '');
    const prompt = String(body.prompt || '');
    const system = String(body.system || '').slice(0, 1000);
    if (!DEVICE_CODE.test(deviceCode)) return json({ error: 'Неверный код устройства' }, 400);
    if (!prompt || prompt.length > MAX_PROMPT) return json({ error: 'Слишком длинный запрос' }, 400);

    const licensed = body.license ? await verifyLicense(String(body.license), deviceCode, env.LICENSE_PUBLIC_KEY) : false;
    const limit = Number(licensed ? env.DAILY_LIMIT_LICENSED || 100 : env.DAILY_LIMIT_TRIAL || 15);

    if (env.RATE_KV) {
      const key = `${new Date().toISOString().slice(0, 10)}:${deviceCode}`;
      const used = Number(await env.RATE_KV.get(key)) || 0;
      if (used >= limit) {
        return json({ error: licensed ? 'Лимит запросов к ИИ на сегодня исчерпан' : 'Лимит пробного периода на сегодня исчерпан' }, 429);
      }
      await env.RATE_KV.put(key, String(used + 1), { expirationTtl: 2 * 86400 });
    }

    const errors = [];
    if (env.GEMINI_API_KEY) {
      try { return json({ answer: await askGemini(env, system, prompt), provider: 'gemini' }); }
      catch (e) { errors.push('Gemini: ' + e.message); }
    }
    if (env.OPENROUTER_API_KEY) {
      try { return json({ answer: await askOpenRouter(env, system, prompt), provider: 'openrouter' }); }
      catch (e) { errors.push('OpenRouter: ' + e.message); }
    }
    return json({ error: errors.join('; ') || 'ИИ-провайдеры не настроены' }, 502);
  }
};

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8' }
  });
}

async function askGemini(env, system, prompt) {
  const model = env.GEMINI_MODEL || 'gemini-3.8-flash';
  const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'x-goog-api-key': env.GEMINI_API_KEY },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: system }] },
      contents: [{ role: 'user', parts: [{ text: prompt }] }],
      generationConfig: { temperature: 0.4, maxOutputTokens: 700 }
    })
  });
  if (!res.ok) throw new Error('HTTP ' + res.status);
  const data = await res.json();
  const parts = data.candidates?.[0]?.content?.parts || [];
  const text = parts.filter(p => !p.thought && p.text).map(p => p.text).join('\n').trim();
  if (!text) throw new Error('пустой ответ');
  return text;
}

async function askOpenRouter(env, system, prompt) {
  const res = await fetch('https://openrouter.ai/api/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + env.OPENROUTER_API_KEY },
    body: JSON.stringify({
      model: env.OPENROUTER_MODEL || 'google/gemma-4-31b-it:free',
      temperature: 0.4,
      max_tokens: 700,
      messages: [{ role: 'system', content: system }, { role: 'user', content: prompt }]
    })
  });
  if (!res.ok) throw new Error('HTTP ' + res.status);
  const data = await res.json();
  const text = (data.choices?.[0]?.message?.content || '').trim();
  if (!text) throw new Error('пустой ответ');
  return text;
}

// ===== Проверка ключа SmartWallet (тот же формат, что LicenseVerifier.kt) =====

function base64ToBytes(b64) {
  const s = atob(b64.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((b64.length + 3) % 4));
  return Uint8Array.from(s, c => c.charCodeAt(0));
}

// WebCrypto ждёт подпись ECDSA как r||s по 32 байта, а Java выдаёт DER
function derToRaw(der) {
  let i = 2;
  if (der[1] & 0x80) i += der[1] & 0x7f;
  const readInt = () => {
    if (der[i++] !== 0x02) throw new Error('bad DER');
    const len = der[i++];
    let v = der.slice(i, i + len);
    i += len;
    while (v.length > 32 && v[0] === 0) v = v.slice(1);
    const out = new Uint8Array(32);
    out.set(v, 32 - v.length);
    return out;
  };
  const raw = new Uint8Array(64);
  raw.set(readInt(), 0);
  raw.set(readInt(), 32);
  return raw;
}

async function verifyLicense(key, deviceCode, publicKeyB64) {
  try {
    if (!publicKeyB64) return false;
    const cleaned = key.replace(/\s+/g, '');
    if (!/^SW-/i.test(cleaned)) return false;
    const bytes = base64ToBytes(cleaned.slice(3));
    if (bytes.length < 12 || bytes[0] !== 1) return false;
    const plan = String.fromCharCode(bytes[1]);
    const expiry = (bytes[2] << 8) | bytes[3];
    const todayEpochDay = Math.floor(Date.now() / 86400000);
    if (expiry !== 0xffff && todayEpochDay > expiry) return false;

    const publicKey = await crypto.subtle.importKey(
      'spki', base64ToBytes(publicKeyB64), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']
    );
    const message = new TextEncoder().encode(`SW1|${deviceCode}|${plan}|${expiry}`);
    return await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, publicKey, derToRaw(bytes.slice(4)), message);
  } catch {
    return false;
  }
}
