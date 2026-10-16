#!/usr/bin/env python3
"""
Saves SpaceX's upcoming launches from Launch Library - the same request index.html makes for its countdowns - to
launches-snapshot.json at the repo root, for a first visit's instant first paint: a browser with nothing saved used
to show the hero's loading skeleton until the live sources answered one after another (9+ seconds measured on
2026-10-16). The page shows this file at once (same origin, no rate limit) and the live answer replaces it as soon as
it arrives; a passed T-0 in it is not taken for a liftoff (the hero's "checking status" rule), so an old time in it
can't show a launch still on the pad as flying.

Runs in the scheduled GitHub Action (see .github/workflows/update-mission-cards.yml). On any failure (Launch Library
rate-limits shared addresses too) the existing file is left as it is.

No dependencies beyond the Python standard library.
"""

import json
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

URL = "https://ll.thespacedevs.com/2.2.0/launch/upcoming/?lsp__id=121&limit=15&mode=detailed"
OUTPUT_PATH = Path(__file__).parent / "launches-snapshot.json"


def main() -> int:
    try:
        req = urllib.request.Request(URL, headers={"User-Agent": "spacexfantracker launches snapshot"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"Could not fetch upcoming launches: {e} - leaving {OUTPUT_PATH.name} unchanged.", file=sys.stderr)
        return 0
    results = data.get("results") if isinstance(data, dict) else None
    if not isinstance(results, list) or not results:
        print(f"No launches in the answer - leaving {OUTPUT_PATH.name} unchanged.", file=sys.stderr)
        return 0
    out = {"fetchedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "results": results}
    OUTPUT_PATH.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"Saved {len(results)} upcoming launches to {OUTPUT_PATH.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
