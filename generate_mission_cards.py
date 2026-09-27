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

2. Human click-through. The shared URL stays on this static page so preview bots still see
   the per-mission image/title. A real person clicking the link is forwarded immediately
   (JS location.replace, skipped for known preview-bot user-agents) to
   index.html?mission=<id>, which opens that mission's modal. The visible "Open in Live
   Tracker" button stays as a no-JS fallback. We used to skip that forward so Google would
   index these pages as standalone articles; that extra landing step is no longer wanted.

Usage:
    python generate_mission_cards.py

Run this locally before every push (or wire it into whatever your own deploy process is -
a GitHub Action on a schedule is a natural next step once you're happy with the output,
but this script itself doesn't touch git or GitHub at all: it only writes files under m/
in the current directory, plus sitemap.xml at the repo root).

No dependencies beyond the Python standard library - deliberately, so there's nothing to
pip install before this will run.
"""

import hashlib
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
# Hebrew copies of the same pages (m/he/<id>.html): a link shared while the site is in Hebrew points here
# (buildMissionShareUrl in index.html), so the link preview - which preview bots read straight from the page,
# without running any script - is in Hebrew too
OUTPUT_DIR_HE = OUTPUT_DIR / "he"
# The Gemini translations the site itself uses for mission names (translate_mission_purpose.py, "names")
GEMINI_FILE = Path(__file__).parent / "mission-purpose-gemini.json"

# Mirrors FALLBACK_IMAGES / mapLaunchToSchema's category detection in index.html exactly,
# so a shared mission's preview image matches whatever image that mission actually shows
# once you're inside the app - see index.html's own FALLBACK_IMAGES for the source of truth
# if these ever drift apart.
FALLBACK_IMAGES = {
    "starship": "https://spacexfantracker.com/starship-share.jpg",
    # Same photo as index.html's starlink-launch-pad.webp, as a JPG with an absolute URL -
    # link-preview crawlers need the full URL and handle JPG more reliably than WebP.
    "starlink": "https://spacexfantracker.com/starlink-share.jpg",
    "falcon": "https://wp.technologyreview.com/wp-content/uploads/2024/07/AP24191572534430.jpg?w=3000",
    "falcon_heavy": "https://cdn.mos.cms.futurecdn.net/fnfyE7cDwV9JWCopNK8Ycb.jpg",
    # Same photo as index.html's dragon-launch-pad.jpg, as an absolute URL for link-preview crawlers.
    "dragon": "https://spacexfantracker.com/dragon-launch-pad.jpg",
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
<html {html_attrs}>
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
<meta property="og:locale" content="{og_locale}">

<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="{title_escaped}">
<meta name="twitter:description" content="{description_escaped}">
<meta name="twitter:image" content="{image_escaped}">

<script type="application/ld+json">{jsonld}</script>
<script>
(function(){{
  if (/Twitterbot|facebookexternalhit|Facebot|Slackbot|WhatsApp|TelegramBot|LinkedInBot|Discordbot|Pinterest|SkypeUriPreview|Applebot/i.test(navigator.userAgent||"")) return;
  // the sharer's site language rides along (?lang=, added by index.html's buildMissionShareUrl)
  var lang = (location.search.match(/[?&]lang=([A-Za-z-]{{2,6}})(?:&|$)/) || [])[1] || "{page_lang}";
  location.replace("{app_url_escaped}" + (lang ? "&lang=" + lang : ""));
}})();
</script>

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
<a class="brand" href="{home_url_escaped}">{back_arrow} SpaceX Fan Tracker</a>
<img class="photo" src="{image_escaped}" alt="{title_escaped}">
<h1>{name_escaped}</h1>
<div class="vehicle">{vehicle_escaped}</div>
<div class="stats">
<div><div class="stat-label">{label_site}</div><div class="stat-value">{site_escaped}</div></div>
<div><div class="stat-label">{label_date}</div><div class="stat-value">{date_escaped}</div></div>
</div>
<p class="lede">{lede}</p>
<a class="cta" href="{app_url_escaped}">{cta} {forward_arrow}</a>
<a class="back" href="{home_url_escaped}">{back_arrow} {back}</a>
</div>
</body>
</html>
"""


