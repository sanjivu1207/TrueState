-- Baseline schema: an exact reproduction of what the Python service's
-- SQLAlchemy models create (verified with pg_dump --schema-only diff).
--
-- Both services can share one database:
--   * Fresh database  -> Flyway runs this file and creates everything.
--   * Database already created by the Python service -> Flyway sees a
--     non-empty schema with no history table, baselines at V1
--     (spring.flyway.baseline-on-migrate) and skips this file.
--
-- Note the enum labels are the UPPERCASE member names (that's what
-- SQLAlchemy's Enum type persists); the API exposes lowercase values.

CREATE TYPE idempotency_status AS ENUM (
    'PROCESSING',
    'COMPLETED'
);

CREATE TYPE transaction_status AS ENUM (
    'PENDING',
    'AUTHORIZED',
    'CAPTURED',
    'SETTLED',
    'FAILED',
    'REFUNDED'
);

CREATE TABLE accounts (
    id            uuid                     NOT NULL,
    owner_name    character varying(255)   NOT NULL,
    -- Money is always integer cents. Never floats.
    balance_cents bigint                   NOT NULL,
    version       bigint                   NOT NULL,
    created_at    timestamp with time zone NOT NULL,
    updated_at    timestamp with time zone NOT NULL,
    CONSTRAINT accounts_pkey PRIMARY KEY (id)
);

CREATE TABLE idempotency_records (
    idempotency_key      character varying(255)   NOT NULL,
    request_hash         character varying(64)    NOT NULL,
    status               idempotency_status       NOT NULL,
    response_status_code integer,
    response_body        json,
    created_at           timestamp with time zone NOT NULL,
    updated_at           timestamp with time zone NOT NULL,
    CONSTRAINT idempotency_records_pkey PRIMARY KEY (idempotency_key)
);

CREATE TABLE transactions (
    id              uuid                     NOT NULL,
    idempotency_key character varying(255)   NOT NULL,
    from_account_id uuid                     NOT NULL,
    to_account_id   uuid                     NOT NULL,
    amount_cents    bigint                   NOT NULL,
    status          transaction_status       NOT NULL,
    created_at      timestamp with time zone NOT NULL,
    updated_at      timestamp with time zone NOT NULL,
    CONSTRAINT transactions_pkey PRIMARY KEY (id),
    CONSTRAINT transactions_from_account_id_fkey FOREIGN KEY (from_account_id) REFERENCES accounts (id),
    CONSTRAINT transactions_to_account_id_fkey FOREIGN KEY (to_account_id) REFERENCES accounts (id)
);

-- One transaction per idempotency key, enforced by the database. This is
-- the last line of defence if two requests ever both believed they owned
-- the same key: the second INSERT fails instead of moving money twice.
CREATE UNIQUE INDEX ix_transactions_idempotency_key ON transactions USING btree (idempotency_key);
