#!/usr/bin/env python3
"""
Generates static, crawler-readable, independently-indexable pages for every current SpaceX
Launch Tracker mission - one real HTML file per mission (m/<id>.html) with that mission's own
title/description/image baked in as static text, plus a sitemap.xml listing all of them.

Two jobs, both needing the same per-mission static page:

1. Link-preview unfurling. index.html is a single static file with no server behind it
   (GitHub Pages). A link like index.html?mission=<id> opens the right mission for a real
   visitor (index.html's own JS reads the query string and opens that mission's modal), but
   link-preview bots (WhatsApp/Telegram/...) don't run JavaScript - they just fetch the raw
   HTML and read whatever <meta property="og:..."> tags are already in the document, which for
   every mission would be the exact same site-wide tags without a page that's different per
   mission.

2. SEO - each of these pages is meant to be its own independently-rankable page, not just a
   bridge back to the homepage. Earlier versions of this page did `location.replace(...)` to
   immediately forward every visitor (bot included) into the main app - correct for humans, but
   it meant Google's crawler treated the page as a redirect and indexed the target instead of
   this page's own content, so none of these pages could ever rank on their own. There is no
   redirect here anymore: a visitor who lands on one of these (from a search result or a shared
   link) sees a real, readable page about that specific mission, with an explicit "open the live
   tracker" button instead of being auto-forwarded.

Usage:
    python generate_mission_cards.py

Run this locally before every push (or wire it into whatever your own deploy process is -
a GitHub Action on a schedule is a natural next step once you're happy with the output,
but this script itself doesn't touch git or GitHub at all: it only writes files under m/
in the current directory, plus sitemap.xml at the repo root).

No dependencies beyond the Python standard library - deliberately, so there's nothing to
pip install before this will run.
"""

import html
import json
import re
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

# Must match window.location.origin + pathname that buildMissionShareUrl() in index.html
# builds share links against - keep these in sync if the site ever moves.
SITE_BASE_URL = "https://spacexfantracker.com"

OUTPUT_DIR = Path(__file__).parent / "m"

# Mirrors FALLBACK_IMAGES / mapLaunchToSchema's category detection in index.html exactly,
# so a shared mission's preview image matches whatever image that mission actually shows
# once you're inside the app - see index.html's own FALLBACK_IMAGES for the source of truth
# if these ever drift apart.
FALLBACK_IMAGES = {
    "starship": "https://pbs.twimg.com/media/HOGCJc7WoAAMPvM?format=jpg&name=4096x4096",
    "starlink": "https://everydayastronaut.com/wp-content/uploads/2019/11/starlink2.jpg",
    "falcon": "https://wp.technologyreview.com/wp-content/uploads/2024/07/AP24191572534430.jpg?w=3000",
    "falcon_heavy": "https://cdn.mos.cms.futurecdn.net/fnfyE7cDwV9JWCopNK8Ycb.jpg",
    "dragon": "https://s.w-x.co/nasas-spacex-crew-12-rocket_0.webp?format=auto&optimize=medium&width=1600&quality=60",
}

LL2_UPCOMING_URL = "https://ll.thespacedevs.com/2.2.0/launch/upcoming/?lsp__id=121&limit=15&mode=detailed"
LL2_PREVIOUS_URL = "https://ll.thespacedevs.com/2.2.0/launch/previous/?lsp__id=121&limit=50&mode=detailed"


def detect_category(name: str, vehicle: str) -> str:
    name_lower = (name or "").lower()
    vehicle_lower = (vehicle or "").lower()
    if "starship" in vehicle_lower or "starship flight" in name_lower or "ift-" in name_lower:
        return "starship"
    if "heavy" in vehicle_lower or "falcon heavy" in name_lower:
        return "falcon_heavy"
    if "dragon" in vehicle_lower or "dragon" in name_lower or "crew" in name_lower or "polaris" in name_lower:
        return "dragon"
    if "starlink" in name_lower or "starshield" in name_lower or "starfall" in name_lower:
        return "starlink"
    return "falcon"


def fetch_launches(url: str, retries: int = 4) -> list:
    # ll.thespacedevs.com throttles requests that come in too close together (HTTP 429) -
    # this script makes two calls back-to-back, so a single throttle used to silently drop
    # every mission that call would have returned. Retry with backoff instead of giving up.
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "spacexfantracker-card-generator/1.0"})
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            return data.get("results", []) or []
        except Exception as e:
            wait = 20 * (attempt + 1)
            print(f"Warning: failed to fetch {url}: {e}", file=sys.stderr)
            if attempt < retries - 1:
                print(f"Retrying in {wait}s...", file=sys.stderr)
                time.sleep(wait)
    return []


