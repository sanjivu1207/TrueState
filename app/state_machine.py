"""The transaction state machine.

A single source of truth for which status changes are legal. Every place
that changes a Transaction's status — payment authorization, capture,
refund, and any future addition — goes through `transition()` so an illegal
jump (e.g. PENDING straight to SETTLED) is rejected everywhere, not just in
whichever endpoint someone remembered to check.

    PENDING -----> AUTHORIZED -----> CAPTURED -----> SETTLED
       |                |                |               |
       v                v                +------>--------+
     FAILED          FAILED                       v
                                               REFUNDED

REFUNDED is only reachable from CAPTURED or SETTLED — a payment that never
moved money (PENDING/FAILED) can't be refunded.
"""

from app.models import Transaction, TransactionStatus

ALLOWED_TRANSITIONS: dict[TransactionStatus, frozenset[TransactionStatus]] = {
    TransactionStatus.PENDING: frozenset({TransactionStatus.AUTHORIZED, TransactionStatus.FAILED}),
    TransactionStatus.AUTHORIZED: frozenset({TransactionStatus.CAPTURED, TransactionStatus.FAILED}),
    TransactionStatus.CAPTURED: frozenset({TransactionStatus.SETTLED, TransactionStatus.REFUNDED}),
    TransactionStatus.SETTLED: frozenset({TransactionStatus.REFUNDED}),
    TransactionStatus.FAILED: frozenset(),
    TransactionStatus.REFUNDED: frozenset(),
}


class InvalidTransitionError(Exception):
    def __init__(self, from_status: TransactionStatus, to_status: TransactionStatus):
        self.from_status = from_status
        self.to_status = to_status
        super().__init__(f"cannot transition from '{from_status.value}' to '{to_status.value}'")


def transition(transaction: Transaction, to_status: TransactionStatus) -> None:
    """Move `transaction` to `to_status` in place.

    Raises InvalidTransitionError — meant to surface as 409 — if that jump
    isn't in ALLOWED_TRANSITIONS. Does not commit; caller controls the
    transaction boundary.
    """
    allowed = ALLOWED_TRANSITIONS.get(transaction.status, frozenset())
    if to_status not in allowed:
        raise InvalidTransitionError(transaction.status, to_status)
    transaction.status = to_status
