"""Reusable idempotency helper (contract section 3.3).

Table (in the *same* SQLite DB as the business rows, so the business write and
the idempotency record commit in **one transaction**)::

    idempotency_records(key TEXT PRIMARY KEY, request_hash TEXT NOT NULL,
                        response_proto BLOB NOT NULL, created_at TIMESTAMP)

Semantics for every keyed RPC:

* key seen with the same request hash  -> return the stored response verbatim
  (successes *and* business failures replay identically, e.g. a declined
  charge).
* key seen with a different hash       -> business failure
  ``IDEMPOTENCY_CONFLICT`` (returned in a response field, never a gRPC
  status code).
"""
from __future__ import annotations

import hashlib
from datetime import datetime, timezone

from google.protobuf.message import Message
from sqlalchemy import BLOB, DateTime, String
from sqlalchemy.orm import Mapped, Session, mapped_column

from .models import Base

IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"

# Outcome of a key lookup: ("none", None), ("replay", <serialized response>)
# or ("conflict", None).
LOOKUP_NONE = "none"
LOOKUP_REPLAY = "replay"
LOOKUP_CONFLICT = "conflict"


class IdempotencyRecord(Base):
    __tablename__ = "idempotency_records"

    key: Mapped[str] = mapped_column(String, primary_key=True)
    request_hash: Mapped[str] = mapped_column(String, nullable=False)
    response_proto: Mapped[bytes] = mapped_column(BLOB, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, default=lambda: datetime.now(timezone.utc)
    )


def request_hash(request: Message) -> str:
    """Stable SHA-256 of the request proto (deterministic serialization)."""
    return hashlib.sha256(request.SerializeToString(deterministic=True)).hexdigest()


def check(session: Session, key: str, req_hash: str) -> tuple[str, bytes | None]:
    """Look up *key* inside the caller's transaction.

    Returns ``(LOOKUP_NONE, None)`` when the key is fresh,
    ``(LOOKUP_REPLAY, stored_response_bytes)`` on a same-hash replay and
    ``(LOOKUP_CONFLICT, None)`` when the key was used with a different payload.
    """
    record = session.get(IdempotencyRecord, key)
    if record is None:
        return LOOKUP_NONE, None
    if record.request_hash != req_hash:
        return LOOKUP_CONFLICT, None
    return LOOKUP_REPLAY, record.response_proto


def record(session: Session, key: str, req_hash: str, response: Message) -> None:
    """Store the response for *key* (committed together with business rows)."""
    session.add(
        IdempotencyRecord(
            key=key,
            request_hash=req_hash,
            response_proto=response.SerializeToString(),
        )
    )
