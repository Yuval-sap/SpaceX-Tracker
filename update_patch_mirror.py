#!/usr/bin/env python3
"""
Keeps a small copy of every mission patch the site shows in patch-mirror/ (WebP, at most 400px on a side, its
transparency kept), so the patches strip loads them from this site's own host (GitHub's CDN, close to every
visitor) instead of Launch Library's file server in New York - each patch took 1-3.5 s from Israel, and with dozens
loading at once some tiles were still empty seconds after the strip opened (2026-11-02).

The page asks for patch-mirror/<the original file's name>.webp and falls back to the original when it isn't there
yet (a launch newer than the last run), so a patch is never missing because of this. Only the strip uses the copies;
a patch opened large still loads the original.

Patches come from three places: the launch history built into index.html (HISTORICAL_RAW_LAUNCHES), the upcoming
launches snapshot (launches-snapshot.json), and SpaceX's latest launches from Launch Library - that one request at
most every REFRESH_AFTER_HOURS (its time kept in patch-mirror/state.json). A patch already copied is never fetched
again. On any failure the existing files are left as they are.

Runs in the scheduled GitHub Action (see .github/workflows/update-mission-cards.yml). Needs Pillow.
"""

import json
import re
import sys
import urllib.request
from datetime import datetime, timedelta, timezone
from io import BytesIO
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).parent
MIRROR_DIR = ROOT / "patch-mirror"
STATE_PATH = MIRROR_DIR / "state.json"
REFRESH_AFTER_HOURS = 20
MAX_SIDE = 400
# Launch Library's patch files - the only ones copied (hand-added patches already live in patches/)
PATCH_URL = re.compile(r"^https://[^/]*thespacedevs[^/]*/media/mission_patch_images/([^/?#]+)\.(?:png|jpe?g|webp|gif)$", re.I)


def fetch(url: str, timeout: int = 30) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "spacexfantracker patch mirror"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def patch_urls(launch: dict) -> list:
    urls = []
    for holder in (launch, launch.get("mission") or {}):
        for p in holder.get("mission_patches") or []:
            u = (p or {}).get("image_url")
            if u:
                urls.append(u)
    return urls


def historical_launches() -> list:
    html = (ROOT / "index.html").read_text(encoding="utf-8")
    marker = "const HISTORICAL_RAW_LAUNCHES = "
    i = html.find(marker)
    if i < 0:
        return []
    data, _ = json.JSONDecoder().raw_decode(html[i + len(marker):])
    return data if isinstance(data, list) else []


def latest_launches(state: dict, now: datetime) -> list:
    try:
        last = datetime.fromisoformat(str(state.get("fetchedAt", "")).replace("Z", "+00:00"))
        if now - last < timedelta(hours=REFRESH_AFTER_HOURS):
            return []
    except Exception:
        pass
    try:
        data = json.loads(fetch("https://ll.thespacedevs.com/2.2.0/launch/previous/?lsp__id=121&limit=40&mode=detailed").decode("utf-8"))
        state["fetchedAt"] = now.strftime("%Y-%m-%dT%H:%M:%SZ")
        return data.get("results") or []
    except Exception as e:
        print(f"Could not read Launch Library 2: {e} - copying only the patches already known.", file=sys.stderr)
        return []


def main() -> int:
    MIRROR_DIR.mkdir(exist_ok=True)
    now = datetime.now(timezone.utc)
    try:
        state = json.loads(STATE_PATH.read_text(encoding="utf-8"))
    except Exception:
        state = {}

    launches = historical_launches()
    try:
        launches += json.loads((ROOT / "launches-snapshot.json").read_text(encoding="utf-8")).get("results") or []
    except Exception:
        pass
    launches += latest_launches(state, now)

    wanted = {}
    for launch in launches:
        for url in patch_urls(launch):
            m = PATCH_URL.match(url)
            if m:
                wanted[m.group(1)] = url

    added = failed = 0
    for name, url in sorted(wanted.items()):
        out = MIRROR_DIR / f"{name}.webp"
        if out.exists():
            continue
        try:
            img = Image.open(BytesIO(fetch(url)))
            img = img.convert("RGBA")
            img.thumbnail((MAX_SIDE, MAX_SIDE), Image.LANCZOS)
            img.save(out, "WEBP", quality=90, method=6)
            added += 1
        except Exception as e:
            failed += 1
            print(f"Could not copy {url}: {e}", file=sys.stderr)

    STATE_PATH.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    print(f"patch-mirror: {added} new, {failed} failed, {len(wanted)} known.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
