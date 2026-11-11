# TrueState — Java / Spring Boot port

A Spring Boot 3 (Java 21) reimplementation of the Python/FastAPI service in
the repo root. Same endpoints, same request and response bodies, same
status codes, same PostgreSQL schema. The two services can share one
database and honour each other's idempotency keys.

| | Python | Java |
|---|---|---|
| Port | 8000 | 8080 |
| Swagger UI | `/docs` | `/docs` (springdoc) |
| OpenAPI spec | `/openapi.json` | `/openapi.json` |
| Schema management | SQLAlchemy `create_all` | Flyway (`V1__baseline_schema.sql`) |
| Data access | SQLAlchemy ORM | `JdbcTemplate` (plain SQL) |

## Run it

```bash
# Everything (Postgres + Python API + Java API) from the repo root:
docker compose up --build

# Or just the Java service against the compose database:
docker compose up -d db
cd java && mvn spring-boot:run          # needs JDK 21 + Maven
```

Config is via environment variables: `DATABASE_URL` (JDBC form, e.g.
`jdbc:postgresql://localhost:5432/payments`), `DATABASE_USER`,
`DATABASE_PASSWORD`, `PORT`, `DB_POOL_SIZE`.

## Code map

```
api/          Controllers, DTOs, JSON config, FastAPI-compatible error handler
payments/     PaymentService: transaction boundaries, lock order, business rules
idempotency/  Key reservation / replay / conflict logic, request hashing
domain/       Account, Transaction, TransactionStatus, StateMachine
persistence/  JdbcTemplate repositories: every SELECT ... FOR UPDATE lives here
resources/db/migration/V1__baseline_schema.sql
```

## Design decisions

### Why JdbcTemplate instead of JPA/Hibernate?

The correctness of this service comes down to exactly which rows get locked,
in what order, and when. With `JdbcTemplate`, each lock is a literal
`SELECT ... FOR UPDATE` you can read in `AccountRepository` and
`TransactionRepository`. JPA can do the same (`@Lock(PESSIMISTIC_WRITE)`), but
it adds a persistence context, dirty checking, and flush timing between the
code and the SQL. Those are good for CRUD apps and get in the way when the
point is to reason precisely about locks.

### Transaction isolation: READ COMMITTED plus explicit row locks

Every operation runs in one database transaction at **READ COMMITTED**,
Postgres's default. On its own, READ COMMITTED does *not* prevent a lost
update:

```
request A: SELECT balance → 1000          request B: SELECT balance → 1000
request A: UPDATE balance = 0             request B: UPDATE balance = 0     ← A's debit is lost
```

So before reading a balance it will change, the code locks the row with
`SELECT ... FOR UPDATE`. A second request that wants the same row **waits**
until the first commits, then reads the new value. Conflicting requests take
turns, and none of them fails.

The alternative is **SERIALIZABLE**. Postgres would detect the conflict and
*abort* one transaction with a serialization error, and the app would need a
retry loop around every operation. Pessimistic locks are simpler here
because the rows that conflict are known in advance: the two accounts in the
transfer.

### How the locks avoid deadlocks: one global order

A deadlock needs a cycle: A holds row 1 and waits for row 2, while B holds
row 2 and waits for row 1. A transfer from Alice to Bob that locks "sender
first" and a transfer from Bob to Alice that also locks "sender first" form
exactly that cycle.

The fix is to have everyone lock in the **same order**, so no cycle can form:

1. **Idempotency key.** It is claimed and committed *before* the main
   transaction starts, so it isn't held during the rest.
2. **The transaction row** (capture/refund only). A new payment inserts a
   brand-new row that nobody else can be waiting on.
3. **Account rows, lowest id first.**

One Java detail matters here. `UUID.compareTo` compares the two 64-bit halves
as **signed** numbers, while Postgres orders `uuid` values as unsigned bytes.
They disagree for about half of all pairs. `AccountRepository` therefore
sorts with an unsigned comparator that matches Postgres, so the Java service
locks in the same order as the Python service, which sorts by the UUID's
unsigned integer value. That keeps the two deadlock-free even when they
share a database.

