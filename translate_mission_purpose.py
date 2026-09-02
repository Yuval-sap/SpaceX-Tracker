#!/usr/bin/env python3
"""
Pre-translates Launch Library mission-purpose fields (description, type, program, agencies)
into every language the site supports, using the Gemini API, and writes
mission-purpose-gemini.json at the repo root.

index.html reads that file in the visitor's browser. The API key never goes in the page:
it lives in the GitHub Actions secret GEMINI_API_KEY and is only used on GitHub's servers.

If GEMINI_API_KEY is missing, this script exits 0 and leaves the existing JSON untouched
so a missing secret cannot break the share-card job that runs in the same workflow.

No dependencies beyond the Python standard library.
"""

import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

OUTPUT_PATH = Path(__file__).parent / "mission-purpose-gemini.json"

LL2_UPCOMING_URL = "https://ll.thespacedevs.com/2.2.0/launch/upcoming/?lsp__id=121&limit=15&mode=detailed"
LL2_PREVIOUS_URL = "https://ll.thespacedevs.com/2.2.0/launch/previous/?lsp__id=121&limit=50&mode=detailed"

GENERIC_DESCRIPTION = "SpaceX operational launch deployment mission."
# gemini-2.0-flash is retired for new AI Studio keys (HTTP 404). Prefer 3.8 Flash
# (the current public Flash). Also try 4.8 if a studio screen lists that id.
_DEFAULT_MODELS = [
    os.environ.get("GEMINI_MODEL") or "gemini-3.8-flash",
    "gemini-4.8-flash",
    "gemini-flash-latest",
    "gemini-3.7-flash",
    "gemini-3.6-flash",
]
GEMINI_MODELS = []
for _name in _DEFAULT_MODELS:
    if _name and _name not in GEMINI_MODELS:
        GEMINI_MODELS.append(_name)
_active_model = GEMINI_MODELS[0]


class GeminiAuthError(RuntimeError):
    """API key rejected — do not keep calling."""


def gemini_url(model: str) -> str:
    return f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"

# Must match MISSION_PURPOSE_I18N in index.html (except en, and iw which is the same as he).
LANG_NAMES = {
    "he": "Hebrew",
    "es": "Spanish",
    "fr": "French",
    "de": "German",
    "ru": "Russian",
    "zh": "Simplified Chinese",
    "it": "Italian",
    "cs": "Czech",
    "sv": "Swedish",
    "nl": "Dutch",
    "da": "Danish",
    "pt": "Portuguese",
    "pl": "Polish",
    "hi": "Hindi",
    "ar": "Arabic",
    "tr": "Turkish",
}

# Two batches so one Gemini reply stays small enough to parse reliably.
LANG_BATCHES = [
    ["he", "es", "fr", "de", "ru", "zh", "it", "cs"],
    ["sv", "nl", "da", "pt", "pl", "hi", "ar", "tr"],
]

# Same forms the site already uses in STARSHIP_I18N / STARLINK_I18N.
STARSHIP_TERM = {
    "he": "סטארשיפ", "es": "Starship", "fr": "Starship", "de": "Starship",
    "ru": "Старшип", "zh": "星舰", "it": "Starship", "cs": "Starship",
    "sv": "Starship", "nl": "Starship", "da": "Starship", "pt": "Starship",
    "pl": "Starship", "hi": "स्टारशिप", "ar": "ستارشيب", "tr": "Starship",
}
STARLINK_TERM = {
    "he": "סטארלינק", "es": "Starlink", "fr": "Starlink", "de": "Starlink",
    "ru": "Старлинк", "zh": "星链", "it": "Starlink", "cs": "Starlink",
    "sv": "Starlink", "nl": "Starlink", "da": "Starlink", "pt": "Starlink",
    "pl": "Starlink", "hi": "स्टारलिंक", "ar": "ستارلينك", "tr": "Starlink",
}

PROMPT = """You translate SpaceX launch-library mission fields from English into several languages.

Return ONLY valid JSON. Top-level keys must be exactly these language codes:
{lang_keys}

Each language value must be an object with exactly these keys:
description, missionType, programStr, agenciesStr

Rules:
- Natural, accurate translation. Do not translate word-by-word.
- zh must be Simplified Chinese.
- Keep these as-is in every language (do not translate or respell them): SpaceX, Falcon, Falcon 9, Falcon Heavy, Dragon, Crew Dragon, Starshield, Starfall, USSF, NASA, NOAA, NRO, GPS, LEO, MEO, GEO, GTO, ISS, VLEO, and mission codes such as USSF-153.
- For "Starship" use exactly: {starship_terms}
- For "Starlink" use exactly: {starlink_terms}
- "satellite bus" is the satellite platform/chassis, NEVER a road vehicle. Translate that meaning in each language (Hebrew: פלטפורמת הלוויין).
- "classified" means secret/restricted, not "sorted". "splashdown" is a water landing. "rideshare" is a shared launch.
- Empty English input must stay an empty string in every language.
- Do not add labels, markdown, or commentary.

English:
description: {description}
missionType: {missionType}
programStr: {programStr}
agenciesStr: {agenciesStr}
"""


