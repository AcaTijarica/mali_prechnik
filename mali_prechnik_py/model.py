from __future__ import annotations

import json
import os
import re
import shutil
import sqlite3
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


SOURCE_PROJECT_ROOT = Path(__file__).resolve().parents[1]
IS_FROZEN = bool(getattr(sys, "frozen", False))
BUNDLE_ROOT = Path(getattr(sys, "_MEIPASS", SOURCE_PROJECT_ROOT))
PROJECT_ROOT = Path(sys.executable).resolve().parent if IS_FROZEN else SOURCE_PROJECT_ROOT

if sys.platform == "win32":
    USER_DATA_ROOT = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local")) / "MaliPrechnik"
else:
    USER_DATA_ROOT = Path.home() / ".mali_precnik"

BUNDLED_OFFICIAL_JSON = BUNDLE_ROOT / "database" / "input" / "official_entries.json"
DEFAULT_OFFICIAL_JSON = (
    USER_DATA_ROOT / "official_entries.json"
    if IS_FROZEN
    else SOURCE_PROJECT_ROOT / "database" / "input" / "official_entries.json"
)
BUILD_SEED_SCRIPT = PROJECT_ROOT / "database" / "scripts" / "build_seed_database.py"
BUILD_PDF_SCRIPT = PROJECT_ROOT / "database" / "scripts" / "build_a5_pdf.py"
APP_ICON_PATH = (
    BUNDLE_ROOT / "assets" / "ic_launcher_prechnik.png"
    if IS_FROZEN
    else SOURCE_PROJECT_ROOT / "app" / "src" / "main" / "res" / "drawable-xxxhdpi" / "ic_launcher_prechnik.png"
)

JSON_FORMAT = "mali-precnik"
JSON_VERSION = 7

SERBIAN_CYRILLIC_ORDER = "абвгдђежзијклљмнњопрстћуфхцчџш"
RUSSIAN_CYRILLIC_AFTER_E = "ё"
RUSSIAN_CYRILLIC_AFTER_I = "й"
RUSSIAN_CYRILLIC_EXTRA_ORDER = "щыэюя"
CYRILLIC_SORT_ORDER = (
    SERBIAN_CYRILLIC_ORDER
    .replace("е", f"е{RUSSIAN_CYRILLIC_AFTER_E}", 1)
    .replace("и", f"и{RUSSIAN_CYRILLIC_AFTER_I}", 1)
    + RUSSIAN_CYRILLIC_EXTRA_ORDER
)
SERBIAN_ORDER_INDEX = {letter: index for index, letter in enumerate(CYRILLIC_SORT_ORDER)}


def ensure_default_official_json() -> Path:
    """Копира уграђено складиште у трајну корисничку фасциклу при првом покретању EXE-а."""
    if DEFAULT_OFFICIAL_JSON.exists():
        return DEFAULT_OFFICIAL_JSON
    if not BUNDLED_OFFICIAL_JSON.exists():
        raise FileNotFoundError(f"Није пронађено почетно складиште речи: {BUNDLED_OFFICIAL_JSON}")
    DEFAULT_OFFICIAL_JSON.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(BUNDLED_OFFICIAL_JSON, DEFAULT_OFFICIAL_JSON)
    return DEFAULT_OFFICIAL_JSON


@dataclass(frozen=True)
class Option:
    replacement_word: str
    explanation: str = ""
    weight: int = 1


@dataclass(frozen=True)
class Entry:
    foreign_word: str
    origin: str = ""
    addendum: str = ""
    options: list[Option] | None = None

    def normalized_options(self) -> list[Option]:
        return self.options or []


@dataclass(frozen=True)
class OldWord:
    old_word: str
    addendum: str = ""
    synonyms: list[str] | None = None

    def normalized_synonyms(self) -> list[str]:
        return self.synonyms or []


def clean_text(value: Any) -> str:
    """Сређује празнине, али чува намерне преломе редова у дужем тексту."""
    if value is None:
        return ""
    lines = [" ".join(line.strip().split()) for line in str(value).replace("\r\n", "\n").split("\n")]
    return "\n".join(line for line in lines if line).strip()


