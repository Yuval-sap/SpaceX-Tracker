"""Keeps SpaceX's own post-launch timeline of every mission, for good (spacex-timelines.json).

SpaceX's website lists each mission's timeline ("T+00:02:30 MECO ...") on its mission page while the launch is upcoming,
and takes many of those pages down some time after the flight. The site's launch simulation flies those real times, so
this keeps a copy of each one as it appears: every run reads SpaceX's upcoming-launches list, fetches each mission's
page and stores its timeline (and the text paragraphs, where the orbit's altitude is stated), keyed by the page's slug.
Entries are only added or refreshed, never removed. Run by the scheduled GitHub workflow (update-mission-cards.yml).
"""
import json
import os
import re
import sys
import time
import urllib.request

BASE = "https://content.spacex.com/api/spacex-website"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "spacex-timelines.json")
# pages that may already be off the upcoming list but are still up (seeded once; harmless to re-check)
EXTRA_SLUGS = ["starship-flight-14", "starship-flight-15", "sl-10-40"]


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (timeline archive)", "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))


def main():
    try:
        with open(OUT, encoding="utf-8") as f:
            archive = json.load(f)
    except (OSError, ValueError):
        archive = {}
    slugs = list(EXTRA_SLUGS)
    try:
        for tile in get(BASE + "/launches-page-tiles/upcoming") or []:
            link = str((tile or {}).get("link") or "").strip("/")
            if link and link not in slugs:
                slugs.append(link)
    except Exception as e:  # the list unreachable: keep what is archived
        print("upcoming list unavailable:", e)
    # and the recent past launches (Launch Library's dev mirror: no rate limit), by the same slug guesses the site makes
    # (--all: every past SpaceX launch, a one-off backfill; the scheduled run checks the latest 40)
    guessed = {}
    try:
        past, url = [], "https://lldev.thespacedevs.com/2.2.0/launch/previous/?lsp__id=121&limit=100&mode=list"
        if "--all" not in sys.argv:
            url = url.replace("limit=100", "limit=40")
        while url:
            page = get(url) or {}
            past += page.get("results") or []
            url = page.get("next") if "--all" in sys.argv else None
        for l in past:
            name = str(l.get("name") or "")
            raw = re.sub(r"\s*\([^)]*\)\s*$", "", name)
            payload = raw.split("|")[-1].strip()
            cands = []
            m = re.search(r"\(\s*starship\s+flight\s+(\d+)\s*\)", name, re.I)
            if m: cands.append("starship-flight-" + m.group(1))
            m = re.search(r"starlink(?:\s+group)?\s+(\d+)\s*[-–]\s*(\d+)", raw, re.I)
            if m: cands.append("sl-%s-%s" % (m.group(1), m.group(2)))
            m = re.search(r"\bspx[\s-]*(\d+)\b", raw, re.I)
            if m: cands.append("crs" + m.group(1))
            m = re.search(r"\bng[\s-]*(\d+)\b", raw, re.I)
            if m: cands += ["ng" + m.group(1), "ng-" + m.group(1)]
            m = re.search(r"tranche\s+(\d+)\s+transport\s+layer\s+([a-z])\b", raw, re.I)
            if m: cands.append("sda-t%stl-%s" % (m.group(1), m.group(2).lower()))
            cands += [re.sub(r"[^a-z0-9]", "", payload.lower()), re.sub(r"[^a-z0-9]+", "-", payload.lower()).strip("-")]
            guessed[name] = [c for c in cands if len(c) >= 4]
    except Exception as e:
        print("past launches unavailable:", e)
    for name, cands in guessed.items():
        if any(c in archive for c in cands):
            continue
        for c in cands:
            if c not in slugs:
                slugs.append(c)
    changed = 0
    for slug in slugs:
        try:
            m = get(BASE + "/missions/" + slug)
        except Exception as e:
            continue
        entries = ((m or {}).get("postLaunchTimeline") or {}).get("timelineEntries") or []
        entries = [{"time": e.get("time"), "description": e.get("description")} for e in entries if e and e.get("time") and e.get("description")]
        if not entries:
            continue
        paragraphs = [{"content": p.get("content")} for p in (m.get("paragraphs") or []) if isinstance(p, dict) and p.get("content")]
        rec = {"title": m.get("title") or "", "slug": slug, "postLaunchTimeline": {"timelineEntries": entries}, "paragraphs": paragraphs}
        old = archive.get(slug)
        if not old or old.get("postLaunchTimeline") != rec["postLaunchTimeline"] or old.get("title") != rec["title"]:
            rec["saved"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            archive[slug] = rec
            changed += 1
            print("saved", slug, len(entries), "events")
    if changed:
        with open(OUT, "w", encoding="utf-8") as f:
            json.dump(archive, f, ensure_ascii=False, indent=1, sort_keys=True)
    print(len(archive), "timelines archived,", changed, "updated")
    return 0


if __name__ == "__main__":
    sys.exit(main())
