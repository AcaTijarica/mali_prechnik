#!/usr/bin/env python3
"""
Спаја ручно прегледане Firestore предлоге у службени `official_entries.json`.

Ток рада је:
1. `fetch_firestore_proposals.py` направи `firestore_ready_for_review.json`.
2. Уредник ручно прегледа текстуалне предлоге и, ако жели аутоматско спајање,
   ручно их претвори у конкретна поља `srpskoslovenski` и `pojasnjenje`.
3. Ова скрипта прочита такав очишћени JSON и допише прихваћене конкретне речи у
   службену базу.

Слободан `tekst` предлога се не претаче аутоматски у службену базу. Скрипта не
прави дупликате: ако туђица већ постоји, само додаје нове предлоге; ако исти
предлог већ постоји за исту туђицу, прескаче га.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OFFICIAL = PROJECT_ROOT / "database" / "input" / "official_entries.json"
DEFAULT_REVIEWED = PROJECT_ROOT / "database" / "input" / "firestore_ready_for_review.json"

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


@dataclass(frozen=True)
class Option:
    replacement_word: str
    explanation: str
    weight: int = 1


@dataclass(frozen=True)
class Entry:
    foreign_word: str
    origin: str
    addendum: str
    options: list[Option]


def clean_text(value: object) -> str:
    """Сређује празнине, али оставља намерне преломе редова у појашњењу."""
    if value is None:
        return ""
    lines = [
        " ".join(line.strip().split())
        for line in str(value).replace("\r\n", "\n").split("\n")
    ]
    return "\n".join(line for line in lines if line).strip()


def normalize_word(value: str) -> str:
    return clean_text(value).casefold()


def read_weight(raw: object) -> int:
    if not isinstance(raw, dict):
        return 1
    value = raw.get("tezina") or raw.get("тежина") or raw.get("weight") or 1
    try:
        return max(1, int(value))
    except (TypeError, ValueError):
        return 1


def truthy(value: object) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().casefold() in {"true", "1", "yes", "да", "odobreno", "usvojiti"}
    return False


def serbian_sort_key(value: str) -> tuple[list[int], str]:
    lowered = value.strip().casefold()
    return ([SERBIAN_ORDER_INDEX.get(char, 1000 + ord(char)) for char in lowered], lowered)


def read_json(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def options_from_raw(raw_options: list[dict[str, object]]) -> list[Option]:
    options: list[Option] = []
    seen: set[str] = set()
    for raw_option in raw_options:
        if "odobreno" in raw_option and not truthy(raw_option.get("odobreno")):
            continue
        if "usvojiti" in raw_option and not truthy(raw_option.get("usvojiti")):
            continue
        replacement_word = clean_text(
            raw_option.get("srpskoslovenski") or raw_option.get("replacement_word")
        )
        key = normalize_word(replacement_word)
        if not key or key in seen:
            continue
        seen.add(key)
        options.append(
            Option(
                replacement_word=replacement_word,
                explanation=clean_text(raw_option.get("pojasnjenje") or raw_option.get("explanation")),
                weight=read_weight(raw_option),
            )
        )
    return options


def official_entries_from_json(path: Path) -> list[Entry]:
    payload = read_json(path)
    raw_entries = payload.get("entries", []) if isinstance(payload, dict) else payload

    entries: list[Entry] = []
    for raw_entry in raw_entries:
        foreign_word = clean_text(raw_entry.get("tudjica") or raw_entry.get("foreign_word"))
        if not foreign_word:
            continue

        raw_options = raw_entry.get("predlozi") or raw_entry.get("resenja") or raw_entry.get("options") or []
        entries.append(
            Entry(
                foreign_word=foreign_word,
                origin=clean_text(raw_entry.get("poreklo") or raw_entry.get("origin")),
                addendum=clean_text(raw_entry.get("dodatak") or raw_entry.get("addendum")),
                options=options_from_raw(raw_options),
            )
        )
    return normalize_entries(entries)


def reviewed_entries_from_json(path: Path) -> list[Entry]:
    payload = read_json(path)
    raw_groups = payload.get("groups", []) if isinstance(payload, dict) else payload

    entries: list[Entry] = []
    for raw_group in raw_groups:
        foreign_word = clean_text(raw_group.get("tudjica") or raw_group.get("foreign_word"))
        if not foreign_word:
            continue
        raw_options = raw_group.get("predlozi") or raw_group.get("resenja") or raw_group.get("options") or []
        options = options_from_raw(raw_options)
        if options:
            entries.append(
                Entry(
                    foreign_word=foreign_word,
                    origin=clean_text(raw_group.get("poreklo") or raw_group.get("origin")),
                    addendum=clean_text(raw_group.get("dodatak") or raw_group.get("addendum")),
                    options=options,
                )
            )
    return normalize_entries(entries)


def normalize_entries(entries: list[Entry]) -> list[Entry]:
    grouped: dict[str, Entry] = {}
    for entry in entries:
        foreign_word = clean_text(entry.foreign_word)
        key = normalize_word(foreign_word)
        if not key:
            continue

        existing = grouped.get(key)
        existing_options = existing.options if existing else []
        grouped[key] = Entry(
            foreign_word=existing.foreign_word if existing else foreign_word,
            origin=(existing.origin if existing and existing.origin else clean_text(entry.origin)),
            addendum=(existing.addendum if existing and existing.addendum else clean_text(entry.addendum)),
            options=unique_options(existing_options + entry.options),
        )

    return sorted(
        grouped.values(),
        key=lambda entry: serbian_sort_key(entry.foreign_word),
    )


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


def merge_entries(official_entries: list[Entry], reviewed_entries: list[Entry]) -> tuple[list[Entry], int, int]:
    before_options = sum(len(entry.options) for entry in official_entries)
    merged_entries = normalize_entries(official_entries + reviewed_entries)
    after_options = sum(len(entry.options) for entry in merged_entries)
    added_entries = max(0, len(merged_entries) - len(official_entries))
    added_options = max(0, after_options - before_options)
    return merged_entries, added_entries, added_options


def old_words_from_json(path: Path) -> list[object]:
    """
    Враћа сиров списак старих речи из службеног JSON-а.

    Ова скрипта за сада уређује само туђице и њихове предлоге. Старе речи зато
    не тумачимо нити мењамо, али их морамо дословно пренети у излаз како
    спајање Firestore предлога не би обрисало други део складишта.
    """
    payload = read_json(path)
    if not isinstance(payload, dict):
        return []
    old_words = payload.get("stare_reci", [])
    return old_words if isinstance(old_words, list) else []


def entries_to_payload(entries: list[Entry], old_words: list[object]) -> dict[str, object]:
    return {
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
                    for option in entry.options
                ],
            }
            for entry_index, entry in enumerate(entries, start=1)
        ],
        "stare_reci": old_words,
    }


def backup_file(path: Path) -> Path:
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    backup_path = path.with_suffix(path.suffix + f".bak-{stamp}")
    shutil.copy2(path, backup_path)
    return backup_path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Спаја ручно прегледане Firestore предлоге у official_entries.json."
    )
    parser.add_argument("--official", type=Path, default=DEFAULT_OFFICIAL, help=f"Службени JSON: {DEFAULT_OFFICIAL}")
    parser.add_argument("--reviewed", type=Path, default=DEFAULT_REVIEWED, help=f"Очишћени Firestore JSON: {DEFAULT_REVIEWED}")
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="Где се пише спојени JSON. Ако се не наведе, преписује --official уз backup.",
    )
    parser.add_argument("--no-backup", action="store_true", help="Не прави backup када се преписује --official.")
    parser.add_argument("--dry-run", action="store_true", help="Само приказује шта би било додато, без уписа.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.official.exists():
        raise FileNotFoundError(f"Службени JSON не постоји: {args.official}")
    if not args.reviewed.exists():
        raise FileNotFoundError(f"Очишћени Firestore JSON не постоји: {args.reviewed}")

    official_entries = official_entries_from_json(args.official)
    official_old_words = old_words_from_json(args.official)
    reviewed_entries = reviewed_entries_from_json(args.reviewed)
    merged_entries, added_entries, added_options = merge_entries(official_entries, reviewed_entries)

    output_path = args.output or args.official

    print(f"Службених туђица пре спајања: {len(official_entries)}")
    print(f"Прегледаних туђица за спајање: {len(reviewed_entries)}")
    print(f"Нових туђица: {added_entries}")
    print(f"Нових српскословенских предлога: {added_options}")
    print(f"Сачуваних старих речи: {len(official_old_words)}")

    if args.dry_run:
        print("Dry run: ништа није уписано.")
        return 0

    output_path.parent.mkdir(parents=True, exist_ok=True)
    if output_path == args.official and not args.no_backup:
        backup_path = backup_file(args.official)
        print(f"Backup: {backup_path}")

    payload = entries_to_payload(merged_entries, official_old_words)
    output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Уписано: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
