// Cloudflare Worker for spacexfantracker.com - three jobs:
//
// 1. GET /metar?ids=KVBG  - live surface observations (METAR / SPECI) for the stations next to SpaceX's
//    launch sites, for the site's weather boxes (index.html, fetchSiteObservation).
//    aviationweather.gov has the reports the moment they're issued - including SPECI, the special report sent
//    as soon as conditions change (fog forming, visibility dropping) - but it sends no CORS headers, so a
//    visitor's browser can't read it. Only the four stations the site uses are allowed, and each answer is
//    kept for 2 minutes, so the upstream sees at most one request per 2 minutes however many visitors there are.
//
// 2. GET /news  - the headlines for the site's news ticker (index.html, loadNewsTicker), read from the same
//    five RSS feeds the page used to read through rss2json, each with its translation into the site's
//    languages by Gemini. A headline is translated once and kept for good (KV); a new one is sent to Gemini in
//    the background, at most one request every 10 minutes (a few headlines a day - well inside the free tier),
//    and until then goes out untranslated, so the page lets Google Translate handle it meanwhile.
//
// 3. POST /updates  {texts: [...]}  - Gemini translations of Starship "Pre-Launch Updates" log lines (index.html,
//    renderModalUpdates). The GitHub job translates them every 4 hours; a line it hasn't reached yet used to go to
//    Google Translate meanwhile ("FAA launch license acquired" -> "רישיון השיגור של FAA נרכש"). The page now asks
//    here first: a line is translated once, right then, and kept for good (KV); Gemini is asked at most once every
//    20 seconds, and only for lines of the kind the log has (short, a handful at a time).
//
// Needs (Cloudflare dashboard -> this worker -> Settings):
//   - KV namespace binding  NEWS_KV   (Storage & databases -> KV -> create one, then bind it here)
//   - Secret                GEMINI_API_KEY  (same key as the GitHub Actions secret)
// Without them /news still works, just with no translations.

const STATIONS = new Set([
  'KVBG',   // Vandenberg SFB airfield - SLC-4E / 6
  'KXMR',   // Cape Canaveral SFS Skid Strip - SLC-40
  'KTTS',   // NASA Shuttle Landing Facility, Kennedy - LC-39A
  'KBRO',   // Brownsville airport - nearest station to Starbase (~30 km)
]);
const METAR_CACHE_SECONDS = 120;
const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, POST, OPTIONS', 'Access-Control-Allow-Headers': 'Content-Type' };

export default {
  async fetch(request, env, ctx) {
    if (request.method === 'OPTIONS') return new Response(null, { headers: CORS });
    const url = new URL(request.url);
    if (url.pathname === '/metar') return metar(url, ctx);
    if (url.pathname === '/news') return news(url, env, ctx);
    if (url.pathname === '/news/status') return newsStatus(env);
    if (url.pathname === '/updates' && request.method === 'POST') return updates(request, env);
    return new Response('Not found', { status: 404, headers: CORS });
  },
};

function json(obj, status = 200, extra = {}) {
  return new Response(JSON.stringify(obj), { status, headers: { ...CORS, 'Content-Type': 'application/json; charset=utf-8', ...extra } });
}

// ------------------------------------------------------------------ weather
async function metar(url, ctx) {
  const ids = [...new Set((url.searchParams.get('ids') || '').toUpperCase().split(',').filter(s => STATIONS.has(s)))].sort();
  if (!ids.length) return new Response('Unknown station', { status: 400, headers: CORS });
  const upstream = `https://aviationweather.gov/api/data/metar?ids=${ids.join(',')}&format=json`;
  const cache = caches.default, key = new Request(upstream);
  let res = await cache.match(key);
  if (!res) {
    let r;
    try {
      r = await fetch(upstream, { headers: { 'User-Agent': 'spacexfantracker weather proxy' } });
    } catch (e) {
      return json({ error: 'upstream unreachable' }, 502);
    }
    if (!r.ok) return json({ error: 'upstream ' + r.status }, 502);
    res = new Response(await r.text(), { headers: { 'Content-Type': 'application/json', 'Cache-Control': `public, max-age=${METAR_CACHE_SECONDS}` } });
    ctx.waitUntil(cache.put(key, res.clone()));
  }
  const out = new Response(res.body, res);
  for (const [k, v] of Object.entries(CORS)) out.headers.set(k, v);
  out.headers.set('Cache-Control', `public, max-age=${METAR_CACHE_SECONDS}`);
  return out;
}

