#!/usr/bin/env python3
"""
Одобрава ручно прегледане Firestore предлоге за јавно гласање.

`fetch_firestore_proposals.py` прави JSON у ком сваки предлог има поље
`odobreno`. Уредник ручно постави `odobreno: true` за предлоге који смеју да
буду јавно видљиви у апликацији, а ова скрипта потом у Firestore-у:

- поставља тај текстуални proposal/option документ на `status = approved`;
- поставља његову групу на `status = voting`;
- по жељи означава неодобрене pending текстуалне предлоге као `rejected`.

Скрипта користи Firebase service account JSON и зато се покреће само уреднички,
на рачунару, никако из Android апликације.
"""

from __future__ import annotations

import argparse
import json
import sys
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
DEFAULT_REVIEWED = PROJECT_ROOT / "database" / "input" / "firestore_ready_for_review.json"


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


def read_json(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def truthy(value: object) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().casefold() in {"true", "1", "yes", "да", "odobreno"}
    return False


def approve_reviewed_proposals(
    client,
    reviewed_path: Path,
    reject_unapproved: bool,
    dry_run: bool,
) -> tuple[int, int]:
    payload = read_json(reviewed_path)
    raw_groups = payload.get("groups", []) if isinstance(payload, dict) else payload
    if not isinstance(raw_groups, list):
        raise ValueError("Review JSON нема очекивано поље `groups`.")

    approved_count = 0
    rejected_count = 0

    for raw_group in raw_groups:
        if not isinstance(raw_group, dict):
            continue
        group_id = str(raw_group.get("id") or "").strip()
        if not group_id:
            continue

        group_ref = client.collection("proposal_groups").document(group_id)
        group_has_approved_option = False
        raw_options = raw_group.get("predlozi") or raw_group.get("options") or []
        if not isinstance(raw_options, list):
            continue

        for raw_option in raw_options:
            if not isinstance(raw_option, dict):
                continue
            option_id = str(raw_option.get("id") or "").strip()
            if not option_id:
                continue

            option_ref = group_ref.collection("options").document(option_id)
            option_is_approved = truthy(raw_option.get("odobreno"))
            option_status = str(raw_option.get("status") or "").strip()

            if option_is_approved:
                approved_count += 1
                group_has_approved_option = True
                if not dry_run:
                    option_ref.update(
                        {
                            "status": "approved",
                            "approved_at": firestore.SERVER_TIMESTAMP,
                            "reviewed_at": firestore.SERVER_TIMESTAMP,
                        }
                    )
            elif reject_unapproved and option_status == "pending":
                rejected_count += 1
                if not dry_run:
                    option_ref.update(
                        {
                            "status": "rejected",
                            "reviewed_at": firestore.SERVER_TIMESTAMP,
                        }
                    )

        if group_has_approved_option and not dry_run:
            group_ref.update(
                {
                    "status": "voting",
                    "reviewed_at": firestore.SERVER_TIMESTAMP,
                }
            )

    return approved_count, rejected_count


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Одобрава reviewed Firestore предлоге за јавно гласање.")
    parser.add_argument(
        "--service-account",
        type=Path,
        required=True,
        help="Путања до Firebase service account JSON фајла.",
    )
    parser.add_argument(
        "--reviewed",
        type=Path,
        default=DEFAULT_REVIEWED,
        help=f"Ручно прегледан JSON: {DEFAULT_REVIEWED}",
    )
    parser.add_argument(
        "--reject-unapproved",
        action="store_true",
        help="Pending предлоге који немају `odobreno: true` означава као rejected.",
    )
    parser.add_argument("--dry-run", action="store_true", help="Приказује број измена без уписа у Firestore.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.service_account.exists():
        raise FileNotFoundError(f"Service account JSON не постоји: {args.service_account}")
    if not args.reviewed.exists():
        raise FileNotFoundError(f"Review JSON не постоји: {args.reviewed}")

    client = initialize_firestore(args.service_account)
    approved_count, rejected_count = approve_reviewed_proposals(
        client=client,
        reviewed_path=args.reviewed,
        reject_unapproved=args.reject_unapproved,
        dry_run=args.dry_run,
    )

    print(f"Одобрених предлога: {approved_count}")
    print(f"Одбијених предлога: {rejected_count}")
    if args.dry_run:
        print("Dry run: ништа није уписано у Firestore.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
