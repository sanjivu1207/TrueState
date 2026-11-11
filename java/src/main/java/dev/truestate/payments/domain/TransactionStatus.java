package dev.truestate.payments.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle of a payment. Persisted as the UPPERCASE enum name (matching the
 * {@code transaction_status} Postgres enum the Python service created) and
 * exposed in the API as the lowercase {@link #value()}.
 */
public enum TransactionStatus {
    PENDING,
    AUTHORIZED,
    CAPTURED,
    SETTLED,
    FAILED,
    REFUNDED;

    @JsonValue
    public String value() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