// ------------------------------------------------------------------ news ticker
const FEEDS = [
  ['https://www.teslarati.com/category/spacex/feed/', 'SpaceX'],
  ['https://www.nasa.gov/news-release/feed/', 'NASA'],
  ['https://spacenews.com/feed/', 'Space News'],
  ['https://spacenews.com/tag/blue-origin/feed/', 'Blue Origin'],
  ['https://www.esa.int/rssfeed/TopNews', 'ESA'],
];
const NEWS_CACHE_SECONDS = 300;
const NEWS_MAX_ITEMS = 24;
// the site's languages besides English (iw is served as he)
const LANGS = { he: 'Hebrew', es: 'Spanish', fr: 'French', de: 'German', ru: 'Russian', zh: 'Simplified Chinese', it: 'Italian', cs: 'Czech', sv: 'Swedish', nl: 'Dutch', da: 'Danish', pt: 'Portuguese', pl: 'Polish', hi: 'Hindi', ar: 'Arabic', tr: 'Turkish' };
// same forms as the site itself uses (translate_mission_purpose.py STARSHIP_TERM / STARLINK_TERM)
const STARSHIP = { he: 'סטארשיפ', ru: 'Старшип', zh: '星舰', hi: 'स्टारशिप', ar: 'ستارشيب' };
const STARLINK = { he: 'סטארלינק', ru: 'Старлинк', zh: '星链', hi: 'स्टारलिंक', ar: 'ستارلينك' };
// every letter of a translation must be English (names like SpaceX / NASA) or the language's own script - the
// light model once put the Thai word "ภาพ" into a Hebrew headline; such a text is dropped and asked for again
const OWN_SCRIPT = { he: /\p{Script=Hebrew}/u, ar: /\p{Script=Arabic}/u, ru: /\p{Script=Cyrillic}/u, zh: /\p{Script=Han}/u, hi: /\p{Script=Devanagari}/u };
// words the light model made up in translations stored before it was dropped
const BAD_WORDS = ['רקטחיים', 'פובלקת'];
function validTranslation(lang, text, source) {
  if (typeof text !== 'string' || !text.trim()) return false;
  if (source && /SpaceX/.test(source) && !/SpaceX/.test(text)) return false;   // "SpaceX" must stay as it is
  const own = OWN_SCRIPT[lang];
  for (const ch of text.match(/\p{L}/gu) || []) if (!/\p{Script=Latin}/u.test(ch) && !(own && own.test(ch))) return false;
  return !own || own.test(text);          // and a non-Latin language must actually be in its script
}
// Small batches, more often: a background task gets only ~30 s after the response, and the full flash model needs
// longer than that for 10 headlines x 16 languages - the call was cut off silently. 3 at a time finishes well inside it.
const GEMINI_MIN_GAP_MS = 3 * 60 * 1000;
const GEMINI_MAX_TITLES = 3;
// the free quota is counted per model and per day: start with models the GitHub translation job doesn't use
// (it starts with gemini-3.8-flash), and move on to the next when one is used up
// Full flash models only. The lite one made up Hebrew words ("רקטחיים", "פובלקת") and mistranslated ("סגנון" for
// "alloy") - worse than Google Translate, which the site uses for a headline with no translation yet. So when every full
// model is out of quota, headlines just wait (Google Translate meanwhile) until one has quota again.
const GEMINI_MODELS = ['gemini-flash-latest', 'gemini-3.7-flash', 'gemini-3.6-flash', 'gemini-3.8-flash'];
const GEMINI_MODELS_RETRY = GEMINI_MODELS;

