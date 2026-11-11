package dev.truestate.payments.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request/response bodies. JSON field names are snake_case via the global
 * Jackson naming strategy, matching the Python API. Constraint violations
 * are rendered in Pydantic's error format by {@link ApiExceptionHandler}.
 */
public final class Dtos {

    private Dtos() {}

    public record CreateAccountRequest(
            @NotNull @Size(min = 1, max = 255) String ownerName,
            @PositiveOrZero @Schema(defaultValue = "0") Long startingBalanceCents) {

        public long startingBalanceOrZero() {
            return startingBalanceCents == null ? 0 : startingBalanceCents;
        }
    }

    public record PaymentRequest(
            @NotNull UUID fromAccountId,
            @NotNull UUID toAccountId,
            @NotNull @Positive @Schema(description = "Amount in integer cents", example = "2500") Long amountCents) {}

    public record BalanceResponse(UUID id, long balanceCents) {}

    public record HealthResponse(String status, String database) {}

    public record ErrorResponse(Object detail) {}
}
