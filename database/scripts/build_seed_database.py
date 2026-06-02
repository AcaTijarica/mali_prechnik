#!/usr/bin/env python3
"""
Гради службену seed базу за Android апликацију.

Основни улаз је `database/input/official_entries.json`. Тај фајл представља
уреднички прихваћену базу: туђице су већ прегледане, а свака туђица има листу
засебних српскословенских предлога. Firestore предлози не улазе аутоматски у
службену базу; прво се прегледају кроз извештај који прави
`fetch_firestore_proposals.py`, па се тек ручно прихваћени предлози препишу у
овај службени JSON.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import shutil
import sqlite3
import sys
import time
from dataclasses import dataclass
from pathlib import Path


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INPUT = PROJECT_ROOT / "database" / "input" / "official_entries.json"
DEFAULT_OUTPUT_DIR = PROJECT_ROOT / "database" / "generated"
ANDROID_ASSETS_DIR = PROJECT_ROOT / "app" / "src" / "main" / "assets"

JSON_FORMAT = "mali-precnik"
JSON_VERSION = 6
DATABASE_VERSION = 7

SCHEMA_SQL = """
CREATE TABLE foreign_terms (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    word TEXT NOT NULL,
    normalized_word TEXT NOT NULL UNIQUE,
    origin TEXT NOT NULL DEFAULT '',
    addendum TEXT NOT NULL DEFAULT '',
    updated_at INTEGER NOT NULL
);

CREATE TABLE replacement_options (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    foreign_term_id INTEGER NOT NULL,
    replacement_word TEXT NOT NULL,
    normalized_replacement_word TEXT NOT NULL,
    explanation TEXT NOT NULL DEFAULT '',
    weight INTEGER NOT NULL DEFAULT 1,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY(foreign_term_id)
        REFERENCES foreign_terms(id)
        ON DELETE CASCADE,
    UNIQUE(foreign_term_id, normalized_replacement_word)
);

CREATE INDEX index_foreign_terms_word ON foreign_terms(word);
CREATE INDEX index_replacement_options_word ON replacement_options(replacement_word);
CREATE INDEX index_replacement_options_normalized_word
    ON replacement_options(normalized_replacement_word);
