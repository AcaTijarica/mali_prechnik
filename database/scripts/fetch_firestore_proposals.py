#!/usr/bin/env python3
"""
Преузима Firestore предлоге и прави извештај за ручни преглед.

Ова скрипта намерно не убацује предлоге директно у службену базу. Њен посао је
да извуче оно што је заједница предложила и да направи читљив `review_report.md`.
Предлози из Firestore-а су текстуални записи (`proposal_text`); уредник их потом
ручно прихвата за гласање или касније ручно претаче у службену базу.

Потребан је Firebase service account JSON који се не чува у git-у.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

try:
    import firebase_admin
    from firebase_admin import credentials, firestore
except ImportError:  # pragma: no cover - корисничка порука, не логика parser-а
    firebase_admin = None
    credentials = None
    firestore = None


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_JSON_OUTPUT = PROJECT_ROOT / "database" / "input" / "firestore_ready_for_review.json"
DEFAULT_REPORT_OUTPUT = PROJECT_ROOT / "database" / "generated" / "review_report.md"
DEFAULT_REPORTED_JSON_OUTPUT = PROJECT_ROOT / "database" / "input" / "firestore_reported_for_review.json"
DEFAULT_REPORTED_REPORT_OUTPUT = PROJECT_ROOT / "database" / "generated" / "reported_proposals.md"


@dataclass(frozen=True)
class ProposalOption:
    id: str
    proposal_text: str
    status: str
    created_by: str
    votes_count: int


@dataclass(frozen=True)
class ProposalGroup:
    id: str
    foreign_word: str
    first_letter: str
    origin: str
    addendum: str
    status: str
    proposal_type: str
    options: list[ProposalOption]


@dataclass(frozen=True)
class ProposalReport:
    id: str
    user_id: str
    reported_author_id: str
    reason: str


@dataclass(frozen=True)
class ReportedProposalOption:
    group_id: str
    option_id: str
    foreign_word: str
    proposal_text: str
    status: str
    created_by: str
    votes_count: int
    reports: list[ProposalReport]


def require_firebase_admin() -> None:
    if firebase_admin is None:
        raise RuntimeError(
            "Недостаје пакет firebase-admin. Инсталирај га у Python окружењу:\n"
            "  python -m pip install firebase-admin"
        )


def initialize_firestore(service_account_path: Path):
    require_firebase_admin()
    if not firebase_admin._apps:
        firebase_admin.initialize_app(credentials.Certificate(service_account_path))
    return firestore.client()


def text_from_legacy_option(option_data: dict[str, object]) -> str:
    replacement_word = str(option_data.get("replacement_word") or "").strip()
    if not replacement_word:
        return ""
    explanation = str(option_data.get("explanation") or "").strip()
    if explanation:
        return f"Српскословенска реч: {replacement_word} - {explanation}"
    return f"Српскословенска реч: {replacement_word}"


def proposal_text_from_option(option_data: dict[str, object]) -> str:
    proposal_text = str(option_data.get("proposal_text") or "").strip()
    return proposal_text or text_from_legacy_option(option_data)


def read_groups(
    client,
    min_votes: int,
    include_all: bool,
    option_status: str = "pending",
    include_all_statuses: bool = False,
) -> list[ProposalGroup]:
    query = client.collection("proposal_groups")
    groups: list[ProposalGroup] = []

    for group_snapshot in query.stream():
        group_data = group_snapshot.to_dict() or {}
        options: list[ProposalOption] = []
        for option_snapshot in group_snapshot.reference.collection("options").stream():
            option_data = option_snapshot.to_dict() or {}
            status = str(option_data.get("status") or "").strip()
            if not include_all_statuses and option_status and status != option_status:
                continue
            votes_count = int(option_data.get("votes_count") or 0)
            if not include_all and votes_count < min_votes:
                continue
            proposal_text = proposal_text_from_option(option_data)
            if not proposal_text:
                continue
            options.append(
                ProposalOption(
                    id=option_snapshot.id,
                    proposal_text=proposal_text,
                    status=status,
                    created_by=str(option_data.get("created_by") or "").strip(),
                    votes_count=votes_count,
                )
            )

        if options:
            foreign_word = str(group_data.get("foreign_word") or "").strip()
            if not foreign_word:
                continue
            groups.append(
                ProposalGroup(
                    id=group_snapshot.id,
                    foreign_word=foreign_word,
                    first_letter=str(group_data.get("first_letter") or foreign_word[:1] or "#"),
                    origin=str(group_data.get("origin") or "").strip(),
                    addendum=str(group_data.get("addendum") or "").strip(),
                    status=str(group_data.get("status") or ""),
                    proposal_type=str(group_data.get("type") or ""),
                    options=sorted(options, key=lambda item: (-item.votes_count, item.proposal_text.casefold())),
                )
            )

    return sorted(groups, key=lambda item: item.foreign_word.casefold())


def read_reported_options(client) -> list[ReportedProposalOption]:
    """Чита јавне предлоге који имају бар једну пријаву корисника."""
    reported: list[ReportedProposalOption] = []

    for group_snapshot in client.collection("proposal_groups").stream():
        group_data = group_snapshot.to_dict() or {}
        foreign_word = str(group_data.get("foreign_word") or "").strip()
        if not foreign_word:
            continue

        for option_snapshot in group_snapshot.reference.collection("options").stream():
            option_data = option_snapshot.to_dict() or {}
            reports: list[ProposalReport] = []
            for report_snapshot in option_snapshot.reference.collection("reports").stream():
                report_data = report_snapshot.to_dict() or {}
                reports.append(
                    ProposalReport(
                        id=report_snapshot.id,
                        user_id=str(report_data.get("user_id") or "").strip(),
                        reported_author_id=str(report_data.get("reported_author_id") or "").strip(),
                        reason=str(report_data.get("reason") or "").strip(),
                    )
                )

            if not reports:
                continue

            proposal_text = proposal_text_from_option(option_data)
            if not proposal_text:
                continue

            reported.append(
                ReportedProposalOption(
                    group_id=group_snapshot.id,
                    option_id=option_snapshot.id,
                    foreign_word=foreign_word,
                    proposal_text=proposal_text,
                    status=str(option_data.get("status") or "").strip(),
                    created_by=str(option_data.get("created_by") or "").strip(),
                    votes_count=int(option_data.get("votes_count") or 0),
                    reports=sorted(reports, key=lambda item: item.id),
                )
            )

    return sorted(
        reported,
        key=lambda item: (item.foreign_word.casefold(), -len(item.reports), -item.votes_count, item.proposal_text.casefold()),
    )


def write_review_json(groups: list[ProposalGroup], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "format": "mali-precnik-firestore-review",
        "version": 2,
        "proposal_format": "text",
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
                        "odobreno": False,
                    }
                    for option in group.options
                ],
            }
            for group in groups
        ],
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_reported_json(reported_options: list[ReportedProposalOption], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "format": "mali-precnik-firestore-reported-review",
        "version": 1,
        "proposal_format": "text",
        "reported_options": [
            {
                "group_id": option.group_id,
                "option_id": option.option_id,
                "tudjica": option.foreign_word,
                "tekst": option.proposal_text,
                "status": option.status,
                "autor": option.created_by,
                "glasova": option.votes_count,
                "broj_prijava": len(option.reports),
                "sakriti": False,
                "reports": [
                    {
                        "id": report.id,
                        "korisnik": report.user_id,
                        "reported_author_id": report.reported_author_id,
                        "razlog": report.reason,
                    }
                    for report in option.reports
                ],
            }
            for option in reported_options
        ],
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_review_report(groups: list[ProposalGroup], path: Path, min_votes: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# Предлози за ручни преглед",
        "",
        f"У извештају су приказани предлози са најмање {min_votes} гласова.",
        "Гласови су знак да предлог треба погледати, а не аутоматско прихватање.",
        "",
    ]

    if not groups:
        lines.append("Нема предлога који испуњавају задати праг.")
    else:
        for group in groups:
            lines.extend(
                [
                    f"## {group.foreign_word}",
                    "",
                    f"- Firestore id: `{group.id}`",
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
                        f"- Статус: `{option.status or 'није наведен'}`",
                        f"- Аутор: `{option.created_by or 'није наведен'}`",
                        "- Одобрено: `false`",
                        "",
                        "**Текст предлога**",
                        "",
                        option.proposal_text,
                        "",
                    ]
                )

    path.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


def write_reported_report(reported_options: list[ReportedProposalOption], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# Пријављени предлози за ручни преглед",
        "",
        "Ово су јавни предлози које је бар један корисник пријавио.",
        "За предлог који треба уклонити из гласања у JSON-у поставити `sakriti: true`, па покренути `moderate_reported_proposals.py`.",
        "",
    ]

    if not reported_options:
        lines.append("Нема пријављених предлога.")
    else:
        for option in reported_options:
            lines.extend(
                [
                    f"## {option.foreign_word}",
                    "",
                    f"- Firestore group id: `{option.group_id}`",
                    f"- Firestore option id: `{option.option_id}`",
                    f"- Статус: `{option.status or 'није наведен'}`",
                    f"- Гласова: {option.votes_count}",
                    f"- Број пријава: {len(option.reports)}",
                    f"- Аутор: `{option.created_by or 'није наведен'}`",
                    "- Сакрити: `false`",
                    "",
                    "**Текст предлога**",
                    "",
                    option.proposal_text,
                    "",
                    "**Пријаве**",
                    "",
                ]
            )
            for report in option.reports:
                lines.extend(
                    [
                        f"- Пријава `{report.id}`; корисник `{report.user_id or 'није наведен'}`; "
                        f"пријављени аутор `{report.reported_author_id or 'није наведен'}`; "
                        f"разлог: {report.reason or 'није наведен'}",
                    ]
                )
            lines.append("")

    path.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Преузима Firestore предлоге и прави review_report.md.")
    parser.add_argument(
        "--service-account",
        type=Path,
        required=True,
        help="Путања до Firebase service account JSON фајла.",
    )
    parser.add_argument("--min-votes", type=int, default=0, help="Праг гласова за извештај.")
    parser.add_argument("--include-all", action="store_true", help="У извештај убацује и предлоге испод прага.")
    parser.add_argument(
        "--option-status",
        default="pending",
        help="Који статус option докумената се повлачи. Подразумевано: pending.",
    )
    parser.add_argument(
        "--include-all-statuses",
        action="store_true",
        help="Повлачи option документе свих статуса.",
    )
    parser.add_argument("--json-out", type=Path, default=DEFAULT_JSON_OUTPUT, help=f"JSON излаз: {DEFAULT_JSON_OUTPUT}")
    parser.add_argument("--report-out", type=Path, default=DEFAULT_REPORT_OUTPUT, help=f"Markdown извештај: {DEFAULT_REPORT_OUTPUT}")
    parser.add_argument(
        "--reported-json-out",
        type=Path,
        default=DEFAULT_REPORTED_JSON_OUTPUT,
        help=f"JSON пријављених предлога: {DEFAULT_REPORTED_JSON_OUTPUT}",
    )
    parser.add_argument(
        "--reported-report-out",
        type=Path,
        default=DEFAULT_REPORTED_REPORT_OUTPUT,
        help=f"Markdown извештај пријављених предлога: {DEFAULT_REPORTED_REPORT_OUTPUT}",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.service_account.exists():
        raise FileNotFoundError(f"Service account JSON не постоји: {args.service_account}")

    client = initialize_firestore(args.service_account)
    groups = read_groups(
        client,
        min_votes=args.min_votes,
        include_all=args.include_all,
        option_status=args.option_status,
        include_all_statuses=args.include_all_statuses,
    )
    write_review_json(groups, args.json_out)
    write_review_report(groups, args.report_out, min_votes=args.min_votes)
    reported_options = read_reported_options(client)
    write_reported_json(reported_options, args.reported_json_out)
    write_reported_report(reported_options, args.reported_report_out)

    option_count = sum(len(group.options) for group in groups)
    print(f"Туђица за преглед: {len(groups)}")
    print(f"Предлога за преглед: {option_count}")
    print(f"JSON: {args.json_out}")
    print(f"Извештај: {args.report_out}")
    print(f"Пријављених предлога: {len(reported_options)}")
    print(f"JSON пријава: {args.reported_json_out}")
    print(f"Извештај пријава: {args.reported_report_out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