async function news(url, env, ctx) {
  const cache = caches.default, key = new Request('https://cache.internal/news-v3');
  const hit = await cache.match(key);
  if (hit) {
    const out = new Response(hit.body, hit);
    for (const [k, v] of Object.entries(CORS)) out.headers.set(k, v);
    return out;
  }
  const lists = await Promise.all(FEEDS.map(([u, source]) => readFeed(u, source)));
  const seen = new Set();
  const items = lists.flat()
    .filter(i => i.title && i.link && !seen.has(i.link) && seen.add(i.link))
    .sort((a, b) => (Date.parse(b.pubDate) || 0) - (Date.parse(a.pubDate) || 0))
    .slice(0, NEWS_MAX_ITEMS);
  if (!items.length) return json({ error: 'no news' }, 502);

  // translations already made
  const kv = env.NEWS_KV;
  const missing = [];
  if (kv) {
    await Promise.all(items.map(async it => {
      const h = await hash(it.title);
      let t = await kv.get('t3:' + h, 'json');
      // Not translated again yet by a full model: the translation the site showed before (the 't:' keys) meanwhile,
      // unless it's one known to be wrong (a made-up word) - checked language by language below like any other
      if (!t) {
        const old = await kv.get('t:' + h, 'json');
        if (old && typeof old === 'object') { t = old; it.old = true; }
      }
      if (t && typeof t === 'object') {
        for (const l of Object.keys(t)) if (!validTranslation(l, t[l], it.title) || BAD_WORDS.some(w => String(t[l]).includes(w))) { delete t[l]; it.retry = true; }   // a bad one is asked for again
        if (Object.keys(t).length) it.t = t;
      }
      if (!it.retry && await kv.get('r3:' + h)) it.retry = true;   // rejected before
      // made by the light model (before it was dropped): not shown - Google Translate meanwhile - and translated again
      if (t && !it.old && await kv.get('l3:' + h)) { delete it.t; missing.push(it); }
      else if (!t || it.old || Object.keys(LANGS).some(l => !t[l])) missing.push(it);
    }));
    if (missing.length && env.GEMINI_API_KEY) ctx.waitUntil(translateLater(missing.slice(0, GEMINI_MAX_TITLES), env));
  }
  const body = JSON.stringify({ at: new Date().toISOString(), items });
  const res = new Response(body, { headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': `public, max-age=${NEWS_CACHE_SECONDS}` } });
  // don't hold a response with untranslated headlines for the full 5 minutes when a translation is on its way
  if (!missing.some(it => !it.old)) ctx.waitUntil(cache.put(key, res.clone()));
  else ctx.waitUntil(cache.put(key, new Response(body, { headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'public, max-age=60' } })));
  const out = new Response(res.body, res);
  for (const [k, v] of Object.entries(CORS)) out.headers.set(k, v);
  return out;
}

// what the translation side is doing - whether it's set up, and how the last Gemini request went (never the key)
async function newsStatus(env) {
  const kv = env.NEWS_KV;
  const out = { kvBound: !!kv, geminiKeySet: !!env.GEMINI_API_KEY };
  if (kv) {
    out.nextGeminiAt = new Date(Number(await kv.get('meta:next-gemini-at-v2')) || 0).toISOString();
    out.lastAttempt = await kv.get('meta:last-attempt', 'json');
    out.attempts = await kv.get('meta:attempts', 'json');
  }
  return json(out, 200, { 'Cache-Control': 'no-store' });
}
// (KV's free tier allows 1,000 writes a day: one log entry per model tried, a round of news translation is ~10 writes)
async function noteAttempt(kv, result) {
  try {
    const entry = { at: new Date().toISOString(), ...result };
    await kv.put('meta:last-attempt', JSON.stringify(entry));
    const log = (await kv.get('meta:attempts', 'json')) || [];
    log.unshift(entry);
    await kv.put('meta:attempts', JSON.stringify(log.slice(0, 12)));
  } catch (e) { }
}

async function readFeed(u, source) {
  try {
    const r = await fetch(u, { headers: { 'User-Agent': 'Mozilla/5.0 (spacexfantracker news ticker)' }, cf: { cacheTtl: 300 } });
    if (!r.ok) return [];
    const xml = await r.text();
    return [...xml.matchAll(/<item[\s>][\s\S]*?<\/item>/g)].map(m => {
      const block = m[0], tag = n => { const x = new RegExp(`<${n}[^>]*>([\\s\\S]*?)<\\/${n}>`).exec(block); return x ? clean(x[1]) : ''; };
      return { title: tag('title'), link: tag('link'), pubDate: tag('pubDate'), source };
    });
  } catch (e) {
    return [];
  }
}

function clean(s) {
  return s.replace(/^\s*<!\[CDATA\[/, '').replace(/\]\]>\s*$/, '')
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(+d)).replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&')
    .replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
}

