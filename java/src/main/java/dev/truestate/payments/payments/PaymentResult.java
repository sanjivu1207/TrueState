package dev.truestate.payments.payments;

/**
 * The HTTP status and exact JSON body for a POST /payments response. Kept as
 * a pre-serialized string so a replay returns the stored bytes, not a fresh
 * re-rendering that could drift (timestamps, field order, formatting).
 */
public record PaymentResult(int status, String body) {}
