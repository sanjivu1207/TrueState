"""Payment processing: the atomic, lock-protected balance transfer behind
POST /payments, plus capture and refund."""

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Account, Transaction, TransactionStatus
from app.schemas import PaymentCreate
from app.state_machine import ALLOWED_TRANSITIONS, transition

ALLOWED_TRANSITIONS_INTO_REFUNDED = {
    status
    for status, targets in ALLOWED_TRANSITIONS.items()
    if TransactionStatus.REFUNDED in targets
}


class PaymentProcessingError(Exception):
    """A business-rule failure (bad accounts, insufficient funds, ...).

    Distinct from an unexpected exception: callers store this outcome
    against the idempotency key (so retries replay the same error) rather
    than leaving the key stuck in 'processing'.

    `transaction`, when set, is a real (already-flushed) Transaction row the
    caller should include in the response — e.g. a FAILED record — rather
    than just an error message with no audit trail.
    """

    def __init__(self, status_code: int, detail: str, transaction: Transaction | None = None):
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail
        self.transaction = transaction


def process_payment(db: Session, idempotency_key: str, payload: PaymentCreate) -> Transaction:
    """Debit `from_account_id`, credit `to_account_id`, and record the
    transaction — atomically and safely under concurrency.

    Locks both account rows with SELECT ... FOR UPDATE, always in ascending
    account-ID order (a single query, ORDER BY id), so two transfers that
    touch the same pair of accounts — even in opposite directions — always
    request their locks in the same order and can never deadlock waiting on
    each other.

    Does not commit. The caller commits together with the idempotency
    record's completion so both persist or neither does.
    """
    if payload.from_account_id == payload.to_account_id:
        raise PaymentProcessingError(422, "from_account_id and to_account_id must differ")

    account_ids = sorted([payload.from_account_id, payload.to_account_id])
    locked_accounts = {
        account.id: account
        for account in db.execute(
            select(Account)
            .where(Account.id.in_(account_ids))
            .order_by(Account.id)
            .with_for_update()
        ).scalars()
    }

    from_account = locked_accounts.get(payload.from_account_id)
    to_account = locked_accounts.get(payload.to_account_id)
    if from_account is None or to_account is None:
        raise PaymentProcessingError(404, "from_account_id or to_account_id not found")

    # Start at PENDING explicitly (the column default only applies at flush
    # time, and transition() below needs a real status to check against
    # immediately). Every change from here on goes through transition() so
    # it's checked against ALLOWED_TRANSITIONS like any other status change
    # (capture, refund, ...).
    transaction = Transaction(
        idempotency_key=idempotency_key,
        from_account_id=payload.from_account_id,
        to_account_id=payload.to_account_id,
        amount_cents=payload.amount_cents,
        status=TransactionStatus.PENDING,
    )
    db.add(transaction)

    if from_account.balance_cents < payload.amount_cents:
        # Record the failed attempt rather than just returning an error
        # with no trace of it ever happening.
        transition(transaction, TransactionStatus.FAILED)
        db.flush()
        raise PaymentProcessingError(402, "insufficient funds", transaction=transaction)

    from_account.balance_cents -= payload.amount_cents
    to_account.balance_cents += payload.amount_cents
    transition(transaction, TransactionStatus.AUTHORIZED)
    db.flush()
    return transaction


def capture_payment(transaction: Transaction) -> None:
    """AUTHORIZED -> CAPTURED. No balance movement: in this model funds
    already moved to the receiver at authorization time, so capture is a
    pure status transition — the merchant confirming the charge. Raises
    InvalidTransitionError (-> 409) if the transaction isn't AUTHORIZED."""
    transition(transaction, TransactionStatus.CAPTURED)


def refund_payment(db: Session, transaction: Transaction) -> None:
    """CAPTURED/SETTLED -> REFUNDED: reverse the transfer (credit the
    original sender, debit the original receiver) and mark REFUNDED.

    Locks both account rows the same way process_payment does — one query,
    ascending account-ID order — so refunds and payments touching the same
    accounts can never deadlock against each other.
    """
    # Check the transition is legal before touching any balances, but don't
    # apply it yet — only commit to REFUNDED once the reversal below is
    # known to succeed.
    if transaction.status not in ALLOWED_TRANSITIONS_INTO_REFUNDED:
        transition(transaction, TransactionStatus.REFUNDED)  # raises InvalidTransitionError

    account_ids = sorted([transaction.from_account_id, transaction.to_account_id])
    locked_accounts = {
        account.id: account
        for account in db.execute(
            select(Account)
            .where(Account.id.in_(account_ids))
            .order_by(Account.id)
            .with_for_update()
        ).scalars()
    }
    from_account = locked_accounts[transaction.from_account_id]
    to_account = locked_accounts[transaction.to_account_id]

    if to_account.balance_cents < transaction.amount_cents:
        # Simplification: this project never lets a balance go negative
        # (see the overdraft test), so a refund is refused if the receiver
        # has since spent the funds elsewhere. Real processors often allow
        # this — chargebacks can push a merchant balance negative — but
        # that's a deliberate policy choice this project doesn't make.
        raise PaymentProcessingError(
            409, "cannot refund: receiving account no longer holds enough balance to reverse"
        )

    to_account.balance_cents -= transaction.amount_cents
    from_account.balance_cents += transaction.amount_cents
    transition(transaction, TransactionStatus.REFUNDED)
    db.flush()
