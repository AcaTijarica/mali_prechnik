#!/usr/bin/env python3
"""
Извози изгласане Firestore предлоге у Markdown и JSON за службени преглед.

Ова скрипта је намењена одржавању базе. Повлачи само текстуалне предлоге који
су већ уреднички одобрени (`status = approved`) и имају најмање задати број
гласова. Извештај је помоћ уреднику: предлози се и даље ручно претачу у
`database/input/official_entries.json`.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from fetch_firestore_proposals import initialize_firestore, read_groups


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_JSON_OUTPUT = PROJECT_ROOT / "database" / "input" / "firestore_voted_for_official_review.json"
DEFAULT_REPORT_OUTPUT = PROJECT_ROOT / "database" / "generated" / "voted_proposals.md"


def write_voted_json(groups, path: Path, min_votes: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "format": "mali-precnik-firestore-voted-review",
        "version": 2,
        "proposal_format": "text",
        "min_votes": min_votes,
        "groups": [
            {
                "id": group.id,
                "tudjica": group.foreign_word,
                "pocetno_slovo": group.first_letter,
                "poreklo": group.origin,
                "dodatak": group.addendum,
                "status": group.status,
                "vrsta": group.proposal_type,
                "predlozi": [
                    {
                        "id": option.id,
                        "tekst": option.proposal_text,
                        "status": option.status,
                        "autor": option.created_by,
                        "glasova": option.votes_count,
                        "usvojiti": False,
                    }
                    for option in group.options
                ],
            }
            for group in groups
        ],
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_voted_report(groups, path: Path, min_votes: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# Изгласани предлози за службени преглед",
        "",
        f"У извештају су предлози са статусом `approved` и најмање {min_votes} гласова.",
        "Ово није аутоматско усвајање: уредник ручно претаче прихваћене идеје у службену базу.",
        "",
    ]

    if not groups:
        lines.append("Нема изгласаних предлога који испуњавају задати праг.")
    else:
        for group in groups:
            lines.extend(
                [
                    f"## {group.foreign_word}",
                    "",
                    f"- Firestore group id: `{group.id}`",
                    f"- Врста: {group.proposal_type or 'није наведено'}",
                    f"- Порекло у групи: {group.origin or 'није наведено'}",
                    "",
                ]
            )
            if group.addendum:
                lines.extend(["**Додатак у групи**", "", group.addendum, ""])

            for option in group.options:
                lines.extend(
                    [
                        f"### Предлог `{option.id}`",
                        "",
                        f"- Гласова: {option.votes_count}",
                        f"- Статус: {option.status}",
                        f"- Аутор: `{option.created_by or 'није наведен'}`",
                        "",
                        "**Текст предлога**",
                        "",
                        option.proposal_text,
                        "",
                    ]
                )

    path.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Извози approved Firestore предлоге који имају довољно гласова.")
    parser.add_argument(
        "--service-account",
        type=Path,
        required=True,
        help="Путања до Firebase service account JSON фајла.",
    )
    parser.add_argument("--min-votes", type=int, default=50, help="Најмањи број гласова. Подразумевано: 50.")
    parser.add_argument("--json-out", type=Path, default=DEFAULT_JSON_OUTPUT, help=f"JSON излаз: {DEFAULT_JSON_OUTPUT}")
    parser.add_argument("--report-out", type=Path, default=DEFAULT_REPORT_OUTPUT, help=f"Markdown извештај: {DEFAULT_REPORT_OUTPUT}")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.service_account.exists():
        raise FileNotFoundError(f"Service account JSON не постоји: {args.service_account}")

    client = initialize_firestore(args.service_account)
    groups = read_groups(
        client,
        min_votes=args.min_votes,
        include_all=False,
        option_status="approved",
        include_all_statuses=False,
    )
    write_voted_json(groups, args.json_out, min_votes=args.min_votes)
    write_voted_report(groups, args.report_out, min_votes=args.min_votes)

    option_count = sum(len(group.options) for group in groups)
    print(f"Туђица за службени преглед: {len(groups)}")
    print(f"Изгласаних предлога: {option_count}")
    print(f"JSON: {args.json_out}")
    print(f"Извештај: {args.report_out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