async function hash(s) {
  const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map(b => b.toString(16).padStart(2, '0')).join('');
}

async function translateLater(items, env) {
  const kv = env.NEWS_KV, now = Date.now();
  const last = Number(await kv.get('meta:next-gemini-at-v2')) || 0;
  if (now < last) return;                                       // one request per 10 minutes at most
  await kv.put('meta:next-gemini-at-v2', String(now + GEMINI_MIN_GAP_MS));
  const langs = Object.keys(LANGS);
  const prompt = `You translate short news headlines about spaceflight (SpaceX, NASA, ESA, Blue Origin and the space industry) from English for a news ticker on a rocket-launch tracking website.

Return ONLY valid JSON. Top-level keys must be exactly these language codes: ${langs.join(', ')}
Each value must be a JSON ARRAY of exactly ${items.length} strings - the translated headlines, in the SAME ORDER as the numbered headlines below. Do not skip, merge or reorder any.

Rules:
- Natural headline style in each language, accurate, not word-for-word. No trailing period.
- "launch" is ALWAYS a rocket launch, never a product launch (Hebrew: שיגור). Know the terms: booster (the rocket's first stage), static fire, scrub, rideshare, payload, flight test, crew, spacewalk, deorbit.
- Keep as-is in every language: SpaceX, NASA, ESA, Blue Origin, New Glenn, New Shepard, ULA, Vulcan, Rocket Lab, Electron, Neutron, Falcon 9, Falcon Heavy, Dragon, Crew Dragon, Artemis, Orion, ISS, and mission / satellite codes.
- "Starship": ${langs.map(l => `${l}=${STARSHIP[l] || 'Starship'}`).join(', ')}
- "Starlink": ${langs.map(l => `${l}=${STARLINK[l] || 'Starlink'}`).join(', ')}
- zh must be Simplified Chinese.
- Write each translation ONLY in its own language's alphabet (Hebrew letters for he, Arabic for ar, Cyrillic for ru, Chinese characters for zh, Devanagari for hi, Latin for the rest), plus the English names / codes above kept as they are. Never let a letter or word of any other language or alphabet slip in.
- No labels, markdown or commentary.

Headlines:
${items.map((it, i) => `${i + 1}. ${it.title}`).join('\n')}`;
  const body = JSON.stringify({ contents: [{ parts: [{ text: prompt }] }], generationConfig: { temperature: 0.2, responseMimeType: 'application/json', maxOutputTokens: 8192 } });
  let quotaHit = false;
  // a headline whose translation was rejected before goes to the stronger model first, not the light one again
  const models = env.GEMINI_MODEL ? [env.GEMINI_MODEL] : items.some(it => it.retry) ? GEMINI_MODELS_RETRY : GEMINI_MODELS;
  for (const model of models) {
    let r;
    try {
      r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'x-goog-api-key': env.GEMINI_API_KEY }, body, signal: AbortSignal.timeout(25000) });
    } catch (e) { await noteAttempt(kv, { model, error: (e.name === 'TimeoutError' ? 'timeout: ' : 'network: ') + e.message }); return; }
    const errText = r.ok ? '' : (await r.text()).slice(0, 300);
    if (r.status === 404 || r.status === 400) { await noteAttempt(kv, { model, status: r.status, error: errText }); continue; }   // model not available on this key - next one
    if (r.status === 429) { quotaHit = true; await noteAttempt(kv, { model, status: 429, error: errText }); continue; }   // this model's quota is used up - next one
    // overloaded / server error (503 'high demand', 500...): a temporary problem with this model - next one
    if (r.status >= 500) { await noteAttempt(kv, { model, status: r.status, error: errText }); continue; }
    if (!r.ok) { await noteAttempt(kv, { model, status: r.status, error: errText }); return; }
    let parsed;
    try {
      const data = await r.json();
      const text = (((data.candidates || [])[0] || {}).content || {}).parts?.[0]?.text || '';
      parsed = JSON.parse(text.replace(/^```(?:json)?\s*|\s*```$/g, ''));
    } catch (e) { await noteAttempt(kv, { model, error: 'unreadable answer: ' + e.message }); return; }
    await Promise.all(items.map(async (it, i) => {
      const h = await hash(it.title), k = 't3:' + h, t = Object.assign({}, it.old ? {} : (it.t || {}));
      let rejected = false;
      for (const l of langs) {
        const arr = parsed[l];
        const v = Array.isArray(arr) && arr.length === items.length && typeof arr[i] === 'string' ? arr[i].trim().replace(/[.。]$/, '') : '';
        if (validTranslation(l, v, it.title)) t[l] = v;
        else if (v) rejected = true;
      }
      if (Object.keys(t).length) await kv.put(k, JSON.stringify(t));
      await kv.delete('l3:' + h);
      if (rejected || it.retry) await kv.put('r3:' + h, '1');
    }));
    await caches.default.delete(new Request('https://cache.internal/news-v3'));   // next request picks them up
    await noteAttempt(kv, { model, ok: true, titles: items.length });
    return;
  }
  // no model had quota left: try again in an hour
  if (quotaHit) await kv.put('meta:next-gemini-at-v2', String(now + 60 * 60 * 1000));
}