def fetch_launches(url: str, retries: int = 4) -> list:
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "spacexfantracker-gemini-purpose/1.0"})
            with urllib.request.urlopen(req, timeout=20) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            return data.get("results", []) or []
        except Exception as e:
            wait = 20 * (attempt + 1)
            print(f"Warning: failed to fetch {url}: {e}", file=sys.stderr)
            if attempt < retries - 1:
                print(f"Retrying in {wait}s...", file=sys.stderr)
                time.sleep(wait)
    return []


def extract_fields(launch: dict) -> dict:
    mission = launch.get("mission") or {}
    description = launch.get("mission_description") or mission.get("description") or ""
    mission_type = mission.get("type") or ""

    programs = []
    for item in launch.get("program") or []:
        name = (item or {}).get("name") or ""
        stripped = re.sub(r"^\s*SpaceX\s+", "", name, flags=re.I).strip()
        programs.append(stripped or name)
    program_str = ", ".join(name for name in programs if name)

    agencies = []
    for item in mission.get("agencies") or []:
        name = (item or {}).get("name")
        if name:
            agencies.append(name)
    if not agencies:
        provider = (launch.get("launch_service_provider") or {}).get("name")
        if provider:
            agencies = [provider]
    agencies_str = ", ".join(agencies)

    return {
        "description": description,
        "missionType": mission_type,
        "programStr": program_str,
        "agenciesStr": agencies_str,
    }


def description_hash(description: str) -> str:
    return hashlib.sha256(description.encode("utf-8")).hexdigest()


def load_existing() -> dict:
    if not OUTPUT_PATH.exists():
        return {"version": 1, "generatedAt": "", "entries": {}}
    try:
        data = json.loads(OUTPUT_PATH.read_text(encoding="utf-8"))
    except Exception:
        return {"version": 1, "generatedAt": "", "entries": {}}
    if not isinstance(data, dict):
        return {"version": 1, "generatedAt": "", "entries": {}}
    data.setdefault("version", 1)
    data.setdefault("entries", {})
    if not isinstance(data["entries"], dict):
        data["entries"] = {}
    return data


def strip_json_fences(text: str) -> str:
    cleaned = (text or "").strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned)
        cleaned = re.sub(r"\s*```$", "", cleaned)
    return cleaned.strip()


def langs_complete(entry, langs) -> bool:
    if not isinstance(entry, dict):
        return False
    for lang in langs:
        block = entry.get(lang)
        if not isinstance(block, dict) or not str(block.get("description") or "").strip():
            return False
    return True


def normalize_lang_block(block) -> dict:
    if not isinstance(block, dict):
        return {}
    return {
        "description": str(block.get("description") or "").strip(),
        "missionType": str(block.get("missionType") or "").strip(),
        "programStr": str(block.get("programStr") or "").strip(),
        "agenciesStr": str(block.get("agenciesStr") or "").strip(),
    }