def format_date(net: str) -> str:
    if not net:
        return "TBD"
    try:
        dt = datetime.fromisoformat(net.replace("Z", "+00:00")).astimezone(timezone.utc)
        return dt.strftime("%B %-d, %Y") if sys.platform != "win32" else dt.strftime("%B %#d, %Y")
    except Exception:
        return "TBD"


PAGE_TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title_escaped}</title>
<meta name="description" content="{description_escaped}">
<link rel="canonical" href="{url_escaped}">

<meta property="og:type" content="website">
<meta property="og:title" content="{title_escaped}">
<meta property="og:description" content="{description_escaped}">
<meta property="og:image" content="{image_escaped}">
<meta property="og:url" content="{url_escaped}">

<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="{title_escaped}">
<meta name="twitter:description" content="{description_escaped}">
<meta name="twitter:image" content="{image_escaped}">

<script type="application/ld+json">{jsonld}</script>

<style>
:root{{color-scheme:dark}}
*{{box-sizing:border-box}}
body{{margin:0;padding:0;background:#0a0e0c;color:#e4e4e7;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;line-height:1.6}}
.wrap{{max-width:640px;margin:0 auto;padding:2.5rem 1.25rem 4rem}}
.brand{{display:flex;align-items:center;gap:.5rem;font-size:.8rem;font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:#71717a;text-decoration:none;margin-bottom:2rem}}
.brand:hover{{color:#34d399}}
.photo{{width:100%;aspect-ratio:16/9;object-fit:cover;border-radius:1rem;border:1px solid #27272a;margin-bottom:1.5rem}}
h1{{font-size:1.75rem;font-weight:700;margin:0 0 .5rem;letter-spacing:-0.01em}}
.vehicle{{color:#34d399;font-weight:600;font-size:.95rem;margin-bottom:1.5rem}}
.stats{{display:grid;grid-template-columns:1fr 1fr;gap:.75rem;background:#111815;border:1px solid #27272a;border-radius:1rem;padding:1rem 1.25rem;margin-bottom:1.5rem}}
.stat-label{{font-size:.7rem;text-transform:uppercase;letter-spacing:.06em;color:#71717a;font-weight:700;margin-bottom:.15rem}}
.stat-value{{font-size:.9rem;font-weight:600;color:#e4e4e7}}
p.lede{{color:#a1a1aa;font-size:.95rem}}
.cta{{display:block;text-align:center;background:#10b981;color:#022c22;font-weight:700;text-decoration:none;padding:.9rem 1.5rem;border-radius:.85rem;margin:2rem 0 1rem;font-size:1rem}}
.cta:hover{{background:#34d399}}
.back{{display:inline-block;color:#71717a;font-size:.85rem;text-decoration:none}}
.back:hover{{color:#e4e4e7}}
</style>
</head>
<body>
<div class="wrap">
<a class="brand" href="{home_url_escaped}">&larr; SpaceX Fan Tracker</a>
<img class="photo" src="{image_escaped}" alt="{title_escaped}">
<h1>{name_escaped}</h1>
<div class="vehicle">{vehicle_escaped}</div>
<div class="stats">
<div><div class="stat-label">Launch Site</div><div class="stat-value">{site_escaped}</div></div>
<div><div class="stat-label">Date</div><div class="stat-value">{date_escaped}</div></div>
</div>
<p class="lede">Live countdown, launch window, booster and recovery details, and video coverage for this SpaceX mission are available in the full tracker.</p>
<a class="cta" href="{app_url_escaped}">Open in Live Tracker &rarr;</a>
<a class="back" href="{home_url_escaped}">&larr; Back to all missions</a>
</div>
</body>
</html>
"""


def build_page(mission_id: str, name: str, vehicle: str, site: str, net: str) -> str:
    category = detect_category(name, vehicle)
    image = FALLBACK_IMAGES.get(category, FALLBACK_IMAGES["falcon"])
    date_str = format_date(net)
    title = f"SpaceX • {name}"
    description = f"{vehicle} • {site} • {date_str}"
    app_url = f"{SITE_BASE_URL}/?mission={quote(mission_id)}"
    card_url = f"{SITE_BASE_URL}/m/{quote(mission_id)}.html"

    jsonld_obj = {
        "@context": "https://schema.org",
        "@type": "Event",
        "name": name,
        "description": description,
        "startDate": net or None,
        "eventAttendanceMode": "https://schema.org/OnlineEventAttendanceMode",
        "location": {
            "@type": "Place",
            "name": site,
            "address": {"@type": "PostalAddress", "addressCountry": "US"},
        },
        "organizer": {"@type": "Organization", "name": "SpaceX", "url": "https://www.spacex.com"},
        "image": image,
        "url": card_url,
    }
    # startDate: None serializes as JSON null, which is valid JSON-LD (means "unknown") rather
    # than omitting the key entirely - simpler than conditionally building the dict above.
    jsonld = json.dumps(jsonld_obj, ensure_ascii=False)

    return PAGE_TEMPLATE.format(
        title_escaped=html.escape(title),
        description_escaped=html.escape(description),
        image_escaped=html.escape(image),
        url_escaped=html.escape(card_url),
        app_url_escaped=html.escape(app_url),
        home_url_escaped=html.escape(SITE_BASE_URL + "/"),
        name_escaped=html.escape(name),
        vehicle_escaped=html.escape(vehicle),
        site_escaped=html.escape(site),
        date_escaped=html.escape(date_str),
        jsonld=jsonld,
    )


def build_sitemap(mission_ids: list) -> str:
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    urls = [f"  <url><loc>{html.escape(SITE_BASE_URL)}/</loc><changefreq>hourly</changefreq><priority>1.0</priority></url>"]
    for safe_id in mission_ids:
        loc = html.escape(f"{SITE_BASE_URL}/m/{safe_id}.html")
        urls.append(f"  <url><loc>{loc}</loc><lastmod>{today}</lastmod><changefreq>daily</changefreq><priority>0.7</priority></url>")
    body = "\n".join(urls)
    return f'<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n{body}\n</urlset>\n'


def main():
    OUTPUT_DIR.mkdir(exist_ok=True)

    upcoming = fetch_launches(LL2_UPCOMING_URL)
    time.sleep(3)
    previous = fetch_launches(LL2_PREVIOUS_URL)

    # If either half of the fetch still failed after retries, don't ship a half-complete
    # set of cards - better to leave the existing (older but complete) m/ folder untouched
    # and let the next scheduled run try again.
    if not upcoming or not previous:
        print("Failed to fetch one or both launch lists - aborting without touching existing files.", file=sys.stderr)
        sys.exit(1)

    launches = upcoming + previous

    written = 0
    seen_ids = set()
    written_safe_ids = []
    for launch in launches:
        mission_id = launch.get("id")
        if not mission_id or mission_id in seen_ids:
            continue
        seen_ids.add(mission_id)

        name = launch.get("name") or "SpaceX Launch"
        rocket = launch.get("rocket") or {}
        config = launch.get("launch_service_provider") or {}
        vehicle = (
            rocket.get("configuration", {}).get("full_name")
            if isinstance(rocket.get("configuration"), dict)
            else None
        ) or "Falcon 9"
        pad = launch.get("pad") or {}
        site = pad.get("location", {}).get("name") if isinstance(pad.get("location"), dict) else None
        site = site or pad.get("name") or "TBD"
        net = launch.get("net")

        # Only the id needs to be filesystem/URL-safe here; ids from this API are plain UUIDs.
        safe_id = re.sub(r"[^a-zA-Z0-9._-]", "_", mission_id)
        out_path = OUTPUT_DIR / f"{safe_id}.html"
        out_path.write_text(build_page(mission_id, name, vehicle, site, net), encoding="utf-8")
        written += 1
        written_safe_ids.append(safe_id)

    print(f"Wrote {written} mission preview pages to {OUTPUT_DIR}/")

    # Remove any leftover page from a previous run whose mission has since aged out of both the
    # upcoming and previous LL2 windows - otherwise old pages (some still in the old redirect
    # format from before this script was rewritten) linger on disk and stay reachable/indexable
    # forever even though they no longer appear in the sitemap. Only ever touches files inside
    # OUTPUT_DIR (m/) that match the mission-id filename pattern this script itself writes.
    keep = {f"{safe_id}.html" for safe_id in written_safe_ids}
    pruned = 0
    for existing in OUTPUT_DIR.glob("*.html"):
        if existing.name not in keep:
            existing.unlink()
            pruned += 1
    if pruned:
        print(f"Pruned {pruned} stale mission page(s) no longer in the LL2 launch window.")

    # sitemap.xml at the repo root (same level as index.html) - not inside m/ - so it covers the
    # homepage too and matches the conventional /sitemap.xml location search engines expect.
    sitemap_path = OUTPUT_DIR.parent / "sitemap.xml"
    sitemap_path.write_text(build_sitemap(written_safe_ids), encoding="utf-8")
    print(f"Wrote sitemap.xml with {len(written_safe_ids) + 1} URLs to {sitemap_path}")


if __name__ == "__main__":
    main()