PAGE_TEXT = {
    "en": {
        "html_attrs": 'lang="en"', "og_locale": "en_US", "page_lang": "",
        "label_site": "Launch Site", "label_date": "Date",
        "lede": "Live countdown, launch window, booster and recovery details, and video coverage for this SpaceX mission are available in the full tracker.",
        "cta": "Open in Live Tracker", "back": "Back to all missions", "forward_arrow": "&rarr;", "back_arrow": "&larr;",
    },
    "he": {
        "html_attrs": 'lang="he" dir="rtl"', "og_locale": "he_IL", "page_lang": "iw",
        "label_site": "אתר השיגור", "label_date": "תאריך",
        "lede": "ספירה לאחור חיה, חלון השיגור, פרטי הבוסטר והנחיתה וסרטון השיגור של משימת SpaceX הזו נמצאים באתר המלא.",
        "cta": "פתח באתר", "back": "לכל המשימות", "forward_arrow": "&larr;", "back_arrow": "&rarr;",
    },
}

HE_MONTHS = ["ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני", "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר"]
# Right-to-left mark. At the start it makes the preview apps lay the Hebrew title out right-to-left even though
# it starts with "SpaceX"; after a separator it keeps the number that follows an English code ("SFB • 26
# בספטמבר") from being pulled into the English run and shown on the wrong side
RLM = "‏"
# index.html's SITE_NAME_I18N.he (+ the Louisiana name in localizeSiteStr)
SITE_NAMES_HE = [
    ("Orbital Launch Pad", "משטח שיגור מסלולי"), ("Kennedy Space Center", "מרכז החלל קנדי"),
    ("Cape Canaveral", "קייפ קנוורל"), ("Vandenberg", "ואנדנברג"), ("Starbase", "סטארבייס"),
    ("Pecan Island", "פקאן איילנד"), ("Louisiana", "לואיזיאנה"),
]


def format_date_he(net: str) -> str:
    try:
        dt = datetime.fromisoformat(net.replace("Z", "+00:00")).astimezone(timezone.utc)
        return f"{dt.day} ב{HE_MONTHS[dt.month - 1]} {dt.year}"
    except Exception:
        return "טרם נקבע"


# Same cleanup translate_mission_purpose.py applies to a name before hashing it for the "names" map
def clean_mission_name(name: str) -> str:
    name = re.sub(r"\s*Block\s*5\b", "", name, flags=re.IGNORECASE)
    name = re.sub(r"((?:bandwagon|starship|transporter)[^(]*)\([^)]*\)\s*$", r"\1", name, flags=re.IGNORECASE)
    name = re.sub(r"\s{2,}", " ", name)
    return name.strip()


# index.html's localizeMissionName for Hebrew: the fixed terms the site itself puts into every mission title
def localize_name_he(text: str) -> str:
    text = re.sub(r"\bFlight\s+(\d+)", r"טיסה \1", text, flags=re.I)
    text = re.sub(r"starship", "סטארשיפ", text, flags=re.I)
    text = re.sub(r"starlink", "סטארלינק", text, flags=re.I)
    text = re.sub(r"bandwagon", "בנדוואגון", text, flags=re.I)
    text = re.sub(r"\btransporter(?=[\s-]*\d)", "טרנספורטר", text, flags=re.I)
    text = re.sub(r"\bCrew-(\d+)\b", r"צוות \1", text, flags=re.I)
    text = re.sub(r"סטארלינק\s+(?:Group|קבוצה)\s+", "קבוצת סטארלינק ", text, flags=re.I)
    text = re.sub(r"Falcon\s+Heavy", "פלקון כבד", text, flags=re.I)
    return re.sub(r"Falcon\s+9", "פלקון 9", text, flags=re.I)


