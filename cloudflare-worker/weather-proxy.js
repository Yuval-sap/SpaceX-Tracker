// Cloudflare Worker: live surface observations (METAR / SPECI) for the stations next to SpaceX's launch
// sites, for the site's weather boxes (index.html, fetchSiteObservation).
//
// Why: aviationweather.gov has the reports the moment they're issued - including SPECI, the special report
// sent as soon as conditions change (fog forming, visibility dropping) - but it sends no CORS headers, so a
// visitor's browser can't read it. api.weather.gov can be read from a browser but lags up to an hour.
// This worker reads aviationweather.gov and hands the result on with CORS.
//
// Only the four stations the site uses are allowed, and each answer is kept for 2 minutes (Cloudflare's
// cache), so the upstream sees at most one request per 2 minutes however many visitors there are.
//
//   GET /metar?ids=KVBG          ->  aviationweather.gov's JSON for that station (array of reports)
//
// Deploy: Cloudflare dashboard -> Workers & Pages -> Create -> Worker -> paste this file -> Deploy.

const STATIONS = new Set([
  'KVBG',   // Vandenberg SFB airfield - SLC-4E / 6
  'KXMR',   // Cape Canaveral SFS Skid Strip - SLC-40
  'KTTS',   // NASA Shuttle Landing Facility, Kennedy - LC-39A
  'KBRO',   // Brownsville airport - nearest station to Starbase (~30 km)
]);
const CACHE_SECONDS = 120;
const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, OPTIONS' };

export default {
  async fetch(request, env, ctx) {
    if (request.method === 'OPTIONS') return new Response(null, { headers: CORS });
    const url = new URL(request.url);
    if (url.pathname !== '/metar') return new Response('Not found', { status: 404, headers: CORS });
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
      res = new Response(await r.text(), { headers: { 'Content-Type': 'application/json', 'Cache-Control': `public, max-age=${CACHE_SECONDS}` } });
      ctx.waitUntil(cache.put(key, res.clone()));
    }
    const out = new Response(res.body, res);
    for (const [k, v] of Object.entries(CORS)) out.headers.set(k, v);
    out.headers.set('Cache-Control', `public, max-age=${CACHE_SECONDS}`);
    return out;
  },
};

function json(obj, status) {
  return new Response(JSON.stringify(obj), { status, headers: { ...CORS, 'Content-Type': 'application/json' } });
}
