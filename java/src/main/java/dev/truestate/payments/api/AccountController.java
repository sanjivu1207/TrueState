package dev.truestate.payments.api;

import dev.truestate.payments.api.Dtos.BalanceResponse;
import dev.truestate.payments.api.Dtos.CreateAccountRequest;
import dev.truestate.payments.domain.Account;
import dev.truestate.payments.domain.Timestamps;
import dev.truestate.payments.payments.ApiError;
import dev.truestate.payments.persistence.AccountRepository;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {

    private final AccountRepository accounts;
    private final Timestamps timestamps;

    public AccountController(AccountRepository accounts, Timestamps timestamps) {
        this.accounts = accounts;
        this.timestamps = timestamps;
    }

    @Operation(summary = "Create an account")
    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public Account create(@Valid @RequestBody CreateAccountRequest req) {
        OffsetDateTime now = timestamps.now();
        Account account = new Account(UUID.randomUUID(), req.ownerName(), req.startingBalanceOrZero(), now, now);
        accounts.insert(account);
        return account;
    }

    @Operation(summary = "Check balance")
    @GetMapping("/accounts/{accountId}/balance")
    public BalanceResponse balance(@PathVariable UUID accountId) {
        Account account = accounts.findById(accountId).orElseThrow(() -> new ApiError(404, "account not found"));
        return new BalanceResponse(account.id(), account.balanceCents());
    }
}