// ------------------------------------------------------------------ pre-launch updates
const UPDATE_MAX_TEXTS = 20;
const UPDATE_MAX_CHARS = 300;
const UPDATE_GEMINI_GAP_MS = 20 * 1000;

async function updates(request, env) {
  let texts;
  try { texts = (await request.json()).texts; } catch (e) { return json({ error: 'bad request' }, 400); }
  if (!Array.isArray(texts) || !texts.length || texts.length > UPDATE_MAX_TEXTS
    || texts.some(t => typeof t !== 'string' || !t.trim() || t.length > UPDATE_MAX_CHARS)) return json({ error: 'bad request' }, 400);
  const kv = env.NEWS_KV;
  if (!kv) return json({ translations: texts.map(() => null) });
  const langs = Object.keys(LANGS);
  const keys = await Promise.all(texts.map(async t => 'u:' + await hash(t.trim())));
  const out = await Promise.all(keys.map(k => kv.get(k, 'json')));
  const missing = [];
  out.forEach((t, i) => { if (!t || langs.some(l => !t[l])) missing.push(i); });
  if (missing.length && env.GEMINI_API_KEY) {
    const got = await translateUpdates(missing.map(i => texts[i].trim()), env);
    if (got) await Promise.all(missing.map(async (i, j) => {
      const t = Object.assign({}, out[i] || {}, got[j]);
      if (Object.keys(t).length) { out[i] = t; await kv.put(keys[i], JSON.stringify(t)); }
    }));
  }
  return json({ translations: out.map(t => t || null) }, 200, { 'Cache-Control': 'no-store' });
}

