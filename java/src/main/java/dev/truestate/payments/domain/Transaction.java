package dev.truestate.payments.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A payment between two accounts. Field order matches the Python API's response body. */
public record Transaction(
        UUID id,
        String idempotencyKey,
        UUID fromAccountId,
        UUID toAccountId,
        long amountCents,
        TransactionStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /** Validates the move against the state machine and returns the updated copy. */
    public Transaction transitionTo(TransactionStatus next, OffsetDateTime now) {
        return new Transaction(
                id,
                idempotencyKey,
                fromAccountId,
                toAccountId,
                amountCents,
                StateMachine.transition(status, next),
                createdAt,
                now);
    }
}
