from __future__ import annotations

import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

try:
    from .model import BUNDLE_ROOT, Entry, IS_FROZEN, PROJECT_ROOT, clean_text, initial_letter, normalize_word, serbian_sort_key
except ImportError:
    from model import BUNDLE_ROOT, Entry, IS_FROZEN, PROJECT_ROOT, clean_text, initial_letter, normalize_word, serbian_sort_key


GOOGLE_SERVICES_JSON = (
    BUNDLE_ROOT / "app" / "google-services.json"
    if IS_FROZEN
    else PROJECT_ROOT / "app" / "google-services.json"
)
AUTH_CACHE = Path.home() / ".mali_precnik" / "firebase_anonymous_user.json"


class RemoteProposalError(RuntimeError):
    pass


@dataclass(frozen=True)
class RemoteVotingOption:
    id: str
    proposal_text: str
    votes_count: int
    created_by: str


@dataclass(frozen=True)
class RemoteVotingGroup:
    id: str
    foreign_word: str
    first_letter: str
    options: list[RemoteVotingOption]


def proposal_text_from_entry(entry: Entry) -> str:
    lines = [
        f"Туђица: {clean_text(entry.foreign_word)}",
        f"Порекло: {clean_text(entry.origin)}",
    ]
    for option in entry.normalized_options():
        replacement_word = clean_text(option.replacement_word)
        if not replacement_word:
            continue
        explanation = clean_text(option.explanation)
        prefix = f"Српскословенска реч: {replacement_word}"
        lines.append(f"{prefix} - {explanation}" if explanation else prefix)
    lines.append(f"Додатак: {clean_text(entry.addendum)}")
    return "\n".join(lines)


