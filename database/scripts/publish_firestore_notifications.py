#!/usr/bin/env python3
"""
Објављује уредничка обавештења из JSON фајла у Firestore.

Android апликација чита само документе у колекцији `notifications` који имају
`status = "published"`. Остала стања (`draft`, `hidden`) могу да остану у
Firestore-у, али се не приказују корисницима.

Потребан је Firebase service account JSON који се не чува у git-у.
"""

from __future__ import annotations

import argparse
import json
import re
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
DEFAULT_INPUT = PROJECT_ROOT / "database" / "input" / "notifications.json"
COLLECTION_NOTIFICATIONS = "notifications"
ALLOWED_STATUSES = {"draft", "published", "hidden"}


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


def read_json(path: Path) -> dict[str, object]:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def clean_text(value: object) -> str:
    return str(value or "").replace("\r\n", "\n").strip()


def document_id_from_title(title: str, published_at: str) -> str:
    source = f"{published_at}-{title}".casefold()
    slug = re.sub(r"[^0-9a-zа-яђјљњћџčćžšđ]+", "-", source, flags=re.IGNORECASE).strip("-")
    return slug[:120] or "obavestenje"


def parse_datetime(value: str) -> datetime:
    cleaned = value.strip()
    if not cleaned:
        return datetime.now(timezone.utc)
    if cleaned.endswith("Z"):
        cleaned = cleaned[:-1] + "+00:00"
    parsed = datetime.fromisoformat(cleaned)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def normalized_notifications(path: Path) -> list[dict[str, object]]:
    payload = read_json(path)
    raw_notifications = payload.get("notifications", [])
    if not isinstance(raw_notifications, list):
        raise ValueError("JSON мора да садржи листу `notifications`.")

    notifications: list[dict[str, object]] = []
    seen_ids: set[str] = set()
    for index, raw_notification in enumerate(raw_notifications, start=1):
        if not isinstance(raw_notification, dict):
            raise ValueError(f"Обавештење #{index} није JSON објекат.")

        title = clean_text(raw_notification.get("title"))
        body = clean_text(raw_notification.get("body"))
        author = clean_text(raw_notification.get("author")) or "Уредништво"
        published_at_text = clean_text(raw_notification.get("published_at"))
        status = clean_text(raw_notification.get("status")) or "draft"
        if status not in ALLOWED_STATUSES:
            raise ValueError(f"Обавештење #{index} има непознат status: {status}")
        if not title:
            raise ValueError(f"Обавештење #{index} нема наслов.")
        if not body:
            raise ValueError(f"Обавештење #{index} нема писаније.")
        if len(title) > 180:
            raise ValueError(f"Наслов обавештења #{index} је предугачак.")
        if len(author) > 120:
            raise ValueError(f"Име списатеља обавештења #{index} је предугачко.")
        if len(body) > 20000:
            raise ValueError(f"Писаније обавештења #{index} је предугачко.")

        notification_id = clean_text(raw_notification.get("id")) or document_id_from_title(title, published_at_text)
        if notification_id in seen_ids:
            raise ValueError(f"Дуплиран id обавештења: {notification_id}")
        seen_ids.add(notification_id)

        notifications.append(
            {
                "id": notification_id,
                "title": title,
                "author": author,
                "body": body,
                "status": status,
                "published_at": parse_datetime(published_at_text),
            }
        )

    return notifications


def publish_notifications(client, notifications: list[dict[str, object]], delete_missing: bool, dry_run: bool) -> tuple[int, int]:
    collection = client.collection(COLLECTION_NOTIFICATIONS)
    wanted_ids = {str(notification["id"]) for notification in notifications}
    written_count = 0
    deleted_count = 0

    for notification in notifications:
        notification_id = str(notification["id"])
        data = {
            "title": notification["title"],
            "author": notification["author"],
            "body": notification["body"],
            "status": notification["status"],
            "published_at": notification["published_at"],
            "updated_at": firestore.SERVER_TIMESTAMP,
        }
        written_count += 1
        if not dry_run:
            collection.document(notification_id).set(data, merge=True)

    if delete_missing:
        for snapshot in collection.stream():
            if snapshot.id not in wanted_ids:
                deleted_count += 1
                if not dry_run:
                    snapshot.reference.delete()

    return written_count, deleted_count


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Објављује JSON обавештења у Firestore.")
    parser.add_argument(
        "--service-account",
        type=Path,
        required=True,
        help="Путања до Firebase service account JSON фајла.",
    )
    parser.add_argument(
        "--input",
        type=Path,
        default=DEFAULT_INPUT,
        help=f"Улазни JSON фајл: {DEFAULT_INPUT}",
    )
    parser.add_argument(
        "--delete-missing",
        action="store_true",
        help="Брише Firestore обавештења која више не постоје у JSON-у.",
    )
    parser.add_argument("--dry-run", action="store_true", help="Проверава JSON и исписује бројке без уписа.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.service_account.exists():
        raise FileNotFoundError(f"Service account JSON не постоји: {args.service_account}")
    if not args.input.exists():
        raise FileNotFoundError(f"JSON са обавештењима не постоји: {args.input}")

    notifications = normalized_notifications(args.input)
    client = initialize_firestore(args.service_account)
    written_count, deleted_count = publish_notifications(
        client=client,
        notifications=notifications,
        delete_missing=args.delete_missing,
        dry_run=args.dry_run,
    )
    print(f"Уписано/освежено обавештења: {written_count}")
    print(f"Обрисано обавештења: {deleted_count}")
    if args.dry_run:
        print("Dry run: ништа није уписано у Firestore.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
