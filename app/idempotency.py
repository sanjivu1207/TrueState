"""Idempotency-key handling for POST /payments.

The contract (see README): a client retrying the same logical request sends
the same `Idempotency-Key`. The server must:
  - process a brand-new key normally,
  - replay the stored response if that key already completed,
  - reject the key if it's reused with a *different* request body,
  - and never let two concurrent requests for the same new key both process
    (one should win; the other should see the winner's result).

`reserve()` handles all of that. It commits its own short transaction to
claim the key, so the claim is visible to other requests immediately —
before any of the actual payment work starts.
"""

import hashlib
import json
import time
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any

from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.models import IdempotencyRecord, IdempotencyStatus

# If a request holding a key dies before finishing (crash, killed
# connection), don't let that key block retries forever — after this long
# with no completion, a new request is allowed to reclaim it.
STALE_PROCESSING_AFTER = timedelta(seconds=30)

# How long a request will wait for a concurrent, in-flight duplicate to
# finish before giving up and returning 409 instead of the real result.
WAIT_FOR_COMPLETION_TIMEOUT_SECONDS = 5.0
POLL_INTERVAL_SECONDS = 0.05


def _as_aware_utc(dt: datetime) -> datetime:
    """Treat a naive datetime as UTC. Postgres (DateTime(timezone=True))
    always hands back an aware datetime; SQLite, used only for local/test
    runs, hands back a naive one — normalize so age comparisons work on
    either backend."""
    return dt if dt.tzinfo is not None else dt.replace(tzinfo=UTC)


def hash_request(payload: dict[str, Any]) -> str:
    """Deterministic hash of a request body, used to detect a key being
    replayed with a different payload."""
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(canonical.encode()).hexdigest()


@dataclass
class Replay:
    """A previous request with this key already completed — return its
    stored response verbatim instead of reprocessing."""

    status_code: int
    body: Any


@dataclass
class HashMismatch:
    """Same key, different payload — the client is misusing the key."""


@dataclass
class TimedOut:
    """A concurrent request is still processing this key and didn't finish
    in time — the caller should return 409."""


# None means: the caller now owns the key and should process the request.
ReserveResult = Replay | HashMismatch | TimedOut | None


def reserve(db: Session, key: str, request_hash: str) -> ReserveResult:
    record = IdempotencyRecord(idempotency_key=key, request_hash=request_hash)
    db.add(record)
    try:
        db.commit()
        return None  # fresh key, we own it
    except IntegrityError:
        db.rollback()

    return _resolve_conflict(db, key, request_hash)


def _resolve_conflict(db: Session, key: str, request_hash: str) -> ReserveResult:
    deadline = time.monotonic() + WAIT_FOR_COMPLETION_TIMEOUT_SECONDS

    while True:
        record = db.get(IdempotencyRecord, key)
        db.rollback()  # don't hold a transaction open while we poll

        if record is None:
            # Raced with a reclaim between our failed insert and this read.
            return reserve(db, key, request_hash)

        if record.request_hash != request_hash:
            return HashMismatch()

        if record.status == IdempotencyStatus.COMPLETED:
            return Replay(status_code=record.response_status_code, body=record.response_body)

        # Still processing — likely a genuine concurrent duplicate. Unless
        # it's stale, wait for it to finish and replay its result rather
        # than doing the work twice.
        if datetime.now(UTC) - _as_aware_utc(record.created_at) > STALE_PROCESSING_AFTER:
            reclaimed = _try_reclaim(db, key, request_hash)
            if reclaimed:
                return None

        if time.monotonic() >= deadline:
            return TimedOut()

        time.sleep(POLL_INTERVAL_SECONDS)


def _try_reclaim(db: Session, key: str, request_hash: str) -> bool:
    """Take over a stale 'processing' record, assuming its original request
    died without finishing. Returns False if someone else reclaimed it
    first, in which case the caller keeps waiting."""
    record = db.get(IdempotencyRecord, key)
    if record is None or record.status != IdempotencyStatus.PROCESSING:
        return False

    record.created_at = datetime.now(UTC)
    record.request_hash = request_hash
    try:
        db.commit()
        return True
    except IntegrityError:
        db.rollback()
        return False


def complete(db: Session, key: str, status_code: int, body: Any) -> None:
    """Mark `key` completed with the response future retries should replay.

    Call this in the *same* DB transaction as the business logic it
    protects, and commit them together. That's what makes "server wrote
    the change but died before responding" safe to retry: the client
    retries with the same key, finds it already completed, and gets the
    original response instead of a double-charge.
    """
    record = db.get(IdempotencyRecord, key)
    record.status = IdempotencyStatus.COMPLETED
    record.response_status_code = status_code
    record.response_body = body