class RemoteProposalClient:
    """Мали Firebase REST клијент за PC апликацију.

    Android користи званични Firebase SDK. На PC страни не уводимо посебан
    Google налог нити service account за обичног корисника, него користимо исти
    `google-services.json` и Firebase Anonymous Auth преко REST API-ја.
    """

    def __init__(self, config_path: Path = GOOGLE_SERVICES_JSON, auth_cache: Path = AUTH_CACHE) -> None:
        self.config_path = config_path
        self.auth_cache = auth_cache
        self.project_id = ""
        self.api_key = ""
        self.local_id = ""
        self.id_token = ""
        self.refresh_token = ""
        self.expires_at = 0.0
        self.blocked_authors_path = auth_cache.with_name("blocked_authors.json")
        self._load_configuration()

    @property
    def configured(self) -> bool:
        return bool(self.project_id and self.api_key)

    def _load_configuration(self) -> None:
        if not self.config_path.exists():
            return
        payload = json.loads(self.config_path.read_text(encoding="utf-8-sig"))
        self.project_id = str(payload.get("project_info", {}).get("project_id") or "").strip()
        clients = payload.get("client") or []
        if clients:
            api_keys = clients[0].get("api_key") or []
            if api_keys:
                self.api_key = str(api_keys[0].get("current_key") or "").strip()

    def load_voting_proposals(self) -> list[RemoteVotingGroup]:
        self._require_configuration()
        groups = self._run_query(
            parent_path="",
            structured_query={
                "from": [{"collectionId": "proposal_groups"}],
                "where": {
                    "fieldFilter": {
                        "field": {"fieldPath": "status"},
                        "op": "EQUAL",
                        "value": {"stringValue": "voting"},
                    }
                },
            },
        )
        blocked_authors = self.blocked_authors()
        result: list[RemoteVotingGroup] = []
        for group_id, fields in groups:
            foreign_word = clean_text(fields.get("foreign_word"))
            if not foreign_word:
                continue
            option_docs = self._run_query(
                parent_path=f"proposal_groups/{group_id}",
                structured_query={
                    "from": [{"collectionId": "options"}],
                    "where": {
                        "fieldFilter": {
                            "field": {"fieldPath": "status"},
                            "op": "EQUAL",
                            "value": {"stringValue": "approved"},
                        }
                    },
                },
            )
            options: list[RemoteVotingOption] = []
            for option_id, option_fields in option_docs:
                proposal_text = clean_text(option_fields.get("proposal_text"))
                created_by = clean_text(option_fields.get("created_by"))
                if not proposal_text or created_by in blocked_authors:
                    continue
                options.append(
                    RemoteVotingOption(
                        id=option_id,
                        proposal_text=proposal_text,
                        votes_count=int(option_fields.get("votes_count") or 0),
                        created_by=created_by,
                    )
                )
            options.sort(key=lambda item: (-item.votes_count, item.proposal_text.casefold()))
            if options:
                result.append(
                    RemoteVotingGroup(
                        id=group_id,
                        foreign_word=foreign_word,
                        first_letter=clean_text(fields.get("first_letter")) or initial_letter(foreign_word),
                        options=options,
                    )
                )
        return sorted(result, key=lambda item: serbian_sort_key(item.foreign_word))

    def submit_proposal(self, entry: Entry, proposal_type: str, proposal_text: str | None = None) -> None:
        self._require_configuration()
        self._ensure_signed_in()
        cleaned_text = clean_text(proposal_text or proposal_text_from_entry(entry))
        if not cleaned_text:
            raise RemoteProposalError("Потребно је унети текст предлога.")

        foreign_word = clean_text(entry.foreign_word)
        normalized_foreign_word = normalize_word(foreign_word)
        if not normalized_foreign_word:
            raise RemoteProposalError("Потребно је унети туђицу.")

        group_id = stable_document_id(normalized_foreign_word)
        option_id = stable_document_id(f"{normalized_foreign_word}|{cleaned_text.lower()}")
        group_path = f"proposal_groups/{group_id}"
        option_path = f"{group_path}/options/{option_id}"

        if not self._document_exists(group_path):
            self._create_document(
                group_path,
                {
                    "foreign_word": foreign_word,
                    "normalized_foreign_word": normalized_foreign_word,
                    "first_letter": initial_letter(foreign_word),
                    "origin": clean_text(entry.origin),
                    "addendum": clean_text(entry.addendum),
                    "type": proposal_type,
                    "proposal_format": "text",
                    "status": "pending",
                    "created_by": self.local_id,
                    "created_at": now_timestamp(),
                },
            )

        if not self._document_exists(option_path):
            self._create_document(
                option_path,
                {
                    "proposal_text": cleaned_text,
                    "type": proposal_type,
                    "votes_count": 0,
                    "status": "pending",
                    "created_by": self.local_id,
                    "created_at": now_timestamp(),
                },
            )

    def vote_for_option(self, group_id: str, option_id: str) -> None:
        self._require_configuration()
        self._ensure_signed_in()
        vote_path = f"proposal_groups/{group_id}/votes/{self.local_id}"
        if self._document_exists(vote_path):
            raise RemoteProposalError("Већ је забележен глас за ову туђицу.")

        option_path = f"proposal_groups/{group_id}/options/{option_id}"
        option = self._get_document(option_path)
        if not option:
            raise RemoteProposalError("Одабрани предлог више не постоји.")
        if clean_text(option.get("status")) != "approved":
            raise RemoteProposalError("Одабрани предлог још није одобрен за гласање.")

        current_votes = int(option.get("votes_count") or 0)
        self._commit(
            [
                {
                    "update": self._document_payload(
                        vote_path,
                        {
                            "option_id": option_id,
                            "user_id": self.local_id,
                            "created_at": now_timestamp(),
                        },
                    ),
                    "currentDocument": {"exists": False},
                },
                {
                    "update": self._document_payload(option_path, {"votes_count": current_votes + 1}),
                    "updateMask": {"fieldPaths": ["votes_count"]},
                    "currentDocument": {"exists": True},
                },
            ]
        )

    def report_option(self, group_id: str, option_id: str, reported_author_id: str) -> None:
        self._require_configuration()
        self._ensure_signed_in()
        reported_author_id = clean_text(reported_author_id)
        if not reported_author_id:
            raise RemoteProposalError("Није познат предлагач овог предлога.")

        report_path = f"proposal_groups/{group_id}/options/{option_id}/reports/{self.local_id}"
        self._create_document(
            report_path,
            {
                "user_id": self.local_id,
                "reported_author_id": reported_author_id,
                "reason": "Корисник је пријавио предлог и предлагача као неумесне или неприхватљиве.",
                "created_at": now_timestamp(),
            },
        )

    def block_author(self, author_id: str) -> None:
        author_id = clean_text(author_id)
        if not author_id:
            return
        authors = self.blocked_authors()
        authors.add(author_id)
        self.blocked_authors_path.parent.mkdir(parents=True, exist_ok=True)
        self.blocked_authors_path.write_text(
            json.dumps(sorted(authors), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    def blocked_authors(self) -> set[str]:
        if not self.blocked_authors_path.exists():
            return set()
        try:
            payload = json.loads(self.blocked_authors_path.read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError:
            return set()
        return {clean_text(item) for item in payload if clean_text(item)}

    def _require_configuration(self) -> None:
        if not self.configured:
            raise RemoteProposalError(
                "Firebase није подешен за PC апликацију. Потребан је app/google-services.json."
            )

    def _ensure_signed_in(self) -> None:
        if self.id_token and time.time() < self.expires_at - 60:
            return

        self._load_auth_cache()
        if self.refresh_token:
            try:
                self._refresh_token()
                return
            except RemoteProposalError:
                pass
        self._sign_in_anonymously()

    def _load_auth_cache(self) -> None:
        if not self.auth_cache.exists():
            return
        try:
            payload = json.loads(self.auth_cache.read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError:
            return
        self.local_id = clean_text(payload.get("local_id"))
        self.id_token = clean_text(payload.get("id_token"))
        self.refresh_token = clean_text(payload.get("refresh_token"))
        self.expires_at = float(payload.get("expires_at") or 0)

    def _save_auth_cache(self) -> None:
        self.auth_cache.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "local_id": self.local_id,
            "id_token": self.id_token,
            "refresh_token": self.refresh_token,
            "expires_at": self.expires_at,
        }
        self.auth_cache.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def _sign_in_anonymously(self) -> None:
        payload = self._anonymous_auth_request(
            "https://identitytoolkit.googleapis.com/v1/accounts:signUp",
            {"returnSecureToken": True},
        )
        self._store_auth_payload(payload)

    def _refresh_token(self) -> None:
        url = f"https://securetoken.googleapis.com/v1/token?key={urllib.parse.quote(self.api_key)}"
        body = urllib.parse.urlencode(
            {
                "grant_type": "refresh_token",
                "refresh_token": self.refresh_token,
            }
        ).encode("utf-8")
        request = urllib.request.Request(url, data=body, method="POST")
        request.add_header("Content-Type", "application/x-www-form-urlencoded")
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            raise RemoteProposalError(_http_error_message(exc)) from exc
        self.local_id = clean_text(payload.get("user_id"))
        self.id_token = clean_text(payload.get("id_token"))
        self.refresh_token = clean_text(payload.get("refresh_token"))
        self.expires_at = time.time() + int(payload.get("expires_in") or 3600)
        self._save_auth_cache()

    def _anonymous_auth_request(self, url: str, payload: dict[str, Any]) -> dict[str, Any]:
        request = urllib.request.Request(
            f"{url}?key={urllib.parse.quote(self.api_key)}",
            data=json.dumps(payload).encode("utf-8"),
            method="POST",
        )
        request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            raise RemoteProposalError(_http_error_message(exc)) from exc

    def _store_auth_payload(self, payload: dict[str, Any]) -> None:
        self.local_id = clean_text(payload.get("localId") or payload.get("user_id"))
        self.id_token = clean_text(payload.get("idToken") or payload.get("id_token"))
        self.refresh_token = clean_text(payload.get("refreshToken") or payload.get("refresh_token"))
        self.expires_at = time.time() + int(payload.get("expiresIn") or payload.get("expires_in") or 3600)
        if not self.local_id or not self.id_token:
            raise RemoteProposalError("Firebase није вратио кориснички id.")
        self._save_auth_cache()

    def _run_query(self, parent_path: str, structured_query: dict[str, Any]) -> list[tuple[str, dict[str, Any]]]:
        self._ensure_signed_in()
        suffix = f"/{parent_path}" if parent_path else ""
        url = self._firestore_url(f"documents{suffix}:runQuery")
        payload = {"structuredQuery": structured_query}
        rows = self._request_json("POST", url, payload)
        documents: list[tuple[str, dict[str, Any]]] = []
        for row in rows:
            document = row.get("document")
            if not document:
                continue
            doc_id = document["name"].split("/")[-1]
            documents.append((doc_id, decode_fields(document.get("fields", {}))))
        return documents

    def _document_exists(self, document_path: str) -> bool:
        return self._get_document(document_path) is not None

    def _get_document(self, document_path: str) -> dict[str, Any] | None:
        self._ensure_signed_in()
        url = self._firestore_url(f"documents/{document_path}")
        try:
            document = self._request_json("GET", url)
        except RemoteProposalError as exc:
            if "NOT_FOUND" in str(exc) or "404" in str(exc):
                return None
            raise
        return decode_fields(document.get("fields", {}))

    def _create_document(self, document_path: str, fields: dict[str, Any]) -> None:
        self._ensure_signed_in()
        url = self._firestore_url(f"documents/{document_path}") + "?currentDocument.exists=false"
        try:
            self._request_json("PATCH", url, self._document_payload(document_path, fields, include_name=False))
        except RemoteProposalError as exc:
            if "ALREADY_EXISTS" in str(exc):
                return
            raise

    def _commit(self, writes: list[dict[str, Any]]) -> None:
        self._ensure_signed_in()
        self._request_json("POST", self._firestore_url("documents:commit"), {"writes": writes})

    def _document_payload(self, document_path: str, fields: dict[str, Any], include_name: bool = True) -> dict[str, Any]:
        payload = {"fields": {key: encode_value(value) for key, value in fields.items()}}
        if include_name:
            payload["name"] = f"projects/{self.project_id}/databases/(default)/documents/{document_path}"
        return payload

    def _request_json(self, method: str, url: str, payload: Any | None = None) -> Any:
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("Authorization", f"Bearer {self.id_token}")
        request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read().decode("utf-8")
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as exc:
            raise RemoteProposalError(_http_error_message(exc)) from exc

    def _firestore_url(self, path: str) -> str:
        return f"https://firestore.googleapis.com/v1/projects/{self.project_id}/databases/(default)/{path}"


def encode_value(value: Any) -> dict[str, Any]:
    if isinstance(value, bool):
        return {"booleanValue": value}
    if isinstance(value, int):
        return {"integerValue": str(value)}
    if isinstance(value, float):
        return {"doubleValue": value}
    if isinstance(value, datetime):
        return {"timestampValue": value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")}
    if value is None:
        return {"nullValue": None}
    return {"stringValue": str(value)}


def decode_fields(fields: dict[str, Any]) -> dict[str, Any]:
    return {key: decode_value(value) for key, value in fields.items()}


def decode_value(value: dict[str, Any]) -> Any:
    if "stringValue" in value:
        return value["stringValue"]
    if "integerValue" in value:
        return int(value["integerValue"])
    if "doubleValue" in value:
        return float(value["doubleValue"])
    if "booleanValue" in value:
        return bool(value["booleanValue"])
    if "timestampValue" in value:
        return value["timestampValue"]
    if "nullValue" in value:
        return None
    return ""


def now_timestamp() -> datetime:
    return datetime.now(timezone.utc)


def stable_document_id(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _http_error_message(exc: urllib.error.HTTPError) -> str:
    try:
        payload = json.loads(exc.read().decode("utf-8"))
        error = payload.get("error", {})
        status = error.get("status")
        message = error.get("message")
        if status or message:
            return f"{exc.code} {status or ''}: {message or ''}".strip()
    except Exception:
        pass
    return f"HTTP {exc.code}: {exc.reason}"