def normalize_word(value: str) -> str:
    return clean_text(value).casefold()


def read_weight(raw: Any) -> int:
    if not isinstance(raw, dict):
        return 1
    value = raw.get("tezina") or raw.get("тежина") or raw.get("weight") or 1
    try:
        return max(1, int(value))
    except (TypeError, ValueError):
        return 1


def foreign_word_with_initial_capital(value: str) -> str:
    """Претвара само прво слово туђице у велико, без мењања остатка текста."""
    cleaned = clean_text(value)
    if not cleaned:
        return ""
    return cleaned[0].upper() + cleaned[1:]


def serbian_sort_key(value: str) -> tuple[list[int], str]:
    lowered = value.strip().casefold()
    return ([SERBIAN_ORDER_INDEX.get(char, 1000 + ord(char)) for char in lowered], lowered)


def initial_letter(value: str) -> str:
    for char in value.strip().casefold():
        if char in SERBIAN_ORDER_INDEX:
            return char.upper()
    return "#"


def unique_options(options: list[Option]) -> list[Option]:
    seen: set[str] = set()
    result: list[Option] = []
    for option in options:
        replacement_word = clean_text(option.replacement_word)
        key = normalize_word(replacement_word)
        if not key or key in seen:
            continue
        seen.add(key)
        result.append(
            Option(
                replacement_word=replacement_word,
                explanation=clean_text(option.explanation),
                weight=max(1, int(option.weight or 1)),
            )
        )
    return sorted(result, key=lambda item: (item.weight, serbian_sort_key(item.replacement_word)))


def normalize_entries(entries: list[Entry]) -> list[Entry]:
    """Спаја дупле туђице, чисти празна поља и сортира по српској ћирилици."""
    grouped: dict[str, Entry] = {}
    for entry in entries:
        foreign_word = foreign_word_with_initial_capital(entry.foreign_word)
        key = normalize_word(foreign_word)
        if not key:
            continue

        old = grouped.get(key)
        old_options = old.normalized_options() if old else []
        merged_options = old_options + entry.normalized_options()
        grouped[key] = Entry(
            foreign_word=old.foreign_word if old else foreign_word,
            origin=old.origin if old and old.origin else clean_text(entry.origin),
            addendum=old.addendum if old and old.addendum else clean_text(entry.addendum),
            options=unique_options(merged_options),
        )

    return [
        entry
        for entry in sorted(grouped.values(), key=lambda item: serbian_sort_key(item.foreign_word))
    ]


def normalize_old_words(old_words: list[OldWord]) -> list[OldWord]:
    """Spaja duple stare reci, cisti praznine i cuva redosled slicnoznacnica."""
    grouped: dict[str, OldWord] = {}
    for old_word in old_words:
        cleaned_word = clean_text(old_word.old_word)
        key = normalize_word(cleaned_word)
        if not key:
            continue

        existing = grouped.get(key)
        synonyms: list[str] = []
        seen_synonyms: set[str] = set()
        for synonym in (existing.normalized_synonyms() if existing else []) + old_word.normalized_synonyms():
            cleaned_synonym = clean_text(synonym)
            synonym_key = normalize_word(cleaned_synonym)
            if synonym_key and synonym_key not in seen_synonyms:
                seen_synonyms.add(synonym_key)
                synonyms.append(cleaned_synonym)

        grouped[key] = OldWord(
            old_word=existing.old_word if existing else cleaned_word,
            addendum=existing.addendum if existing and existing.addendum else clean_text(old_word.addendum),
            synonyms=synonyms,
        )

    return [
        old_word
        for old_word in sorted(grouped.values(), key=lambda item: serbian_sort_key(item.old_word))
        if old_word.normalized_synonyms()
    ]


