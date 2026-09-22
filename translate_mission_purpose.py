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

# limit=7 matches index.html's own dbMissions = dbMissions.slice(0, 7) - the site never shows
# more than the 7 nearest upcoming launches, so translating further-out ones the UI never
# displays was pure wasted Gemini quota.
LL2_UPCOMING_URL = "https://ll.thespacedevs.com/2.2.0/launch/upcoming/?lsp__id=121&limit=7&mode=detailed"
# Past-launches strip on the site is a rolling 12 months, not "last 50" (see
# PAST_LAUNCH_WINDOW_MS / fetchPreviousLaunchPages in index.html) - a flat limit=50 here left
# ~70% of the cards the site can actually show (confirmed live: 111 of 161) permanently
# uncovered, since a mission that ages out of the 50 most recent never gets picked up by any
# future run either. Paginated the same way the site itself does: 100/page, net__gte cutoff,
# capped at 3 pages (300 launches - well over what a rolling year contains) as a quota guard.
LL2_PREVIOUS_URL = "https://ll.thespacedevs.com/2.2.0/launch/previous/?lsp__id=121&mode=detailed"
PAST_LAUNCH_WINDOW_DAYS = 365
LL2_PREVIOUS_PAGE_LIMIT = 100
LL2_PREVIOUS_MAX_PAGES = 3

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

# The account's actual free-tier ceiling (confirmed live in AI Studio's rate-limit page) is 5
# requests/minute PER MODEL - i.e. one request every 12s, minimum. The old 4s pacing was 3x
# faster than that, so it was guaranteed to trip a 429 on every run regardless of how much
# headroom the quota actually had - not an external "high demand" problem, our own request
# rate was structurally too fast. 13s leaves a 1s margin per call.
GEMINI_CALL_PACING_SECONDS = 13


class GeminiAuthError(RuntimeError):
    """API key rejected — do not keep calling."""