async function translateUpdates(texts, env) {
  const kv = env.NEWS_KV, now = Date.now();
  if (now < (Number(await kv.get('meta:upd-next-gemini-at')) || 0)) return null;
  await kv.put('meta:upd-next-gemini-at', String(now + UPDATE_GEMINI_GAP_MS));
  const langs = Object.keys(LANGS);
  // same rules as the GitHub job's UPDATE_PROMPT (translate_mission_purpose.py)
  const prompt = `You translate short official status-update log entries for a SpaceX rocket launch, from English into several languages, for a chronological update feed on a launch-tracking website.

Return ONLY valid JSON. Top-level keys must be exactly these language codes: ${langs.join(', ')}
Each language value must be a JSON ARRAY of exactly ${texts.length} strings - the translations, in the SAME ORDER as the numbered entries below. Do not skip, merge, or reorder any entry.

Rules:
- Natural, accurate translation - not word-for-word. These are terse launch-operations log entries, not full sentences - expand abbreviations naturally in the translation: NET = No Earlier Than, TBC = To Be Confirmed, TBD = To Be Determined, "GO for launch" means the launch has been approved/authorized to proceed.
- CRITICAL: "launch" / "launched" / "launch window" here ALWAYS means a ROCKET launch (SpaceX sending a vehicle to space) - NEVER a product or service launch. Use each language's own correct term for a rocket launch specifically. Specifically: he=שיגור
- Regulatory wording: a launch license is issued / granted / received (Hebrew: התקבל רישיון שיגור), never "bought" or "acquired" in the purchase sense.
- zh must be Simplified Chinese.
- Keep as-is, untranslated, in every language: SpaceX, Falcon, Falcon 9, Falcon Heavy, Starship, Dragon, FAA, dates, times, and mission/satellite codes such as USSF-153. Keep month names in English exactly as written (the site puts them in each language's own date format itself).
- Write each translation ONLY in its own language's alphabet (Hebrew letters for he, Arabic for ar, Cyrillic for ru, Chinese characters for zh, Devanagari for hi, Latin for the rest), plus the English names / codes / months above kept as they are.
- Do not add labels, markdown, or commentary.

Entries:
${texts.map((t, i) => `${i + 1}. ${t}`).join('\n')}`;
  const body = JSON.stringify({ contents: [{ parts: [{ text: prompt }] }], generationConfig: { temperature: 0.2, responseMimeType: 'application/json', maxOutputTokens: 8192 } });
  // the stronger models first: a visitor is waiting for this one, and there are only a few lines a day
  for (const model of env.GEMINI_MODEL ? [env.GEMINI_MODEL] : GEMINI_MODELS_RETRY) {
    let r;
    try {
      r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'x-goog-api-key': env.GEMINI_API_KEY }, body });
    } catch (e) { await noteAttempt(kv, { job: 'updates', model, error: 'network: ' + e.message }); return null; }
    if (!r.ok) {
      await noteAttempt(kv, { job: 'updates', model, status: r.status, error: (await r.text()).slice(0, 300) });
      if (r.status === 429 || r.status === 404 || r.status === 400 || r.status >= 500) continue;   // next model
      return null;
    }
    let parsed;
    try {
      const data = await r.json();
      const text = (((data.candidates || [])[0] || {}).content || {}).parts?.[0]?.text || '';
      parsed = JSON.parse(text.replace(/^```(?:json)?\s*|\s*```$/g, ''));
    } catch (e) { await noteAttempt(kv, { job: 'updates', model, error: 'unreadable answer: ' + e.message }); return null; }
    await noteAttempt(kv, { job: 'updates', model, ok: true, texts: texts.length });
    return texts.map((_, i) => {
      const t = {};
      for (const l of langs) {
        const arr = parsed[l];
        const v = Array.isArray(arr) && arr.length === texts.length && typeof arr[i] === 'string' ? arr[i].trim() : '';
        if (validTranslation(l, v)) t[l] = v;
      }
      return t;
    });
  }
  return null;
}
