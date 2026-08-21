import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field

from app.models import TransactionStatus


class AccountCreate(BaseModel):
    owner_name: str = Field(min_length=1, max_length=255)
    starting_balance_cents: int = Field(ge=0, default=0)


class AccountRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    owner_name: str
    balance_cents: int
    created_at: datetime
    updated_at: datetime


class BalanceRead(BaseModel):
    id: uuid.UUID
    balance_cents: int


class PaymentCreate(BaseModel):
    from_account_id: uuid.UUID
    to_account_id: uuid.UUID
    amount_cents: int = Field(gt=0)


class TransactionRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    idempotency_key: str
    from_account_id: uuid.UUID
    to_account_id: uuid.UUID
    amount_cents: int
    status: TransactionStatus
    created_at: datetime
    updated_at: datetime
