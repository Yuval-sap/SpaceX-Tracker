// Cloudflare Worker for spacexfantracker.com - two jobs:
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
const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, OPTIONS' };

export default {
  async fetch(request, env, ctx) {
    if (request.method === 'OPTIONS') return new Response(null, { headers: CORS });
    const url = new URL(request.url);
    if (url.pathname === '/metar') return metar(url, ctx);
    if (url.pathname === '/news') return news(url, env, ctx);
    if (url.pathname === '/news/status') return newsStatus(env);
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
const GEMINI_MIN_GAP_MS = 10 * 60 * 1000;
const GEMINI_MAX_TITLES = 10;
// the free quota is counted per model and per day: start with models the GitHub translation job doesn't use
// (it starts with gemini-3.8-flash), and move on to the next when one is used up
const GEMINI_MODELS = ['gemini-flash-lite-latest', 'gemini-3.7-flash', 'gemini-flash-latest', 'gemini-3.6-flash', 'gemini-3.8-flash'];

async function news(url, env, ctx) {
  const cache = caches.default, key = new Request('https://cache.internal/news-v1');
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
      const t = await kv.get('t:' + await hash(it.title), 'json');
      if (t && typeof t === 'object') it.t = t;
      if (!t || Object.keys(LANGS).some(l => !t[l])) missing.push(it);
    }));
    if (missing.length && env.GEMINI_API_KEY) ctx.waitUntil(translateLater(missing.slice(0, GEMINI_MAX_TITLES), env));
  }
  const body = JSON.stringify({ at: new Date().toISOString(), items });
  const res = new Response(body, { headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': `public, max-age=${NEWS_CACHE_SECONDS}` } });
  // don't hold a response with untranslated headlines for the full 5 minutes when a translation is on its way
  if (!missing.length) ctx.waitUntil(cache.put(key, res.clone()));
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
  }
  return json(out, 200, { 'Cache-Control': 'no-store' });
}
async function noteAttempt(kv, result) {
  try { await kv.put('meta:last-attempt', JSON.stringify({ at: new Date().toISOString(), ...result })); } catch (e) { }
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
- No labels, markdown or commentary.

Headlines:
${items.map((it, i) => `${i + 1}. ${it.title}`).join('\n')}`;
  const body = JSON.stringify({ contents: [{ parts: [{ text: prompt }] }], generationConfig: { temperature: 0.2, responseMimeType: 'application/json', maxOutputTokens: 8192 } });
  let quotaHit = false;
  for (const model of (env.GEMINI_MODEL ? [env.GEMINI_MODEL] : GEMINI_MODELS)) {
    let r;
    try {
      r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'x-goog-api-key': env.GEMINI_API_KEY }, body });
    } catch (e) { await noteAttempt(kv, { model, error: 'network: ' + e.message }); return; }
    const errText = r.ok ? '' : (await r.text()).slice(0, 300);
    if (r.status === 404 || r.status === 400) { await noteAttempt(kv, { model, status: r.status, error: errText }); continue; }   // model not available on this key - next one
    if (r.status === 429) { quotaHit = true; await noteAttempt(kv, { model, status: 429, error: errText }); continue; }   // this model's quota is used up - next one
    if (!r.ok) { await noteAttempt(kv, { model, status: r.status, error: errText }); return; }
    let parsed;
    try {
      const data = await r.json();
      const text = (((data.candidates || [])[0] || {}).content || {}).parts?.[0]?.text || '';
      parsed = JSON.parse(text.replace(/^```(?:json)?\s*|\s*```$/g, ''));
    } catch (e) { await noteAttempt(kv, { model, error: 'unreadable answer: ' + e.message }); return; }
    await Promise.all(items.map(async (it, i) => {
      const k = 't:' + await hash(it.title), t = Object.assign({}, it.t || {});
      for (const l of langs) {
        const arr = parsed[l];
        if (Array.isArray(arr) && arr.length === items.length && typeof arr[i] === 'string' && arr[i].trim()) t[l] = arr[i].trim().replace(/[.。]$/, '');
      }
      if (Object.keys(t).length) await kv.put(k, JSON.stringify(t));
    }));
    await caches.default.delete(new Request('https://cache.internal/news-v1'));   // next request picks them up
    await noteAttempt(kv, { model, ok: true, titles: items.length });
    return;
  }
  // no model had quota left: try again in an hour
  if (quotaHit) await kv.put('meta:next-gemini-at-v2', String(now + 60 * 60 * 1000));
}
