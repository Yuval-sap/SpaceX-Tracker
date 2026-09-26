#!/usr/bin/env python3
"""
Reads the number of ACTIVE Starlink satellites from satellitemap.space's public Starlink page and
writes it to starlink-stats.json at the repo root, which index.html's Starlink card reads (same
origin, so no CORS proxy in the visitor's browser - the old allorigins route took ~20 s or failed,
well past the page's 5 s timeout, so the live number never arrived).

Runs in the scheduled GitHub Action (see .github/workflows/update-mission-cards.yml). The site has
no public API, so this reads the page's "Active Satellites" figure; once every few hours is a
gentle load. On any failure the existing JSON is left as it is.

No dependencies beyond the Python standard library.
"""

import json
import re
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

URL = "https://satellitemap.space/constellation/starlink"
OUTPUT_PATH = Path(__file__).parent / "starlink-stats.json"


def main() -> int:
    try:
        req = urllib.request.Request(URL, headers={"User-Agent": "Mozilla/5.0 (spacexfantracker starlink count)"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            page = resp.read().decode("utf-8", errors="replace")
    except Exception as e:
        print(f"Could not fetch {URL}: {e} - leaving {OUTPUT_PATH.name} unchanged.", file=sys.stderr)
        return 0
    # the label and the number sit in separate tags ('<span class="text-green-400"> 11134 </span>') - the
    # tags are removed first, or the digits in a class name get read as the count
    text = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", page))
    m = re.search(r"Active Satellites\s*([\d,]+)", text, re.I)
    active = int(m.group(1).replace(",", "")) if m else 0
    if active < 5000:
        print(f"No plausible active count found (got {active}) - leaving {OUTPUT_PATH.name} unchanged.", file=sys.stderr)
        return 0
    # rewritten only when the count changes (updatedAt = when this count was first seen), so an unchanged
    # count doesn't make a commit every run
    try:
        if json.loads(OUTPUT_PATH.read_text(encoding="utf-8")).get("active") == active:
            print(f"Active Starlink satellites unchanged: {active}")
            return 0
    except Exception:
        pass
    data = {"active": active, "source": URL, "updatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
    OUTPUT_PATH.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    print(f"Active Starlink satellites: {active}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