def read_entries_from_json(path: Path) -> list[Entry]:
    payload = load_json_with_small_repairs(path)
    raw_entries = (
        payload.get("entries")
        or payload.get("tudjice")
        or payload
        if isinstance(payload, dict)
        else payload
    )

    entries: list[Entry] = []
    for raw_entry in raw_entries:
        foreign_word = clean_text(raw_entry.get("tudjica") or raw_entry.get("foreign_word"))
        if not foreign_word:
            continue

        options: list[Option] = []
        raw_options = raw_entry.get("predlozi") or raw_entry.get("options") or []
        for raw_option in raw_options:
            replacement_word = clean_text(raw_option.get("srpskoslovenski") or raw_option.get("replacement_word"))
            if replacement_word:
                options.append(
                    Option(
                        replacement_word=replacement_word,
                        explanation=clean_text(raw_option.get("pojasnjenje") or raw_option.get("explanation")),
                        weight=read_weight(raw_option),
                    )
                )

        entries.append(
            Entry(
                foreign_word=foreign_word,
                origin=clean_text(raw_entry.get("poreklo") or raw_entry.get("origin")),
                addendum=clean_text(raw_entry.get("dodatak") or raw_entry.get("addendum")),
                options=options,
            )
        )
    return normalize_entries(entries)


def read_old_words_from_json(path: Path) -> list[OldWord]:
    payload = load_json_with_small_repairs(path)
    if not isinstance(payload, dict):
        return []

    old_words: list[OldWord] = []
    raw_old_words = payload.get("stare_reci") or payload.get("old_words") or []
    for raw_old_word in raw_old_words:
        old_word = clean_text(raw_old_word.get("stara_rec") or raw_old_word.get("old_word"))
        if not old_word:
            continue

        raw_synonyms = raw_old_word.get("slicnoznacnice") or raw_old_word.get("synonyms") or []
        if isinstance(raw_synonyms, str):
            raw_synonyms = re.split(r"[,;\n]+", raw_synonyms)

        synonyms = [clean_text(value) for value in raw_synonyms if clean_text(value)]
        old_words.append(
            OldWord(
                old_word=old_word,
                addendum=clean_text(raw_old_word.get("dodatak") or raw_old_word.get("addendum")),
                synonyms=synonyms,
            )
        )
    return normalize_old_words(old_words)


def load_json_with_small_repairs(path: Path) -> Any:
    """Чита JSON, уз поправку честе ручне грешке: сувишан зарез пре `]` или `}`."""
    text = path.read_text(encoding="utf-8-sig")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        repaired_text = re.sub(r",(\s*[\]}])", r"\1", text)
        if repaired_text == text:
            raise
        return json.loads(repaired_text)


def read_entries_from_sqlite(path: Path) -> list[Entry]:
    with sqlite3.connect(path) as connection:
        connection.row_factory = sqlite3.Row
        rows = connection.execute(
            """
            SELECT id, word, origin, addendum
            FROM foreign_terms
            ORDER BY word COLLATE NOCASE
            """
        ).fetchall()

        entries: list[Entry] = []
        option_columns = {
            row["name"]
            for row in connection.execute("PRAGMA table_info(replacement_options)").fetchall()
        }
        weight_column = "weight" if "weight" in option_columns else "1 AS weight"
        for row in rows:
            option_rows = connection.execute(
                f"""
                SELECT replacement_word, explanation, {weight_column}
                FROM replacement_options
                WHERE foreign_term_id = ?
                ORDER BY weight ASC, replacement_word COLLATE NOCASE
                """,
                (row["id"],),
            ).fetchall()
            entries.append(
                Entry(
                    foreign_word=clean_text(row["word"]),
                    origin=clean_text(row["origin"]),
                    addendum=clean_text(row["addendum"]),
                    options=[
                        Option(
                            replacement_word=clean_text(option["replacement_word"]),
                            explanation=clean_text(option["explanation"]),
                            weight=max(1, int(option["weight"] or 1)),
                        )
                        for option in option_rows
                    ],
                )
            )
    return normalize_entries(entries)


def sqlite_table_exists(connection: sqlite3.Connection, table_name: str) -> bool:
    row = connection.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
        (table_name,),
    ).fetchone()
    return row is not None


