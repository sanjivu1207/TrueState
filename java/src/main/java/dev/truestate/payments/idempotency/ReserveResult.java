package dev.truestate.payments.idempotency;

/** What {@link IdempotencyService#reserve} decided about an incoming key. */
public sealed interface ReserveResult {

    /** Fresh (or reclaimed) key: the caller now owns it and must process the request. */
    record Owned() implements ReserveResult {}

    /** Same key and payload already completed: return the stored response verbatim. */
    record Replay(int statusCode, String body) implements ReserveResult {}

    /** Same key, different payload: the client is misusing the key (422). */
    record HashMismatch() implements ReserveResult {}

    /** A concurrent request still holds the key and didn't finish in time (409). */
    record TimedOut() implements ReserveResult {}
}