"""

SERBIAN_CYRILLIC_ORDER = "абвгдђежзијклљмнњопрстћуфхцчџш"
SERBIAN_ORDER_INDEX = {letter: index for index, letter in enumerate(SERBIAN_CYRILLIC_ORDER)}


@dataclass(frozen=True)
class Option:
    replacement_word: str
    explanation: str
    weight: int = 1


@dataclass(frozen=True)
class Entry:
    id: int
    foreign_word: str
    origin: str
    addendum: str
    options: list[Option]


def clean_text(value: str | None) -> str:
    """Сређује празнине, али чува намерне преломе редова у дужим појашњењима."""
    if value is None:
        return ""
    lines = [" ".join(line.strip().split()) for line in str(value).replace("\r\n", "\n").split("\n")]
    return "\n".join(line for line in lines if line).strip()


def normalize_word(value: str) -> str:
    return clean_text(value).casefold()


def read_weight(raw: object) -> int:
    """Чита тежину предлога из JSON/CSV реда, уз подразумевану вредност 1."""
    if not isinstance(raw, dict):
        return 1
    value = raw.get("tezina") or raw.get("тежина") or raw.get("weight") or 1
    try:
        return max(1, int(value))
    except (TypeError, ValueError):
        return 1


def serbian_sort_key(value: str) -> tuple[list[int], str]:
    lowered = value.strip().casefold()
    return ([SERBIAN_ORDER_INDEX.get(char, 1000 + ord(char)) for char in lowered], lowered)


def unique_options(options: list[Option]) -> list[Option]:
    seen: set[str] = set()
    result: list[Option] = []
    for option in options:
        key = normalize_word(option.replacement_word)
        if not key or key in seen:
            continue
        seen.add(key)
        result.append(
            Option(
                replacement_word=clean_text(option.replacement_word),
                explanation=clean_text(option.explanation),
                weight=max(1, int(option.weight or 1)),
            )
        )
    return sorted(result, key=lambda item: (item.weight, serbian_sort_key(item.replacement_word)))


def normalize_entries(entries: list[Entry]) -> list[Entry]:
    groups: dict[str, Entry] = {}
    for entry in entries:
        foreign_word = clean_text(entry.foreign_word)
        key = normalize_word(foreign_word)
        if not key:
            continue

        existing = groups.get(key)
        merged_options = (existing.options if existing else []) + entry.options
        groups[key] = Entry(
            id=0,
            foreign_word=existing.foreign_word if existing else foreign_word,
            origin=(existing.origin if existing and existing.origin else clean_text(entry.origin)),
            addendum=(existing.addendum if existing and existing.addendum else clean_text(entry.addendum)),
            options=unique_options(merged_options),
        )

    result: list[Entry] = []
    for entry in sorted(groups.values(), key=lambda item: serbian_sort_key(item.foreign_word)):
        if not entry.options:
            continue
        result.append(
            Entry(
                id=len(result) + 1,
                foreign_word=entry.foreign_word,
                origin=entry.origin,
                addendum=entry.addendum,
                options=[
                    Option(
                        replacement_word=option.replacement_word,
                        explanation=option.explanation,
                        weight=option.weight,
                    )
                    for option in entry.options
                ],
            )
        )
    return result


def load_json_with_small_repairs(path: Path) -> object:
    """Чита JSON, уз поправку честе ручне грешке: сувишан зарез пре `]` или `}`."""
    text = path.read_text(encoding="utf-8-sig")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        repaired_text = re.sub(r",(\s*[\]}])", r"\1", text)
        if repaired_text == text:
            raise
        return json.loads(repaired_text)


def read_entries_from_json(path: Path) -> list[Entry]:
    payload = load_json_with_small_repairs(path)
    raw_entries = payload.get("entries", payload) if isinstance(payload, dict) else payload

    entries: list[Entry] = []
    for raw_entry in raw_entries:
        foreign_word = clean_text(raw_entry.get("tudjica") or raw_entry.get("foreign_word"))
        if not foreign_word:
            continue
        origin = clean_text(raw_entry.get("poreklo") or raw_entry.get("origin"))
        addendum = clean_text(raw_entry.get("dodatak") or raw_entry.get("addendum"))

        raw_options = raw_entry.get("predlozi") or raw_entry.get("resenja") or raw_entry.get("options")
        options: list[Option] = []
        if raw_options:
            for raw_option in raw_options:
                replacement_word = clean_text(
                    raw_option.get("srpskoslovenski") or raw_option.get("replacement_word")
                )
                if not replacement_word:
                    continue
                options.append(
                    Option(
                        replacement_word=replacement_word,
                        explanation=clean_text(
                            raw_option.get("pojasnjenje") or raw_option.get("explanation")
                        ),
                        weight=read_weight(raw_option),
                    )
                )
        else:
            replacement_word = clean_text(raw_entry.get("srpskoslovenski") or raw_entry.get("replacement_words"))
            if replacement_word:
                options.append(
                    Option(
                        replacement_word=replacement_word,
                        explanation=clean_text(raw_entry.get("pojasnjenje") or raw_entry.get("description")),
                        weight=read_weight(raw_entry),
                    )
                )

        entries.append(Entry(id=0, foreign_word=foreign_word, origin=origin, addendum=addendum, options=options))
    return normalize_entries(entries)


def read_entries_from_csv(path: Path) -> list[Entry]:
    entries: list[Entry] = []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            foreign_word = clean_text(row.get("tudjica") or row.get("туђица"))
            replacement_word = clean_text(row.get("srpskoslovenski") or row.get("српскословенски"))
            if not foreign_word or not replacement_word:
                continue
            entries.append(
                Entry(
                    id=0,
                    foreign_word=foreign_word,
                    origin=clean_text(row.get("poreklo") or row.get("порекло") or row.get("origin")),
                    addendum=clean_text(row.get("dodatak") or row.get("додатак") or row.get("addendum")),
                    options=[
                        Option(
                            replacement_word=replacement_word,
                            explanation=clean_text(row.get("pojasnjenje") or row.get("појашњење")),
                            weight=read_weight(row),
                        )
                    ],
                )
            )
    return normalize_entries(entries)


def read_entries(path: Path) -> list[Entry]:
    if path.suffix.casefold() == ".csv":
        return read_entries_from_csv(path)
    return read_entries_from_json(path)


def write_json(entries: list[Entry], json_path: Path) -> None:
    payload = {
        "format": JSON_FORMAT,
        "version": JSON_VERSION,
        "storage": "sqlite-relational",
        "entries": [
            {
                "id": entry.id,
                "tudjica": entry.foreign_word,
                "poreklo": entry.origin,
                "dodatak": entry.addendum,
                "predlozi": [
                    {
                        "srpskoslovenski": option.replacement_word,
                        "pojasnjenje": option.explanation,
                        "tezina": option.weight,
                    }
                    for option in entry.options
                ],
            }
            for entry in entries
        ],
    }
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_sqlite(entries: list[Entry], db_path: Path) -> None:
    if db_path.exists():
        db_path.unlink()

    timestamp = int(time.time() * 1000)
    with sqlite3.connect(db_path) as connection:
        connection.execute("PRAGMA foreign_keys = ON")
        connection.executescript(SCHEMA_SQL)
        connection.execute(f"PRAGMA user_version = {DATABASE_VERSION}")

        for entry in entries:
            cursor = connection.execute(
                """
                INSERT INTO foreign_terms (id, word, normalized_word, origin, addendum, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    entry.id,
                    entry.foreign_word,
                    normalize_word(entry.foreign_word),
                    entry.origin,
                    entry.addendum,
                    timestamp,
                ),
            )
            entry_id = cursor.lastrowid
            connection.executemany(
                """
                INSERT INTO replacement_options (
                    foreign_term_id,
                    replacement_word,
                    normalized_replacement_word,
                    explanation,
                    weight,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                [
                    (
                        entry_id,
                        option.replacement_word,
                        normalize_word(option.replacement_word),
                        option.explanation,
                        option.weight,
                        timestamp,
                    )
                    for option in entry.options
                ],
            )
        connection.commit()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Прави prechnik_seed.db/json из службеног JSON/CSV улаза.")
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT, help=f"Улаз. Подразумевано: {DEFAULT_INPUT}")
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUTPUT_DIR, help=f"Излазни директоријум: {DEFAULT_OUTPUT_DIR}")
    parser.add_argument(
        "--copy-to-android-assets",
        action="store_true",
        help="Копира направљене prechnik_seed.db/json у app/src/main/assets.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.input.exists():
        raise FileNotFoundError(f"Улазни фајл не постоји: {args.input}")

    args.out_dir.mkdir(parents=True, exist_ok=True)
    entries = read_entries(args.input)

    db_path = args.out_dir / "prechnik_seed.db"
    json_path = args.out_dir / "prechnik_seed.json"
    write_sqlite(entries, db_path)
    write_json(entries, json_path)

    if args.copy_to_android_assets:
        ANDROID_ASSETS_DIR.mkdir(parents=True, exist_ok=True)
        shutil.copy2(db_path, ANDROID_ASSETS_DIR / db_path.name)
        shutil.copy2(json_path, ANDROID_ASSETS_DIR / json_path.name)

    option_count = sum(len(entry.options) for entry in entries)
    print(f"Туђица: {len(entries)}")
    print(f"Српскословенских предлога: {option_count}")
    print(f"SQLite: {db_path}")
    print(f"JSON: {json_path}")
    if args.copy_to_android_assets:
        print(f"Копирано у Android assets: {ANDROID_ASSETS_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