### Idempotency-key replay

The client sends `Idempotency-Key: <uuid>` with each payment and reuses it on
retries. The server records each key in `idempotency_records`.

1. **Claim the key** with a single statement:
   `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING`. If it inserts a
   row, this request owns the key. The statement auto-commits immediately,
   so every other request can see the claim before any money moves. If
   another request inserted the same key but hasn't committed yet, Postgres
   makes this insert wait for it, so exactly one request wins.
2. **If the key already exists**, read it:
   - **Different request body.** A SHA-256 of the body is stored with the key,
     and a mismatch returns `422`. A reused key must not let a different
     payment through.
   - **COMPLETED.** Return the stored status code and body exactly as stored.
     Nothing is reprocessed.
   - **Still PROCESSING.** This is a concurrent duplicate that is still
     running. Poll every 50 ms for up to 5 s and then replay its result.
     This is how 50 simultaneous identical requests all receive the same
     `201` body. If it never finishes, return `409`.
   - **PROCESSING for more than 30 s.** The original request most likely
     crashed. A conditional
     `UPDATE ... WHERE status='PROCESSING' AND created_at < cutoff` lets
     exactly one waiter take the key over (compare-and-set).
3. **Do the work and complete the key in the same transaction.** The account
   locks, balance updates, transaction row, and
   `UPDATE idempotency_records SET status='COMPLETED', response_body=...` all
   commit together. If the server crashes after the commit but before it
   replies, the client's retry finds COMPLETED and gets the original
   response. If anything unexpected throws, the whole transaction rolls back
   and the key stays PROCESSING, so the service never replays a response
   that was never produced.

Business-rule failures (insufficient funds `402`, unknown account `404`,
same-account `422`) are stored against the key like successes, so a retry
gets the same answer. A `402` also writes a `FAILED` transaction row as an
audit trail.

A unique index on `transactions.idempotency_key` is the backstop: if two
requests ever both believed they owned a key, the second `INSERT` would fail
rather than move money twice.

**Cross-service compatibility.** The request hash is SHA-256 over
sorted-key, whitespace-free JSON, the same bytes Python's
`json.dumps(sort_keys=True, separators=(",", ":"))` produces. A payment made
through the Python API and retried with the same key against the Java API is
replayed, not reprocessed.

### State machine

`StateMachine` holds the single allow-list of status changes, identical to
`app/state_machine.py`:

```
PENDING → AUTHORIZED | FAILED
AUTHORIZED → CAPTURED | FAILED
CAPTURED → SETTLED | REFUNDED
SETTLED → REFUNDED
FAILED, REFUNDED → (terminal)
```

Every status change goes through `Transaction.transitionTo`, which validates
the move against that list. An illegal change returns `409`. There is no
settle endpoint (also true of the Python API), so `SETTLED` is defined but
cannot currently be reached through the API.

### Integer cents

All amounts are `long` cents in Java and `bigint` in Postgres. No `double`,
no `BigDecimal`. Floating point can't represent most decimal fractions
exactly (`0.1 + 0.2 != 0.3`), and integer cents avoid both that problem and
any rounding policy.

### Byte-identical replays

`POST /payments` returns a pre-serialized JSON string, not an object for
Spring to render. The string the client receives is the same one stored in
`idempotency_records.response_body`, so a replay returns the same bytes.
Timestamps are truncated to microseconds (Postgres's precision) before being
written, so the value in the response matches the value in the database.

### Differences from the Python service

- **Refund/capture lock the transaction row.** Python reads the payment's
  status without a lock before reversing funds. A probe of 20 concurrent
  refunds on one captured payment refunded it **up to 5 times** on the
  Python service (4 of 5 trials). The same probe on this service allowed
  exactly one refund every time.
- **Stale-key reclaim is a compare-and-set** (one conditional `UPDATE`).
  Python reads and then writes, so two waiters could both reclaim.
- **Validation errors**: same `{"detail": [{type, loc, msg, input, ctx}]}`
  shape and messages. One known difference: for a missing body field,
  Pydantic echoes the whole request body as `input`, while this service
  returns `null`.
