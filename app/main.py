import uuid
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import Session

from app import idempotency
from app.database import Base, engine, get_db
from app.models import Account, Transaction
from app.payments import (
    PaymentProcessingError,
    capture_payment,
    process_payment,
    refund_payment,
)
from app.schemas import (
    AccountCreate,
    AccountRead,
    BalanceRead,
    PaymentCreate,
    TransactionRead,
)
from app.state_machine import InvalidTransitionError


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Phase 1: create tables directly. Swap for Alembic migrations once the
    # schema stabilizes.
    Base.metadata.create_all(bind=engine)
    yield


app = FastAPI(title="Idempotent Payment Processing API", lifespan=lifespan)


@app.get("/health")
def health(db: Session = Depends(get_db)):
    try:
        db.execute(select(1))
        db_status = "ok"
    except OperationalError:
        db_status = "unavailable"
    return {"status": "ok", "database": db_status}


@app.post("/accounts", response_model=AccountRead, status_code=201)
def create_account(payload: AccountCreate, db: Session = Depends(get_db)):
    account = Account(
        owner_name=payload.owner_name,
        balance_cents=payload.starting_balance_cents,
    )
    db.add(account)
    db.commit()
    db.refresh(account)
    return account


@app.get("/accounts/{account_id}/balance", response_model=BalanceRead)
def get_balance(account_id: uuid.UUID, db: Session = Depends(get_db)):
    account = db.get(Account, account_id)
    if account is None:
        raise HTTPException(status_code=404, detail="account not found")
    return BalanceRead(id=account.id, balance_cents=account.balance_cents)


@app.post("/payments", response_model=TransactionRead, status_code=201)
def create_payment(
    payload: PaymentCreate,
    db: Session = Depends(get_db),
    idempotency_key: str = Header(..., alias="Idempotency-Key"),
):
    request_hash = idempotency.hash_request(jsonable_encoder(payload))
    outcome = idempotency.reserve(db, idempotency_key, request_hash)

    if isinstance(outcome, idempotency.Replay):
        # Same key, same payload, already processed: return the original
        # result instead of doing the work again.
        return JSONResponse(status_code=outcome.status_code, content=outcome.body)

    if isinstance(outcome, idempotency.HashMismatch):
        raise HTTPException(
            status_code=422,
            detail="Idempotency-Key was already used with a different request payload",
        )

    if isinstance(outcome, idempotency.TimedOut):
        raise HTTPException(
            status_code=409,
            detail="A request with this Idempotency-Key is still being processed",
        )

    # outcome is None: we now own the key and must process the request.
    try:
        transaction = process_payment(db, idempotency_key, payload)
        body = jsonable_encoder(TransactionRead.model_validate(transaction))
        idempotency.complete(db, idempotency_key, 201, body)
        db.commit()
        return JSONResponse(status_code=201, content=body)
    except PaymentProcessingError as exc:
        # Nothing to roll back: same-account/unknown-account errors raise
        # before any write, and insufficient-funds already flushed a FAILED
        # transaction we deliberately want to keep, not undo.
        if exc.transaction is not None:
            body = jsonable_encoder(TransactionRead.model_validate(exc.transaction))
            body["detail"] = exc.detail
        else:
            body = {"detail": exc.detail}
        idempotency.complete(db, idempotency_key, exc.status_code, body)
        db.commit()
        return JSONResponse(status_code=exc.status_code, content=body)
    except Exception:
        # Unexpected failure: do NOT mark the key completed. Leave it
        # 'processing' so a retry either waits for a live concurrent
        # attempt, or — once it goes stale — safely tries again from
        # scratch, instead of replaying a response that was never produced.
        db.rollback()
        raise


@app.get("/payments/{payment_id}", response_model=TransactionRead)
def get_payment(payment_id: uuid.UUID, db: Session = Depends(get_db)):
    transaction = db.get(Transaction, payment_id)
    if transaction is None:
        raise HTTPException(status_code=404, detail="payment not found")
    return transaction


@app.post("/payments/{payment_id}/capture", response_model=TransactionRead)
def capture(payment_id: uuid.UUID, db: Session = Depends(get_db)):
    transaction = db.get(Transaction, payment_id)
    if transaction is None:
        raise HTTPException(status_code=404, detail="payment not found")
    try:
        capture_payment(transaction)
    except InvalidTransitionError as exc:
        raise HTTPException(
            status_code=409,
            detail=f"cannot capture a payment in status '{exc.from_status.value}'",
        ) from exc
    db.commit()
    db.refresh(transaction)
    return transaction


@app.post("/payments/{payment_id}/refund", response_model=TransactionRead)
def refund(payment_id: uuid.UUID, db: Session = Depends(get_db)):
    transaction = db.get(Transaction, payment_id)
    if transaction is None:
        raise HTTPException(status_code=404, detail="payment not found")
    try:
        refund_payment(db, transaction)
    except InvalidTransitionError as exc:
        db.rollback()
        raise HTTPException(
            status_code=409,
            detail=f"cannot refund a payment in status '{exc.from_status.value}'",
        ) from exc
    except PaymentProcessingError as exc:
        db.rollback()
        raise HTTPException(status_code=exc.status_code, detail=exc.detail) from exc
    db.commit()
    db.refresh(transaction)
    return transaction