def call_gemini_batch(api_key: str, fields: dict, langs: list) -> dict:
    starship_terms = ", ".join(f"{lang}={STARSHIP_TERM[lang]}" for lang in langs)
    starlink_terms = ", ".join(f"{lang}={STARLINK_TERM[lang]}" for lang in langs)
    body = {
        "contents": [{"parts": [{"text": PROMPT.format(
            lang_keys=", ".join(langs),
            starship_terms=starship_terms,
            starlink_terms=starlink_terms,
            description=fields["description"],
            missionType=fields["missionType"],
            programStr=fields["programStr"],
            agenciesStr=fields["agenciesStr"],
        )}]}],
        "generationConfig": {
            "temperature": 0.2,
            "responseMimeType": "application/json",
            "maxOutputTokens": 8192,
        },
    }
    payload = json.dumps(body).encode("utf-8")
    global _active_model
    models_to_try = [_active_model] + [m for m in GEMINI_MODELS if m != _active_model]
    last_error = None
    for model in models_to_try:
        for attempt in range(3):
            try:
                req = urllib.request.Request(
                    gemini_url(model),
                    data=payload,
                    headers={
                        "Content-Type": "application/json",
                        "x-goog-api-key": api_key,
                        "User-Agent": "spacexfantracker-gemini-purpose/1.0",
                    },
                    method="POST",
                )
                with urllib.request.urlopen(req, timeout=60) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                text = (
                    data.get("candidates", [{}])[0]
                    .get("content", {})
                    .get("parts", [{}])[0]
                    .get("text", "")
                )
                parsed = json.loads(strip_json_fences(text))
                if not isinstance(parsed, dict):
                    raise ValueError("Gemini did not return a JSON object")
                out = {}
                for lang in langs:
                    block = normalize_lang_block(parsed.get(lang))
                    if block.get("description"):
                        out[lang] = block
                if not out:
                    raise ValueError("Gemini returned no usable language blocks")
                if model != _active_model:
                    print(f"Using Gemini model {model}")
                    _active_model = model
                return out
            except urllib.error.HTTPError as e:
                detail = ""
                try:
                    detail = e.read().decode("utf-8", errors="replace")[:400]
                except Exception:
                    detail = str(e)
                last_error = RuntimeError(f"HTTP {e.code} {model}: {detail}")
                if e.code in (401, 403):
                    raise GeminiAuthError(f"Gemini rejected the API key (HTTP {e.code}): {detail}")
                if e.code in (404, 400) and attempt == 0:
                    print(f"Model {model} is not available ({e.code}); trying another.", file=sys.stderr)
                    break
                if e.code == 429:
                    wait = 15 * (attempt + 1)
                    print(f"Warning: Gemini rate-limited; retrying in {wait}s...", file=sys.stderr)
                    time.sleep(wait)
                    continue
                if attempt < 2:
                    wait = 8 * (attempt + 1)
                    print(f"Warning: Gemini call failed ({last_error}); retrying in {wait}s...", file=sys.stderr)
                    time.sleep(wait)
                    continue
                break
            except Exception as e:
                last_error = e
                wait = 8 * (attempt + 1)
                print(f"Warning: Gemini call failed ({e}); retrying in {wait}s...", file=sys.stderr)
                time.sleep(wait)
    raise RuntimeError(last_error)


def write_output(data: dict) -> None:
    data["generatedAt"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    tmp = OUTPUT_PATH.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(OUTPUT_PATH)


def main() -> int:
    api_key = (os.environ.get("GEMINI_API_KEY") or "").strip()
    if not api_key:
        print("GEMINI_API_KEY is not set - leaving mission-purpose-gemini.json unchanged.")
        return 0

    upcoming = fetch_launches(LL2_UPCOMING_URL)
    time.sleep(3)
    previous = fetch_launches(LL2_PREVIOUS_URL)
    launches = upcoming + previous
    if not launches:
        print("No launches fetched - leaving existing translations unchanged.", file=sys.stderr)
        return 0

    store = load_existing()
    entries = store["entries"]
    translated = 0
    reused = 0
    skipped = 0
    failed = 0
    seen_hashes = set()

    for launch in launches:
        fields = extract_fields(launch)
        description = fields["description"]
        if not description or len(description) <= 30 or description.strip() == GENERIC_DESCRIPTION:
            skipped += 1
            continue
        key = description_hash(description)
        if key in seen_hashes:
            continue
        seen_hashes.add(key)

        existing = entries.get(key) if isinstance(entries.get(key), dict) else {}
        missing_batches = [batch for batch in LANG_BATCHES if not langs_complete(existing, batch)]
        if not missing_batches:
            reused += 1
            continue

        name = launch.get("name") or launch.get("id") or "unknown"
        merged = dict(existing)
        batch_ok = True
        for batch in missing_batches:
            try:
                merged.update(call_gemini_batch(api_key, fields, batch))
                time.sleep(1.5)
            except GeminiAuthError as e:
                print(f"Error: {e}", file=sys.stderr)
                if entries:
                    store["entries"] = entries
                    write_output(store)
                return 1
            except Exception as e:
                batch_ok = False
                print(f"Warning: skipped {name} languages {','.join(batch)}: {e}", file=sys.stderr)
                time.sleep(2)

        if not any(langs_complete({lang: merged.get(lang)}, [lang]) for lang in LANG_NAMES):
            failed += 1
            continue

        entries[key] = merged
        if batch_ok and langs_complete(merged, list(LANG_NAMES)):
            translated += 1
            print(f"Translated: {name}")
        else:
            failed += 1
            print(f"Partial: {name}")

    store["entries"] = entries
    if translated == 0 and failed > 0 and not any(entries.values()):
        print(
            f"Error: Gemini produced no translations (new=0 reused={reused} "
            f"skipped={skipped} failed={failed}). Leaving the JSON file unchanged.",
            file=sys.stderr,
        )
        return 1
    write_output(store)
    print(
        f"Done. new={translated} reused={reused} skipped={skipped} failed={failed} "
        f"total_entries={len(entries)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
