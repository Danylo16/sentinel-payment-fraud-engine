package dev.sentinel.accounts;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
class AccountController {
    private final AccountService service;
    AccountController(AccountService service) { this.service = service; }

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
}
