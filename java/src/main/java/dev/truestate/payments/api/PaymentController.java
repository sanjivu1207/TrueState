package dev.truestate.payments.api;

import dev.truestate.payments.api.Dtos.ErrorResponse;
import dev.truestate.payments.api.Dtos.PaymentRequest;
import dev.truestate.payments.domain.Transaction;
import dev.truestate.payments.payments.PaymentCommand;
import dev.truestate.payments.payments.PaymentResult;
import dev.truestate.payments.payments.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @Operation(
            summary = "Initiate a payment",
            description = "Requires an Idempotency-Key header. Retrying with the same key and body replays the"
                    + " original response byte-for-byte; the same key with a different body is rejected (422).")
    @ApiResponse(
            responseCode = "201",
            description = "Authorized: funds moved",
            content = @Content(schema = @Schema(implementation = Transaction.class)))
    @ApiResponse(
            responseCode = "402",
            description = "Insufficient funds: a FAILED transaction is recorded and returned, plus a detail field",
            content = @Content(schema = @Schema(implementation = Transaction.class)))
    @ApiResponse(responseCode = "404", description = "Unknown account", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Same key still in flight", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "Invalid body, missing key, key reused with different body, or same-account transfer", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/payments")
    public ResponseEntity<String> create(
            @Parameter(description = "Client-chosen unique key for this logical payment; reuse it on retries")
                    @RequestHeader("Idempotency-Key")
                    String idempotencyKey,
            @Valid @RequestBody PaymentRequest req) {
        PaymentResult result = payments.createPayment(
                idempotencyKey, new PaymentCommand(req.fromAccountId(), req.toAccountId(), req.amountCents()));
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }

    @Operation(summary = "Check a payment's status")
    @GetMapping("/payments/{paymentId}")
    public Transaction get(@PathVariable UUID paymentId) {
        return payments.get(paymentId);
    }

    @Operation(summary = "AUTHORIZED -> CAPTURED")
    @PostMapping("/payments/{paymentId}/capture")
    public Transaction capture(@PathVariable UUID paymentId) {
        return payments.capture(paymentId);
    }

    @Operation(summary = "CAPTURED/SETTLED -> REFUNDED (reverses the transfer)")
    @PostMapping("/payments/{paymentId}/refund")
    public Transaction refund(@PathVariable UUID paymentId) {
        return payments.refund(paymentId);
    }
}
