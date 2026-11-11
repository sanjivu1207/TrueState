package dev.truestate.payments.payments;

import static dev.truestate.payments.domain.TransactionStatus.AUTHORIZED;
import static dev.truestate.payments.domain.TransactionStatus.CAPTURED;
import static dev.truestate.payments.domain.TransactionStatus.FAILED;
import static dev.truestate.payments.domain.TransactionStatus.PENDING;
import static dev.truestate.payments.domain.TransactionStatus.REFUNDED;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.truestate.payments.domain.Account;
import dev.truestate.payments.domain.StateMachine;
import dev.truestate.payments.domain.Timestamps;
import dev.truestate.payments.domain.Transaction;
import dev.truestate.payments.idempotency.IdempotencyService;
import dev.truestate.payments.idempotency.RequestHasher;
import dev.truestate.payments.idempotency.ReserveResult;
import dev.truestate.payments.persistence.AccountRepository;
import dev.truestate.payments.persistence.IdempotencyRepository;
import dev.truestate.payments.persistence.TransactionRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payments, capture, and refund.
 *
 * <p><b>Isolation:</b> READ COMMITTED (the Postgres default) plus explicit
 * {@code SELECT ... FOR UPDATE} row locks on exactly the rows we're about to
 * change. We don't use SERIALIZABLE: Postgres would abort conflicting
 * transactions and we'd need retry loops. Pessimistic locks make conflicting
 * requests wait their turn instead.
 *
 * <p><b>Global lock order</b> (the same in every code path, so no cycles and
 * so no deadlocks):
 * <ol>
 *   <li>the idempotency key (claimed and committed before the main transaction starts)
 *   <li>the transaction row (capture/refund only; a new payment's row is brand new, nobody else can hold it)
 *   <li>account rows, in ascending Postgres uuid order
 * </ol>
 */
@Service
public class PaymentService {

    private final IdempotencyService idempotency;
    private final IdempotencyRepository idempotencyRecords;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Timestamps timestamps;

    public PaymentService(
            IdempotencyService idempotency,
            IdempotencyRepository idempotencyRecords,
            AccountRepository accounts,
            TransactionRepository transactions,
            PlatformTransactionManager txManager,
            ObjectMapper json,
            Timestamps timestamps) {
        this.idempotency = idempotency;
        this.idempotencyRecords = idempotencyRecords;
        this.accounts = accounts;
        this.transactions = transactions;
        this.json = json;
        this.timestamps = timestamps;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * POST /payments.
     *
     * <ol>
     *   <li>Claim the idempotency key in its own, immediately committed
     *       statement. Or replay / reject / time out if it is already taken.
     *   <li>In ONE database transaction: lock both accounts, move the money,
     *       insert the transaction row, and mark the key COMPLETED with the
     *       exact response body.
     * </ol>
     * Step 2 being a single transaction is what makes "committed, then crashed
     * before replying" safe: the retry finds the key COMPLETED and replays.
     * If step 2 throws unexpectedly it rolls back entirely and the key stays
     * PROCESSING, so we never replay a response that was never produced.
     */
    public PaymentResult createPayment(String idempotencyKey, PaymentCommand cmd) {
        String requestHash = RequestHasher.hash(cmd.hashPayload());

        return switch (idempotency.reserve(idempotencyKey, requestHash)) {
            case ReserveResult.Replay r -> new PaymentResult(r.statusCode(), canonicalize(r.body()));
            case ReserveResult.HashMismatch m -> throw new ApiError(
                    422, "Idempotency-Key was already used with a different request payload");
            case ReserveResult.TimedOut t -> throw new ApiError(
                    409, "A request with this Idempotency-Key is still being processed");
            case ReserveResult.Owned o -> tx.execute(status -> {
                PaymentResult result = process(idempotencyKey, cmd);
                idempotencyRecords.complete(idempotencyKey, result.status(), result.body(), timestamps.now());
                return result;
            });
        };
    }

    /**
     * Business outcome of a payment. Runs inside the caller's transaction.
     * Business-rule failures (404/422/402) are returned as results, not
     * thrown, so they are committed and stored against the key: a retry
     * replays the same error instead of re-running the payment.
     */
    private PaymentResult process(String idempotencyKey, PaymentCommand cmd) {
        if (cmd.fromAccountId().equals(cmd.toAccountId())) {
            return detail(422, "from_account_id and to_account_id must differ");
        }

        Map<UUID, Account> locked = accounts.lockInOrder(cmd.fromAccountId(), cmd.toAccountId());
        Account from = locked.get(cmd.fromAccountId());
        Account to = locked.get(cmd.toAccountId());
        if (from == null || to == null) {
            return detail(404, "from_account_id or to_account_id not found");
        }

        OffsetDateTime now = timestamps.now();
        Transaction txn = new Transaction(
                UUID.randomUUID(),
                idempotencyKey,
                cmd.fromAccountId(),
                cmd.toAccountId(),
                cmd.amountCents(),
                PENDING,
                now,
                now);

        // This read of from.balanceCents() is safe ONLY because the row is
        // locked: nobody else can change it until we commit. Without the
        // lock this is a classic lost update (two requests both read 1000,
        // both write 0, one debit vanishes).
        if (from.balanceCents() < cmd.amountCents()) {
            // Record the failed attempt, not just an error with no trace of it.
            txn = txn.transitionTo(FAILED, now);
            transactions.insert(txn);
            ObjectNode body = json.valueToTree(txn);
            body.put("detail", "insufficient funds");
            return new PaymentResult(402, write(body));
        }

        accounts.updateBalance(from.id(), from.balanceCents() - cmd.amountCents(), now);
        accounts.updateBalance(to.id(), to.balanceCents() + cmd.amountCents(), now);
        txn = txn.transitionTo(AUTHORIZED, now);
        transactions.insert(txn);
        return new PaymentResult(201, write(txn));
    }

    /**
     * AUTHORIZED -> CAPTURED. No money moves: funds already moved at
     * authorization, so capture is the merchant confirming the charge.
     */
    public Transaction capture(UUID paymentId) {
        return tx.execute(status -> {
            Transaction txn = transactions.lockById(paymentId).orElseThrow(PaymentService::paymentNotFound);
            if (!StateMachine.isAllowed(txn.status(), CAPTURED)) {
                throw new ApiError(409, "cannot capture a payment in status '" + txn.status().value() + "'");
            }
            Transaction captured = txn.transitionTo(CAPTURED, timestamps.now());
            transactions.updateStatus(captured.id(), captured.status(), captured.updatedAt());
            return captured;
        });
    }

    /**
     * CAPTURED/SETTLED -> REFUNDED: reverse the transfer.
     *
     * <p>Locks the transaction row FIRST, then checks its status. That
     * ordering is the whole fix for concurrent double refunds: the second
     * refund blocks on the row lock until the first commits, then re-reads
     * the row, sees REFUNDED, and is rejected with 409.
     */
    public Transaction refund(UUID paymentId) {
        return tx.execute(status -> {
            Transaction txn = transactions.lockById(paymentId).orElseThrow(PaymentService::paymentNotFound);
            if (!StateMachine.isAllowed(txn.status(), REFUNDED)) {
                throw new ApiError(409, "cannot refund a payment in status '" + txn.status().value() + "'");
            }

            Map<UUID, Account> locked = accounts.lockInOrder(txn.fromAccountId(), txn.toAccountId());
            Account payer = locked.get(txn.fromAccountId());
            Account payee = locked.get(txn.toAccountId());

            // Balances never go negative in this system, so a refund is
            // refused if the payee has already spent the funds elsewhere.
            if (payee.balanceCents() < txn.amountCents()) {
                throw new ApiError(409, "cannot refund: receiving account no longer holds enough balance to reverse");
            }

            OffsetDateTime now = timestamps.now();
            accounts.updateBalance(payee.id(), payee.balanceCents() - txn.amountCents(), now);
            accounts.updateBalance(payer.id(), payer.balanceCents() + txn.amountCents(), now);
            Transaction refunded = txn.transitionTo(REFUNDED, now);
            transactions.updateStatus(refunded.id(), refunded.status(), refunded.updatedAt());
            return refunded;
        });
    }

    public Transaction get(UUID paymentId) {
        return transactions.findById(paymentId).orElseThrow(PaymentService::paymentNotFound);
    }

    private static ApiError paymentNotFound() {
        return new ApiError(404, "payment not found");
    }

    private PaymentResult detail(int status, String detail) {
        return new PaymentResult(status, write(Map.of("detail", detail)));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Stored bodies are replayed as compact JSON with their original field
     * order. This matters if the record was written by the Python service,
     * which stores {@code json} with spaces after separators.
     */
    private String canonicalize(String storedBody) {
        try {
            return json.writeValueAsString(json.readTree(storedBody));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt stored idempotency response", e);
        }
    }
}
