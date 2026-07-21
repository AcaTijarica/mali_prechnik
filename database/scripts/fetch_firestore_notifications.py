#!/usr/bin/env python3
"""
Преузима уредничка обавештења из Firestore-а у JSON фајл.

Овај почерк служи као први корак уредничког тока:

1. повући тренутна обавештења из Firestore-а;
2. ручно уредити `database/input/notifications.json`;
3. потом покренути `publish_firestore_notifications.py`.

Потребан је Firebase service account JSON који се не чува у git-у.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

try:
    import firebase_admin
    from firebase_admin import credentials, firestore
except ImportError:  # pragma: no cover - корисничка порука, не логика
    firebase_admin = None
    credentials = None
    firestore = None


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = PROJECT_ROOT / "database" / "input" / "notifications.json"
COLLECTION_NOTIFICATIONS = "notifications"


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


def iso_from_firestore_value(value: object) -> str:
    if value is None:
        return ""
    if isinstance(value, datetime):
        date_value = value
    elif hasattr(value, "timestamp"):
        date_value = datetime.fromtimestamp(value.timestamp(), tz=timezone.utc)
    else:
        return str(value)
    if date_value.tzinfo is None:
        date_value = date_value.replace(tzinfo=timezone.utc)
    return date_value.isoformat()


def read_notifications(client) -> list[dict[str, object]]:
    notifications: list[dict[str, object]] = []
    for snapshot in client.collection(COLLECTION_NOTIFICATIONS).stream():
        data = snapshot.to_dict() or {}
        notifications.append(
            {
                "id": snapshot.id,
                "title": str(data.get("title") or "").strip(),
                "author": str(data.get("author") or "").strip(),
                "published_at": iso_from_firestore_value(data.get("published_at")),
                "status": str(data.get("status") or "draft").strip() or "draft",
                "body": str(data.get("body") or "").strip(),
            }
        )
    return sorted(
        notifications,
        key=lambda item: (str(item.get("published_at") or ""), str(item.get("title") or "")),
        reverse=True,
    )


def write_json(path: Path, notifications: list[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {"notifications": notifications}
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Преузима Firestore обавештења у JSON.")
    parser.add_argument(
        "--service-account",
        type=Path,
        required=True,
        help="Путања до Firebase service account JSON фајла.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help=f"Излазни JSON фајл: {DEFAULT_OUTPUT}",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.service_account.exists():
        raise FileNotFoundError(f"Service account JSON не постоји: {args.service_account}")

    client = initialize_firestore(args.service_account)
    notifications = read_notifications(client)
    write_json(args.output, notifications)
    print(f"Преузето обавештења: {len(notifications)}")
    print(f"Уписано: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
