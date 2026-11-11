package dev.truestate.payments.payments;

import java.util.Map;
import java.util.UUID;

/** A validated payment request. */
public record PaymentCommand(UUID fromAccountId, UUID toAccountId, long amountCents) {

    /**
     * The request body as JSON-ready primitives, for hashing. Same field
     * names and value forms (UUIDs as lowercase strings) as the Python
     * service hashes, so both compute identical hashes for the same body.
     */
    public Map<String, Object> hashPayload() {
        return Map.of(
                "from_account_id", fromAccountId.toString(),
                "to_account_id", toAccountId.toString(),
                "amount_cents", amountCents);
    }
}
