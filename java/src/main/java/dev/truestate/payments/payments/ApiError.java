package dev.truestate.payments.payments;

/**
 * A business-rule rejection that maps straight to an HTTP status and a
 * FastAPI-style {@code {"detail": "..."}} body.
 */
public class ApiError extends RuntimeException {

    private final int status;

    public ApiError(int status, String detail) {
        super(detail);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public String detail() {
        return getMessage();
    }
}