class GeminiQuotaExceededError(RuntimeError):
    """Free-tier daily request quota is exhausted (HTTP 429 with "exceeded your current
    quota", distinct from a transient rate-limit 429) - every remaining call this run would
    fail identically, so retrying (per-attempt backoff, then the next fallback model) just
    burns hours for nothing. Confirmed live: a run that hit this kept retrying for 5.5 hours
    and translated almost nothing. The right response is to stop the whole run immediately
    and let the next scheduled run (quota resets daily) pick up where this one left off."""


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
# he: "פלקון כבד" per explicit user request - the literal Hebrew translation of "Heavy" (not a
# phonetic transliteration). Without pinning this, Gemini picked its own transliteration
# ("פלקון האווי") instead, the same inconsistency "Falcon 9" had before it was pinned.
FALCON_HEAVY_TERM = {
    "he": "פלקון כבד", "es": "Falcon Heavy", "fr": "Falcon Heavy", "de": "Falcon Heavy",
    "ru": "Фалкон Хэви", "zh": "猎鹰重型", "it": "Falcon Heavy", "cs": "Falcon Heavy",
    "sv": "Falcon Heavy", "nl": "Falcon Heavy", "da": "Falcon Heavy", "pt": "Falcon Heavy",
    "pl": "Falcon Heavy", "hi": "फाल्कन हेवी", "ar": "فالكون هيفي", "tr": "Falcon Heavy",
}
# Pins the noun "launch" (a ROCKET launch, not a product/service launch) only for languages
# where a live mistranslation was actually confirmed - Hebrew's own word for "launch" defaults
# to "השקה" (product launch) instead of "שיגור" (rocket launch) when a generic MT engine
# translates it with no domain context, confirmed live in the update-log feature this pins for.
# Other languages are left to the model's own judgment in UPDATE_PROMPT's general instruction
# below rather than guessing at a term with no confirmed issue to fix.
LAUNCH_NOUN_TERM = {
    "he": "שיגור",
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

# Mission NAMES are translated separately from the description/type/program/agencies block
# above, and kept in their own top-level "names" map (see main()) keyed by a hash of the name
# itself, not the description - many different missions (every "Starlink Group X-Y" batch)
# share the exact same description, so a description-keyed entry can't also hold a
# mission-specific name without one mission's name leaking onto another's card/modal title.
# Batched (many names in ONE call, not one call per name) for the same reason the description
# batches above are: this is called once or twice per workflow run, not once per launch.
NAME_PROMPT = """You translate SpaceX launch/mission names from English into several languages,
for use as a short UI title (a card heading or modal title), not prose.

Return ONLY valid JSON. Top-level keys must be exactly these language codes:
{lang_keys}

Each language value must be a JSON ARRAY of exactly {count} strings - the translations, in the
SAME ORDER as the numbered names below (item 1's translation first, item 2's second, and so on).
Do not skip, merge, or reorder any entry, and do not repeat the original name anywhere in the
output - only the translated string for each position.

Rules:
- Translate ordinary descriptive words naturally (e.g. "Group", "Mission", "Dedicated",
  "Rideshare", "Transport Layer", "Constellation", "Flight").
- Vehicle/spacecraft family names - Falcon, Falcon 9, Dragon, Crew Dragon, Cygnus - SHOULD be
  translated/transliterated naturally into each language's own conventional spelling (e.g.
  Hebrew "פלקון 9"), the same way the rest of this site already renders them. Do NOT leave them
  in English.
- Do NOT translate, transliterate, or respell the brand name "SpaceX", or alphanumeric
  mission/satellite designations and product names - keep these exactly as-is in every language:
  SpaceX, O3b, mPower, and any code-like token such as USSF-153, NROL-95, SDA, GPS, CRS-2,
  SpX-35, NG-25.
- zh must be Simplified Chinese.
- For "Starship" use exactly: {starship_terms}
- For "Starlink" use exactly: {starlink_terms}
- For "Falcon Heavy" use exactly: {falcon_heavy_terms}
- Do not add labels, markdown, or commentary.

Names:
{names_block}
"""


# Official update-log entries for a launch (Launch Library's launch.updates[].comment - terse
# operations-log lines like "NET Sep 22, TBC." or "GO for launch.") shown in the site's
# "Pre-Launch Updates" popup (renderModalUpdates in index.html, Starship flights only - see
# is_starship_launch below). Same array-indexed response shape as NAME_PROMPT and for the same
# reason (a JSON-key-per-entry format risks truncating a large batch past maxOutputTokens).
UPDATE_PROMPT = """You translate short official status-update log entries for a SpaceX rocket
launch, from English into several languages, for a chronological update feed on a
launch-tracking website.

Return ONLY valid JSON. Top-level keys must be exactly these language codes:
{lang_keys}

Each language value must be a JSON ARRAY of exactly {count} strings - the translations, in the
SAME ORDER as the numbered entries below. Do not skip, merge, or reorder any entry.

Rules:
- Natural, accurate translation - not word-for-word. These are terse launch-operations log
  entries, not full sentences - expand abbreviations naturally in the translation: NET = No
  Earlier Than, TBC = To Be Confirmed, TBD = To Be Determined, "GO for launch" means the launch
  has been approved/authorized to proceed.
- CRITICAL: "launch" / "launched" / "launch window" here ALWAYS means a ROCKET launch (SpaceX
  sending a vehicle to space) - NEVER a product or service launch. Use each language's own
  correct term for a rocket launch specifically.{launch_term_note}
- zh must be Simplified Chinese.
- Keep as-is, untranslated, in every language: SpaceX, Falcon, Falcon 9, Falcon Heavy, Starship,
  Dragon, dates, times, and mission/satellite codes such as USSF-153.
- Do not add labels, markdown, or commentary.

Entries:
{entries_block}
"""


def call_gemini_update_batch(api_key: str, comments: list, langs: list) -> dict:
    """Same call/response shape as call_gemini_name_batch below (array of translations,
    index-matched to the input order) - see that function's own comment for why."""
    pinned = {lang: LAUNCH_NOUN_TERM[lang] for lang in langs if lang in LAUNCH_NOUN_TERM}
    launch_term_note = (
        " Specifically: " + ", ".join(f"{lang}={term}" for lang, term in pinned.items())
        if pinned else ""
    )
    entries_block = "\n".join(f"{i + 1}. {c}" for i, c in enumerate(comments))
    body = {
        "contents": [{"parts": [{"text": UPDATE_PROMPT.format(
            lang_keys=", ".join(langs),
            count=len(comments),
            launch_term_note=launch_term_note,
            entries_block=entries_block,
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
                    arr = parsed.get(lang)
                    if not isinstance(arr, list) or len(arr) != len(comments):
                        continue
                    cleaned = {}
                    for comment, val in zip(comments, arr):
                        if isinstance(val, str) and val.strip():
                            cleaned[comment] = val.strip()
                    if cleaned:
                        out[lang] = cleaned
                if not out:
                    raise ValueError("Gemini returned no usable update translations")
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
                    if "exceeded your current quota" in detail.lower():
                        raise GeminiQuotaExceededError(f"Gemini free-tier quota exhausted: {detail}")
                    wait = 15 * (attempt + 1)
                    print(f"Warning: Gemini rate-limited; retrying in {wait}s...", file=sys.stderr)
                    time.sleep(wait)
                    continue
                if attempt < 2:
                    wait = 8 * (attempt + 1)
                    print(f"Warning: Gemini update-batch call failed ({last_error}); retrying in {wait}s...", file=sys.stderr)
                    time.sleep(wait)
                    continue
                break
            except Exception as e:
                last_error = e
                wait = 8 * (attempt + 1)
                print(f"Warning: Gemini update-batch call failed ({e}); retrying in {wait}s...", file=sys.stderr)
                time.sleep(wait)
    raise RuntimeError(last_error)


def call_gemini_name_batch(api_key: str, names: list, langs: list) -> dict:
    """Returns {lang: {original_name: translated_name}} for whichever languages came back as a
    correctly-sized array - see NAME_PROMPT's own comment for why this is array-of-translations
    (index-matched to the input order) rather than an object keyed by the original name: the
    latter forces every one of the (often long) original names to be repeated as a JSON key once
    per language, which measured live pushed real responses past maxOutputTokens and got the
    response truncated mid-string, producing invalid JSON for the whole batch instead of a clean
    partial result."""
    starship_terms = ", ".join(f"{lang}={STARSHIP_TERM[lang]}" for lang in langs)
    starlink_terms = ", ".join(f"{lang}={STARLINK_TERM[lang]}" for lang in langs)
    falcon_heavy_terms = ", ".join(f"{lang}={FALCON_HEAVY_TERM[lang]}" for lang in langs)
    names_block = "\n".join(f"{i + 1}. {n}" for i, n in enumerate(names))
    body = {
        "contents": [{"parts": [{"text": NAME_PROMPT.format(
            lang_keys=", ".join(langs),
            count=len(names),
            starship_terms=starship_terms,
            starlink_terms=starlink_terms,
            falcon_heavy_terms=falcon_heavy_terms,
            names_block=names_block,
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
                    arr = parsed.get(lang)
                    if not isinstance(arr, list) or len(arr) != len(names):
                        # Wrong length means we can't trust the ordering - a mismatched array
                        # would silently pair the wrong translation with the wrong name, which
                        # is worse than just not having one. Skip this language for this batch;
                        # it stays untranslated and gets retried the next time this script runs.
                        continue
                    cleaned = {}
                    for name, val in zip(names, arr):
                        if isinstance(val, str) and val.strip():
                            cleaned[name] = val.strip()
                    if cleaned:
                        out[lang] = cleaned
                if not out:
                    raise ValueError("Gemini returned no usable name translations")
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
                    if "exceeded your current quota" in detail.lower():
                        raise GeminiQuotaExceededError(f"Gemini free-tier quota exhausted: {detail}")
                    wait = 15 * (attempt + 1)
                    print(f"Warning: Gemini rate-limited; retrying in {wait}s...", file=sys.stderr)
                    time.sleep(wait)
                    continue
                if attempt < 2:
                    wait = 8 * (attempt + 1)
                    print(f"Warning: Gemini name-batch call failed ({last_error}); retrying in {wait}s...", file=sys.stderr)
                    time.sleep(wait)
                    continue
                break
            except Exception as e:
                last_error = e
                wait = 8 * (attempt + 1)
                print(f"Warning: Gemini name-batch call failed ({e}); retrying in {wait}s...", file=sys.stderr)
                time.sleep(wait)
    raise RuntimeError(last_error)


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


# Mirrors index.html's own fetchPreviousLaunchPages: same page size, same net__gte cutoff, same
# page cap - so this script and the site agree on exactly which past launches "count".
def fetch_previous_launches() -> list:
    cutoff_iso = datetime.fromtimestamp(
        time.time() - PAST_LAUNCH_WINDOW_DAYS * 86400, tz=timezone.utc
    ).strftime("%Y-%m-%dT%H:%M:%SZ")
    collected = []
    offset = 0
    for _page in range(LL2_PREVIOUS_MAX_PAGES):
        url = (
            f"{LL2_PREVIOUS_URL}&limit={LL2_PREVIOUS_PAGE_LIMIT}&offset={offset}"
            f"&net__gte={cutoff_iso}"
        )
        rows = fetch_launches(url)
        if not rows:
            break
        collected.extend(rows)
        if len(rows) < LL2_PREVIOUS_PAGE_LIMIT:
            break
        offset += LL2_PREVIOUS_PAGE_LIMIT
        time.sleep(1)
    return collected


# Must match mapLaunchToSchema's own name cleanup in index.html EXACTLY (same regexes, same
# order, same whitespace collapsing) - the client hashes its own cleaned m.name to look up the
# "names" map, so if this script hashed a differently-cleaned name, every single lookup would
# miss (different hash) and silently fall back to the live Google endpoint for every mission,
# defeating the entire point of pre-translating names here. Confirmed live: this is exactly what
# happened when the Bandwagon parenthetical-stripping regex was added client-side only - every
# Bandwagon title's card fell back to Google (which doesn't phoneticize "Falcon 9" the way the
# Gemini names map does) until this second regex was added here too. Starship/Transporter added
# alongside Bandwagon later, same reasoning each time - keep this list in sync with the JS one.
def clean_mission_name(name: str) -> str:
    name = re.sub(r"\s*Block\s*5\b", "", name, flags=re.IGNORECASE)
    name = re.sub(r"((?:bandwagon|starship|transporter)[^(]*)\([^)]*\)\s*$", r"\1", name, flags=re.IGNORECASE)
    name = re.sub(r"\s{2,}", " ", name)
    return name.strip()


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


# Must match index.html's own category classification (vLower.includes('starship') in
# mapLaunchToSchema) - the update-log popup only ever shows for Starship flights, so
# translating every other launch's updates here would just burn Gemini quota on comments the
# site never displays.
def is_starship_launch(launch: dict) -> bool:
    vehicle_name = ((launch.get("rocket") or {}).get("configuration") or {}).get("full_name") or ""
    return "starship" in vehicle_name.lower()


# Must match index.html's own GENERIC_UPDATE_COMMENT_RE (renderModalUpdates) - the API's
# placeholder entry present on nearly every launch ("Added launch."), which the site filters out
# rather than displays, so translating it here would be pure wasted quota.
GENERIC_UPDATE_RE = re.compile(r"^added launch\.?$", re.IGNORECASE)


def description_hash(description: str) -> str:
    return hashlib.sha256(description.encode("utf-8")).hexdigest()


# Same hash, different name at the call site - the "names" map is keyed by hash-of-the-name
# itself (see call_gemini_name_batch's own comment for why it can't share entries' description
# key), not hash-of-the-description.
def name_hash(name: str) -> str:
    return hashlib.sha256(name.encode("utf-8")).hexdigest()


def load_existing() -> dict:
    default = {"version": 1, "generatedAt": "", "entries": {}, "names": {}, "updates": {}}
    if not OUTPUT_PATH.exists():
        return default
    try:
        data = json.loads(OUTPUT_PATH.read_text(encoding="utf-8"))
    except Exception:
        return default
    if not isinstance(data, dict):
        return default
    data.setdefault("version", 1)
    data.setdefault("entries", {})
    data.setdefault("names", {})
    data.setdefault("updates", {})
    if not isinstance(data["entries"], dict):
        data["entries"] = {}
    if not isinstance(data["names"], dict):
        data["names"] = {}
    if not isinstance(data["updates"], dict):
        data["updates"] = {}
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


# Same shape check as langs_complete above, but for the "names" map - each lang value there is
# a plain translated string (not a {description, missionType, ...} object), since a name only
# ever has the one field.
def name_langs_complete(entry, langs) -> bool:
    if not isinstance(entry, dict):
        return False
    for lang in langs:
        val = entry.get(lang)
        if not isinstance(val, str) or not val.strip():
            return False
    return True


# Used to decide, BEFORE spending any quota, whether this run should even bother fetching/
# translating the past-launch backlog - see main()'s own comment. Mirrors the same
# dedup-by-hash and dedup-by-name logic the real translation loops below use, so "still needs
# work" here means exactly what those loops would actually attempt.
def upcoming_still_needs_entries(upcoming: list, entries: dict) -> bool:
    seen_hashes = set()
    for launch in upcoming:
        fields = extract_fields(launch)
        description = fields["description"]
        if not description or len(description) <= 30 or description.strip() == GENERIC_DESCRIPTION:
            continue
        key = description_hash(description)
        if key in seen_hashes:
            continue
        seen_hashes.add(key)
        existing = entries.get(key) if isinstance(entries.get(key), dict) else {}
        if any(not langs_complete(existing, batch) for batch in LANG_BATCHES):
            return True
    return False


def upcoming_still_needs_names(upcoming: list, names_store: dict) -> bool:
    seen_names = set()
    for launch in upcoming:
        raw_name = clean_mission_name((launch.get("name") or "").strip())
        if not raw_name or raw_name in seen_names:
            continue
        seen_names.add(raw_name)
        existing = names_store.get(name_hash(raw_name))
        if not name_langs_complete(existing, list(LANG_NAMES)):
            return True
    return False


# Qualifying update-log comments for one launch - Starship only (matches index.html's own
# category gate) and skips the API's generic "Added launch." placeholder, the same two filters
# renderModalUpdates applies client-side. Shared by the priority check below and the real
# translation pass in main() so the two can never disagree about which comments "count".
def extract_update_comments(launch: dict) -> list:
    if not is_starship_launch(launch):
        return []
    out = []
    for update in launch.get("updates") or []:
        comment = ((update or {}).get("comment") or "").strip()
        if not comment or GENERIC_UPDATE_RE.match(comment):
            continue
        out.append(comment)
    return out


# Same "check before spending quota" role as upcoming_still_needs_names/-entries above.
def upcoming_still_needs_updates(upcoming: list, updates_store: dict) -> bool:
    seen = set()
    for launch in upcoming:
        for comment in extract_update_comments(launch):
            if comment in seen:
                continue
            seen.add(comment)
            existing = updates_store.get(name_hash(comment))
            if not name_langs_complete(existing, list(LANG_NAMES)):
                return True
    return False


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
                    if "exceeded your current quota" in detail.lower():
                        raise GeminiQuotaExceededError(f"Gemini free-tier quota exhausted: {detail}")
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
    store = load_existing()
    entries = store["entries"]
    names_store = store["names"]
    updates_store = store["updates"]

    # A brand-new upcoming mission (just entered the top-7 window, or a description LL2 only
    # just published) must be translated before this run spends any quota on the 365-day past
    # backlog - a fresh launch page showing a raw Google Translate fallback (see the "satellite
    # bus" -> literal-vehicle mistranslation this was written for) is far more visible/urgent
    # than an old past launch's card still being untranslated. Checked BEFORE even fetching the
    # previous-launches pages, so a run with upcoming work pending skips that fetch entirely and
    # puts every one of its (rate-limited, ~13s-per-call) requests toward upcoming missions only.
    if (
        upcoming_still_needs_entries(upcoming, entries)
        or upcoming_still_needs_names(upcoming, names_store)
        or upcoming_still_needs_updates(upcoming, updates_store)
    ):
        print("Upcoming launches still need translation - skipping the past-launch backlog this run.")
        previous = []
    else:
        time.sleep(3)
        previous = fetch_previous_launches()

    launches = upcoming + previous
    if not launches:
        print("No launches fetched - leaving existing translations unchanged.", file=sys.stderr)
        return 0

    # Mission NAMES - runs BEFORE the descriptions pass below, deliberately: a names call
    # batches up to NAME_CHUNK_SIZE names per request, while descriptions cost a request per
    # mission per language batch, so names are the cheapest pass by far and also the most
    # visible one (card titles). Confirmed live: with descriptions first, the free-tier daily
    # quota (20 requests) ran out mid-descriptions and this pass never ran at all - Bandwagon's
    # card title stayed on the Google fallback, leaving "Falcon 9" in English. A separate
    # pass with its own "names" map (see call_gemini_name_batch's own comment for why names
    # can't share the description-keyed entries below). Only names
    # that are new or still missing a language batch get sent; anything already fully
    # translated from a previous run is skipped (name_reused), same reuse philosophy as entries.
    # (names_store was already pulled from `store` above, before the upcoming-vs-past decision.)
    name_translated = 0
    name_reused = 0
    name_failed = 0
    seen_names = set()
    names_to_translate = []
    for launch in launches:
        raw_name = clean_mission_name((launch.get("name") or "").strip())
        if not raw_name or raw_name in seen_names:
            continue
        seen_names.add(raw_name)
        existing_name_entry = names_store.get(name_hash(raw_name))
        if not isinstance(existing_name_entry, dict):
            existing_name_entry = {}
        if name_langs_complete(existing_name_entry, list(LANG_NAMES)):
            name_reused += 1
            continue
        names_to_translate.append(raw_name)

    # Chunked well under Gemini's context/output budget - each response has to carry every
    # name x every language in the chunk's batch, and a single oversized call is exactly what
    # call_gemini_batch's own "Gemini returned no usable ... blocks" failure mode guards
    # against for the description prompt above. Confirmed live at 30/chunk with the old
    # name-keyed response format: real responses got truncated mid-string past
    # maxOutputTokens. The array-based format above is far cheaper per name, but kept smaller
    # (15) anyway for headroom - mission names can run long ("Bandwagon 5 (Dedicated
    # Mid-Inclination Rideshare)"), and 8 languages' worth of them still adds up.
    NAME_CHUNK_SIZE = 15
    name_chunks = [names_to_translate[i:i + NAME_CHUNK_SIZE] for i in range(0, len(names_to_translate), NAME_CHUNK_SIZE)]

    for chunk in name_chunks:
        merged_by_name = {}
        for n in chunk:
            existing = names_store.get(name_hash(n))
            merged_by_name[n] = dict(existing) if isinstance(existing, dict) else {}

        for batch in LANG_BATCHES:
            names_needing_batch = [n for n in chunk if not name_langs_complete(merged_by_name[n], batch)]
            if not names_needing_batch:
                continue
            try:
                result = call_gemini_name_batch(api_key, names_needing_batch, batch)
                for lang, name_map in result.items():
                    for n, translated_name in name_map.items():
                        if n in merged_by_name:
                            merged_by_name[n][lang] = translated_name
                # Same pacing floor as the description loop above - see GEMINI_CALL_PACING_SECONDS.
                time.sleep(GEMINI_CALL_PACING_SECONDS)
            except GeminiAuthError as e:
                print(f"Error: {e}", file=sys.stderr)
                for n in chunk:
                    if merged_by_name.get(n):
                        names_store[name_hash(n)] = merged_by_name[n]
                store["names"] = names_store
                write_output(store)
                return 1
            except GeminiQuotaExceededError as e:
                print(f"{e} - stopping this run, the next scheduled run will resume once the quota resets.", file=sys.stderr)
                for n in chunk:
                    if merged_by_name.get(n):
                        names_store[name_hash(n)] = merged_by_name[n]
                store["names"] = names_store
                write_output(store)
                return 0
            except Exception as e:
                print(f"Warning: skipped name-batch languages {','.join(batch)}: {e}", file=sys.stderr)
                time.sleep(GEMINI_CALL_PACING_SECONDS)

        for n in chunk:
            if merged_by_name.get(n):
                names_store[name_hash(n)] = merged_by_name[n]
                if name_langs_complete(merged_by_name[n], list(LANG_NAMES)):
                    name_translated += 1
                else:
                    name_failed += 1
            else:
                name_failed += 1

    store["names"] = names_store
    print(
        f"Names: new={name_translated} reused={name_reused} failed={name_failed} "
        f"total_names={len(names_store)}"
    )

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
                # See GEMINI_CALL_PACING_SECONDS's own comment - this has to stay at or above
                # the account's real per-model RPM ceiling, not just be "conservative".
                time.sleep(GEMINI_CALL_PACING_SECONDS)
            except GeminiAuthError as e:
                print(f"Error: {e}", file=sys.stderr)
                store["entries"] = entries
                write_output(store)
                return 1
            except GeminiQuotaExceededError as e:
                print(f"{e} - stopping this run, the next scheduled run will resume once the quota resets.", file=sys.stderr)
                store["entries"] = entries
                write_output(store)
                return 0
            except Exception as e:
                batch_ok = False
                print(f"Warning: skipped {name} languages {','.join(batch)}: {e}", file=sys.stderr)
                time.sleep(GEMINI_CALL_PACING_SECONDS)

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

    # Update-log comments (#modal-preflight-status-tile in index.html) - Starship launches only,
    # generic "Added launch." placeholder already excluded (see extract_update_comments). Same
    # dedup-by-hash / chunk-and-reuse shape as the names pass just above, since both maps are
    # {hash: {lang: string}} - a comment shared verbatim across launches (rare, but e.g. "GO for
    # launch." could repeat) is only ever translated once.
    update_translated = 0
    update_reused = 0
    update_failed = 0
    seen_comments = set()
    comments_to_translate = []
    for launch in launches:
        for comment in extract_update_comments(launch):
            if comment in seen_comments:
                continue
            seen_comments.add(comment)
            existing_update_entry = updates_store.get(name_hash(comment))
            if not isinstance(existing_update_entry, dict):
                existing_update_entry = {}
            if name_langs_complete(existing_update_entry, list(LANG_NAMES)):
                update_reused += 1
                continue
            comments_to_translate.append(comment)

    # Same chunk size reasoning as NAME_CHUNK_SIZE above - keeps each response comfortably under
    # Gemini's output budget across every language in a batch.
    UPDATE_CHUNK_SIZE = 15
    update_chunks = [
        comments_to_translate[i:i + UPDATE_CHUNK_SIZE]
        for i in range(0, len(comments_to_translate), UPDATE_CHUNK_SIZE)
    ]

    for chunk in update_chunks:
        merged_by_comment = {}
        for c in chunk:
            existing = updates_store.get(name_hash(c))
            merged_by_comment[c] = dict(existing) if isinstance(existing, dict) else {}

        for batch in LANG_BATCHES:
            comments_needing_batch = [c for c in chunk if not name_langs_complete(merged_by_comment[c], batch)]
            if not comments_needing_batch:
                continue
            try:
                result = call_gemini_update_batch(api_key, comments_needing_batch, batch)
                for lang, comment_map in result.items():
                    for c, translated_comment in comment_map.items():
                        if c in merged_by_comment:
                            merged_by_comment[c][lang] = translated_comment
                time.sleep(GEMINI_CALL_PACING_SECONDS)
            except GeminiAuthError as e:
                print(f"Error: {e}", file=sys.stderr)
                for c in chunk:
                    if merged_by_comment.get(c):
                        updates_store[name_hash(c)] = merged_by_comment[c]
                store["updates"] = updates_store
                write_output(store)
                return 1
            except GeminiQuotaExceededError as e:
                print(f"{e} - stopping this run, the next scheduled run will resume once the quota resets.", file=sys.stderr)
                for c in chunk:
                    if merged_by_comment.get(c):
                        updates_store[name_hash(c)] = merged_by_comment[c]
                store["updates"] = updates_store
                write_output(store)
                return 0
            except Exception as e:
                print(f"Warning: skipped update-batch languages {','.join(batch)}: {e}", file=sys.stderr)
                time.sleep(GEMINI_CALL_PACING_SECONDS)

        for c in chunk:
            if merged_by_comment.get(c):
                updates_store[name_hash(c)] = merged_by_comment[c]
                if name_langs_complete(merged_by_comment[c], list(LANG_NAMES)):
                    update_translated += 1
                else:
                    update_failed += 1
            else:
                update_failed += 1

    store["updates"] = updates_store
    print(
        f"Updates: new={update_translated} reused={update_reused} failed={update_failed} "
        f"total_updates={len(updates_store)}"
    )

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
