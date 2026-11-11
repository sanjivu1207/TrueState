package dev.truestate.payments.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** An account row. Balances are integer cents; there is no floating-point money anywhere. */
public record Account(
        UUID id, String ownerName, long balanceCents, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    public Account withBalance(long newBalanceCents, OffsetDateTime now) {
        return new Account(id, ownerName, newBalanceCents, createdAt, now);
    }
}
