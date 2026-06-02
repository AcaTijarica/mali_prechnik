from __future__ import annotations

import json
import re
import sqlite3
from dataclasses import dataclass
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OFFICIAL_JSON = PROJECT_ROOT / "database" / "input" / "official_entries.json"
BUILD_SEED_SCRIPT = PROJECT_ROOT / "database" / "scripts" / "build_seed_database.py"
BUILD_PDF_SCRIPT = PROJECT_ROOT / "database" / "scripts" / "build_a5_pdf.py"

JSON_FORMAT = "mali-precnik"
JSON_VERSION = 6

SERBIAN_CYRILLIC_ORDER = "абвгдђежзијклљмнњопрстћуфхцчџш"
SERBIAN_ORDER_INDEX = {letter: index for index, letter in enumerate(SERBIAN_CYRILLIC_ORDER)}


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
        if entry.normalized_options()
    ]


def read_entries_from_json(path: Path) -> list[Entry]:
    payload = load_json_with_small_repairs(path)
    raw_entries = payload.get("entries", payload) if isinstance(payload, dict) else payload

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


def read_entries(path: Path) -> list[Entry]:
    suffix = path.suffix.casefold()
    if suffix == ".json":
        return read_entries_from_json(path)
    if suffix == ".db":
        return read_entries_from_sqlite(path)
    raise ValueError(f"Неподржан формат: {path}. Подржани су .json и .db.")


def write_entries_to_json(entries: list[Entry], path: Path) -> None:
    normalized_entries = normalize_entries(entries)
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


def entry_count_text(entries: list[Entry]) -> str:
    option_count = sum(len(entry.normalized_options()) for entry in entries)
    return f"Туђица: {len(entries)} | Предлога: {option_count}"
