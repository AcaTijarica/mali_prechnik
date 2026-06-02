#!/usr/bin/env python3
"""
Сакрива пријављене Firestore предлоге које је уредник означио као неприкладне.

`fetch_firestore_proposals.py` прави
`database/input/firestore_reported_for_review.json`. Уредник тај фајл отвори,
за неприкладне предлоге постави:

    "sakriti": true

Ова скрипта затим тим `options` документима поставља `status = hidden`.
Android и Python апликација на страници `Гласање` читају само `status = approved`,
па се сакривени предлози више не приказују корисницима.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from fetch_firestore_proposals import initialize_firestore


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_REPORTED = PROJECT_ROOT / "database" / "input" / "firestore_reported_for_review.json"


def read_json(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def truthy(value: object) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().casefold() in {"true", "1", "yes", "да", "sakriti", "сакрити"}
    return False


def hide_reported_options(client, reviewed_path: Path, dry_run: bool) -> int:
    from firebase_admin import firestore

    payload = read_json(reviewed_path)
    raw_options = payload.get("reported_options", []) if isinstance(payload, dict) else payload
    if not isinstance(raw_options, list):
        raise ValueError("JSON нема очекивано поље `reported_options`.")

    hidden_count = 0
    for raw_option in raw_options:
        if not isinstance(raw_option, dict) or not truthy(raw_option.get("sakriti")):
            continue

        group_id = str(raw_option.get("group_id") or "").strip()
        option_id = str(raw_option.get("option_id") or "").strip()
        if not group_id or not option_id:
            continue

        hidden_count += 1
        if not dry_run:
            option_ref = (
                client.collection("proposal_groups")
                .document(group_id)
                .collection("options")
                .document(option_id)
            )
            option_ref.update(
                {
                    "status": "hidden",
                    "moderated_at": firestore.SERVER_TIMESTAMP,
                }
            )

    return hidden_count


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Сакрива пријављене Firestore предлоге означене са `sakriti: true`.")
    parser.add_argument(
        "--service-account",
        type=Path,
        required=True,
        help="Путања до Firebase service account JSON фајла.",
    )
    parser.add_argument(
        "--reviewed",
        type=Path,
        default=DEFAULT_REPORTED,
        help=f"Ручно прегледан JSON пријава: {DEFAULT_REPORTED}",
    )
    parser.add_argument("--dry-run", action="store_true", help="Приказује број измена без уписа у Firestore.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.service_account.exists():
        raise FileNotFoundError(f"Service account JSON не постоји: {args.service_account}")
    if not args.reviewed.exists():
        raise FileNotFoundError(f"JSON пријава не постоји: {args.reviewed}")

    client = initialize_firestore(args.service_account)
    hidden_count = hide_reported_options(client, args.reviewed, args.dry_run)
    print(f"Предлога за сакривање: {hidden_count}")
    if args.dry_run:
        print("Dry run: ништа није уписано у Firestore.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