def read_old_words_from_sqlite(path: Path) -> list[OldWord]:
    with sqlite3.connect(path) as connection:
        connection.row_factory = sqlite3.Row
        if not sqlite_table_exists(connection, "old_words"):
            return []

        rows = connection.execute(
            """
            SELECT id, word, addendum
            FROM old_words
            ORDER BY word COLLATE NOCASE
            """
        ).fetchall()

        old_words: list[OldWord] = []
        has_synonyms_table = sqlite_table_exists(connection, "old_word_synonyms")
        for row in rows:
            synonyms: list[str] = []
            if has_synonyms_table:
                synonym_rows = connection.execute(
                    """
                    SELECT synonym
                    FROM old_word_synonyms
                    WHERE old_word_id = ?
                    ORDER BY position ASC, synonym COLLATE NOCASE
                    """,
                    (row["id"],),
                ).fetchall()
                synonyms = [clean_text(synonym_row["synonym"]) for synonym_row in synonym_rows]

            old_words.append(
                OldWord(
                    old_word=clean_text(row["word"]),
                    addendum=clean_text(row["addendum"]),
                    synonyms=synonyms,
                )
            )
    return normalize_old_words(old_words)


def read_entries(path: Path) -> list[Entry]:
    suffix = path.suffix.casefold()
    if suffix == ".json":
        return read_entries_from_json(path)
    if suffix == ".db":
        return read_entries_from_sqlite(path)
    raise ValueError(f"Неподржан формат: {path}. Подржани су .json и .db.")


def read_storage(path: Path) -> tuple[list[Entry], list[OldWord]]:
    suffix = path.suffix.casefold()
    if suffix == ".json":
        return read_entries_from_json(path), read_old_words_from_json(path)
    if suffix == ".db":
        return read_entries_from_sqlite(path), read_old_words_from_sqlite(path)
    raise ValueError(f"Неподржан формат: {path}. Подржани су .json и .db.")


def write_entries_to_json(entries: list[Entry], path: Path, old_words: list[OldWord] | None = None) -> None:
    normalized_entries = normalize_entries(entries)
    normalized_old_words = normalize_old_words(old_words or [])
    payload = {
        "format": JSON_FORMAT,
        "version": JSON_VERSION,
        "storage": "sqlite-relational",
        "entries": [
            {
                "id": entry_index,
                "tudjica": entry.foreign_word,
                "poreklo": entry.origin,
                "dodatak": entry.addendum,
                "predlozi": [
                    {
                        "srpskoslovenski": option.replacement_word,
                        "pojasnjenje": option.explanation,
                        "tezina": option.weight,
                    }
                    for option in entry.normalized_options()
                ],
            }
            for entry_index, entry in enumerate(normalized_entries, start=1)
        ],
        "stare_reci": [
            {
                "id": old_word_index,
                "stara_rec": old_word.old_word,
                "dodatak": old_word.addendum,
                "slicnoznacnice": old_word.normalized_synonyms(),
            }
            for old_word_index, old_word in enumerate(normalized_old_words, start=1)
        ],
    }

    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_suffix(path.suffix + ".tmp")
    temporary_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary_path.replace(path)


def find_entry_index(entries: list[Entry], foreign_word: str) -> int | None:
    key = normalize_word(foreign_word)
    for index, entry in enumerate(entries):
        if normalize_word(entry.foreign_word) == key:
            return index
    return None


def find_old_word_index(old_words: list[OldWord], old_word: str) -> int | None:
    key = normalize_word(old_word)
    for index, item in enumerate(old_words):
        if normalize_word(item.old_word) == key:
            return index
    return None


def entry_count_text(entries: list[Entry], old_words: list[OldWord] | None = None) -> str:
    option_count = sum(len(entry.normalized_options()) for entry in entries)
    if old_words is None:
        return f"Туђица: {len(entries)} | Предлога: {option_count}"
    return f"Туђица: {len(entries)} | Предлога: {option_count} | Старих речи: {len(old_words)}"
