package dev.truestate.payments.domain;

/** An illegal status change, e.g. CAPTURED after REFUNDED. Surfaces as HTTP 409. */
public class InvalidTransitionException extends RuntimeException {

    private final TransactionStatus from;
    private final TransactionStatus to;

    public InvalidTransitionException(TransactionStatus from, TransactionStatus to) {
        super("cannot transition from '" + from.value() + "' to '" + to.value() + "'");
        this.from = from;
        this.to = to;
    }

    public TransactionStatus from() {
        return from;
    }

    public TransactionStatus to() {
        return to;
    }
}
