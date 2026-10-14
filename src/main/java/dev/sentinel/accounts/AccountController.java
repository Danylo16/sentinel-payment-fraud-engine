package dev.sentinel.accounts;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import dev.sentinel.ledger.LedgerService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
class AccountController {
    private final AccountService service;
    private final LedgerService ledger;
    AccountController(AccountService service, LedgerService ledger) { this.service = service; this.ledger = ledger; }

    record CreateAccountRequest(@NotBlank String name) {}

    @PostMapping
    @Operation(summary = "Create a EUR account with zero balance")
    ResponseEntity<Account> create(@Valid @RequestBody CreateAccountRequest request) {
        Account account = service.create(request.name());
        return ResponseEntity.created(URI.create("/accounts/" + account.id())).body(account);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an account and its balance in cents")
    Account get(@PathVariable UUID id) { return service.get(id); }

    @GetMapping("/{id}/transactions")
    @Operation(summary = "Get immutable ledger history, newest first")
    LedgerService.HistoryPage transactions(@PathVariable UUID id,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @Positive Long beforeId) {
        service.get(id);
        return ledger.history(id, limit, beforeId);
    }
}
