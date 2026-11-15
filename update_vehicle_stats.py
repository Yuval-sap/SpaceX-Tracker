#!/usr/bin/env python3
"""
Launch counts for the evolution page (evolution/stats.json): each vehicle's successful and total launches as Launch
Library counts them per version, so the cards of the rockets still flying (Falcon 9 Block 5, Falcon Heavy, Starship
V3) stay current. The page has the same numbers built in (evolution/data.js) and only takes this file's when it is
there and newer.

Runs in the scheduled GitHub Action (see .github/workflows/update-mission-cards.yml) on GitHub's servers - never on
a visitor's or the owner's own address. Two requests, at most once every 20 hours; on any failure (Launch Library
rate-limits shared addresses too) the existing file is left as it is.

No dependencies beyond the Python standard library.
"""

import json
import sys
import urllib.request
from datetime import datetime, timezone, timedelta
from pathlib import Path

OUTPUT_PATH = Path(__file__).parent / "evolution" / "stats.json"
BASE = "https://ll.thespacedevs.com/2.2.0/config/launcher/?mode=detailed&limit=30&search="
# the page's vehicle id -> Launch Library's launcher configurations (Starship V3's later flights may be filed under the
# plain "Starship" configuration, 528, so both are added up)
IDS = {"falcon1": [133], "f9v10": [169], "f9v11": [14], "f9b5": [164], "fh": [161],
       "ssv1": [464], "ssv2": [527], "ssv3": [522, 528]}


def fetch(term):
    req = urllib.request.Request(BASE + term, headers={"User-Agent": "spacexfantracker vehicle stats"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8")).get("results") or []


def main() -> int:
    try:
        old = json.loads(OUTPUT_PATH.read_text(encoding="utf-8"))
        then = datetime.strptime(old.get("updatedAt", ""), "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
        if datetime.now(timezone.utc) - then < timedelta(hours=20):
            print(f"{OUTPUT_PATH.name} is less than 20 hours old - not refreshed.")
            return 0
    except Exception:
        pass
    try:
        configs = {c.get("id"): c for term in ("falcon", "starship") for c in fetch(term)}
    except Exception as e:
        print(f"Could not fetch the launcher configurations: {e} - leaving {OUTPUT_PATH.name} unchanged.", file=sys.stderr)
        return 0
    vehicles = {}
    for vid, ids in IDS.items():
        found = [configs[i] for i in ids if i in configs]
        if not found or found[0].get("total_launch_count") is None:
            continue
        vehicles[vid] = {"ok": sum(int(c.get("successful_launches") or 0) for c in found),
                         "total": sum(int(c.get("total_launch_count") or 0) for c in found)}
    if len(vehicles) < 5:
        print(f"Only {len(vehicles)} vehicles found - leaving {OUTPUT_PATH.name} unchanged.", file=sys.stderr)
        return 0
    out = {"updatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "vehicles": vehicles}
    OUTPUT_PATH.write_text(json.dumps(out, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"Saved launch counts for {len(vehicles)} vehicles to {OUTPUT_PATH.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
