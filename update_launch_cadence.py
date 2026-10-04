#!/usr/bin/env python3
"""
Collects this year's orbital launches worldwide (Launch Library 2) for the site's launch-cadence chart and writes
them to launch-cadence.json at the repo root, which index.html's chart reads first (same origin).

The chart used to ask Launch Library 2 for this itself, from every visitor's browser, once a day per visitor - about
3 requests out of the same free budget of 15 an hour per address that the site's own launch data needs. Here it is
asked once for everyone, on GitHub's servers. The browser still asks Launch Library itself only when this file is
missing or out of date.

Only the date and the provider of each launch are kept ([net, provider-key]); the page sorts them into weeks itself,
in the visitor's own time zone, exactly as it did with the data it fetched. Refreshed at most every
REFRESH_AFTER_HOURS; on any failure the existing file is left as it is.

Runs in the scheduled GitHub Action (see .github/workflows/update-mission-cards.yml). No dependencies beyond the
Python standard library.
"""

import json
import re
import sys
import time
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

OUTPUT_PATH = Path(__file__).parent / "launch-cadence.json"
REFRESH_AFTER_HOURS = 20
MAX_PAGES = 8


CHINA_PROVIDERS = re.compile(r"china aerospace|cas space|china rocket|landspace|expace|galactic energy|orienspace|space pioneer|ispace|deep blue")


# same buckets as index.html's classifyLiveProvider. The list (mode=list) gives the provider as "lsp_name" and the
# place as a "location" string; the detailed form's launch_service_provider object is read too, if present.
def classify(launch: dict):
    lsp = launch.get("launch_service_provider") if isinstance(launch.get("launch_service_provider"), dict) else {}
    name = (lsp.get("name") or launch.get("lsp_name") or "").lower()
    country = lsp.get("country_code") or ""
    loc = launch.get("location")
    location = loc if isinstance(loc, str) else ((loc or {}).get("name") or "")
    if "spacex" in name:
        return "spacex"
    if "united launch alliance" in name:
        return "ula"
    if "blue origin" in name:
        return "blue"
    if "rocket lab" in name:
        return "rlab"
    if "arianespace" in name or "avio" in name:          # Vega C is flown by Avio now
        return "europe"
    if re.search(r"roscosmos|russian|khrunichev|energiya", name):   # every Russian operator, like the yearly totals
        return "russia"
    if country == "CHN" or CHINA_PROVIDERS.search(name) or re.search(r"People's Republic of China|Haiyang", location, re.I):
        return "china"
    return None


def fetch_json(url: str) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": "spacexfantracker launch cadence"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main() -> int:
    now = datetime.now(timezone.utc)
    try:
        old = json.loads(OUTPUT_PATH.read_text(encoding="utf-8"))
        updated = datetime.fromisoformat(str(old.get("updatedAt", "")).replace("Z", "+00:00"))
        if old.get("year") == now.year and now - updated < timedelta(hours=REFRESH_AFTER_HOURS):
            print(f"{OUTPUT_PATH.name} is recent enough (updated {old.get('updatedAt')}) - not refreshed this run.")
            return 0
    except Exception:
        pass

    year_start = datetime(now.year, 1, 1, tzinfo=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    url = f"https://ll.thespacedevs.com/2.2.0/launch/previous/?net__gte={year_start}&limit=100&mode=list&ordering=net"
    results = []
    try:
        for page in range(MAX_PAGES):
            if not url:
                break
            if page:
                time.sleep(3)
            data = fetch_json(url)
            results.extend(data.get("results") or [])
            url = data.get("next") if data.get("next") and len(results) < (data.get("count") or len(results)) else None
    except Exception as e:
        print(f"Could not read Launch Library 2: {e} - leaving {OUTPUT_PATH.name} unchanged.", file=sys.stderr)
        return 0

    launches = []
    for launch in results:
        net = launch.get("net")
        key = classify(launch)
        if net and key:
            launches.append([net, key])
    if not any(key == "spacex" for _, key in launches):
        # never a real answer (SpaceX launches every few days) - an empty or changed response; keep the old file
        print(f"No SpaceX launches in the answer ({len(results)} rows) - leaving {OUTPUT_PATH.name} unchanged.", file=sys.stderr)
        return 0

    out = {"year": now.year, "updatedAt": now.strftime("%Y-%m-%dT%H:%M:%SZ"), "launches": launches}
    OUTPUT_PATH.write_text(json.dumps(out, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"Wrote {OUTPUT_PATH.name}: {len(launches)} launches this year.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
