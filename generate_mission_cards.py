#!/usr/bin/env python3
"""
Generates static, crawler-readable preview pages for every current SpaceX Launch Tracker
mission, so a shared mission link unfurls as a real card (title + image) on WhatsApp,
Telegram, iMessage, Discord, etc. instead of the site's generic homepage preview.

Why this exists at all: index.html is a single static file with no server behind it
(GitHub Pages). A link like index.html?mission=<id> opens the right mission for a real
visitor (index.html's own JS reads the query string and opens that mission's modal), but
link-preview bots (WhatsApp/Telegram/...) don't run JavaScript - they just fetch the raw
HTML and read whatever <meta property="og:..."> tags are already in the document, which for
every mission would be the exact same site-wide tags. There's no way to make those tags
different per mission without a page that's different per mission - hence this script,
which writes one small real HTML file per mission (m/<id>.html) with that mission's own
title/description/image baked in as static text. A real visitor who lands on one of these
still ends up in the full interactive app - see the JS redirect below.

Usage:
    python generate_mission_cards.py

Run this locally before every push (or wire it into whatever your own deploy process is -
a GitHub Action on a schedule is a natural next step once you're happy with the output,
but this script itself doesn't touch git or GitHub at all: it only writes files under m/
in the current directory).

No dependencies beyond the Python standard library - deliberately, so there's nothing to
pip install before this will run.
"""

import html
import json
import re
import sys
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


def fetch_launches(url: str) -> list:
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "spacexfantracker-card-generator/1.0"})
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        return data.get("results", []) or []
    except Exception as e:
        print(f"Warning: failed to fetch {url}: {e}", file=sys.stderr)
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
<title>{title_escaped}</title>
<meta name="description" content="{description_escaped}">

<meta property="og:type" content="website">
<meta property="og:title" content="{title_escaped}">
<meta property="og:description" content="{description_escaped}">
<meta property="og:image" content="{image_escaped}">
<meta property="og:url" content="{url_escaped}">

<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="{title_escaped}">
<meta name="twitter:description" content="{description_escaped}">
<meta name="twitter:image" content="{image_escaped}">

<script>location.replace({app_url_json});</script>
</head>
<body>
<p>Redirecting to SpaceX Launch Tracker&hellip; <a href="{app_url_escaped}">Click here</a> if you are not redirected automatically.</p>
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

    return PAGE_TEMPLATE.format(
        title_escaped=html.escape(title),
        description_escaped=html.escape(description),
        image_escaped=html.escape(image),
        url_escaped=html.escape(card_url),
        app_url_escaped=html.escape(app_url),
        app_url_json=json.dumps(app_url),
    )


def main():
    OUTPUT_DIR.mkdir(exist_ok=True)

    launches = fetch_launches(LL2_UPCOMING_URL) + fetch_launches(LL2_PREVIOUS_URL)
    if not launches:
        print("No launches fetched - aborting without touching existing files.", file=sys.stderr)
        sys.exit(1)

    written = 0
    seen_ids = set()
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

    print(f"Wrote {written} mission preview pages to {OUTPUT_DIR}/")


if __name__ == "__main__":
    main()