def load_he_names() -> dict:
    try:
        names = json.loads(GEMINI_FILE.read_text(encoding="utf-8")).get("names") or {}
        return {h: v["he"] for h, v in names.items() if isinstance(v, dict) and isinstance(v.get("he"), str) and v["he"].strip()}
    except Exception as e:
        print(f"Warning: couldn't read Hebrew mission names from {GEMINI_FILE.name}: {e}", file=sys.stderr)
        return {}


def build_page(mission_id: str, name: str, vehicle: str, site: str, net: str, lang: str = "en", he_names: dict = None) -> str:
    category = detect_category(name, vehicle)
    image = FALLBACK_IMAGES.get(category, FALLBACK_IMAGES["falcon"])
    text = PAGE_TEXT[lang]
    app_url = f"{SITE_BASE_URL}/?mission={quote(mission_id)}"
    if lang == "he":
        cleaned = clean_mission_name(name)
        name = localize_name_he((he_names or {}).get(hashlib.sha256(cleaned.encode("utf-8")).hexdigest()) or cleaned)
        vehicle = localize_name_he(clean_mission_name(vehicle))
        site = re.sub(r",\s*(?:[A-Z]{2},\s*)?USA$", "", site)
        for en_name, he_name in SITE_NAMES_HE:
            site = site.replace(en_name, he_name)
        date_str = format_date_he(net)
        title = f"{RLM}SpaceX • {name}"
        description = f"{RLM}{vehicle} •{RLM} {site} •{RLM} {date_str}"
        card_url = f"{SITE_BASE_URL}/m/he/{quote(mission_id)}.html"
    else:
        date_str = format_date(net)
        title = f"SpaceX • {name}"
        description = f"{vehicle} • {site} • {date_str}"
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
    # json.dumps doesn't escape "</" - if a mission name/description ever contained the literal
    # substring "</script>", it would close the JSON-LD script tag early and let the rest of the
    # string be parsed as HTML on this publicly indexed, auto-committed page. The escaped form is
    # still valid inside a JSON string and un-escapes back to "</" when parsed as JSON.
    jsonld = jsonld.replace("</", "<\\/")

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
        **text,
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
    OUTPUT_DIR_HE.mkdir(exist_ok=True)
    he_names = load_he_names()

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
        (OUTPUT_DIR_HE / f"{safe_id}.html").write_text(build_page(mission_id, name, vehicle, site, net, "he", he_names), encoding="utf-8")
        written += 1
        written_safe_ids.append(safe_id)

    print(f"Wrote {written} mission preview pages to {OUTPUT_DIR}/")

    # Guard against a "successful" fetch (HTTP 200, non-empty lists) that nonetheless yields zero
    # usable missions - e.g. an LL2 schema change that renames/drops the "id" field would make
    # every record fail the `if not mission_id` check above, leaving written_safe_ids empty. That
    # must never reach the pruning step below: with an empty keep-set, pruning would delete every
    # existing m/*.html file and the sitemap would be written with only the homepage URL, and
    # both would then be auto-committed by the scheduled workflow with no human review.
    if not written_safe_ids:
        print("No usable missions extracted from a non-empty API response - aborting without touching existing files.", file=sys.stderr)
        sys.exit(1)

    # Remove any leftover page from a previous run whose mission has since aged out of both the
    # upcoming and previous LL2 windows - otherwise old pages (some still in the old redirect
    # format from before this script was rewritten) linger on disk and stay reachable/indexable
    # forever even though they no longer appear in the sitemap. Only ever touches files inside
    # OUTPUT_DIR (m/) that match the mission-id filename pattern this script itself writes.
    keep = {f"{safe_id}.html" for safe_id in written_safe_ids}
    pruned = 0
    for existing in list(OUTPUT_DIR.glob("*.html")) + list(OUTPUT_DIR_HE.glob("*.html")):
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
