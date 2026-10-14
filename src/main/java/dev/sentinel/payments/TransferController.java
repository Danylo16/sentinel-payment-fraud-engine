package dev.sentinel.payments;

import dev.sentinel.audit.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transfers")
class TransferController {
    record CreateTransferRequest(@NotNull UUID sourceAccountId, @NotNull UUID targetAccountId,
                                 @NotNull @Positive Long amountCents) {}
    private final TransferService transfers;
    private final AuditService audit;
    private final dev.sentinel.fraud.FraudService fraud;
    TransferController(TransferService transfers, AuditService audit, dev.sentinel.fraud.FraudService fraud) { this.transfers = transfers; this.audit = audit; this.fraud = fraud; }

    @PostMapping
    @Operation(summary = "Atomically transfer EUR cents between customer accounts")
    ResponseEntity<?> create(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateTransferRequest request) {
        return TransferResponses.respond(transfers.transfer(key,
                new TransferCommand(request.sourceAccountId(), request.targetAccountId(), request.amountCents())));
    }
    @GetMapping("/{id}")
    Transfer get(@PathVariable UUID id) { return transfers.get(id); }
    @GetMapping("/{id}/fraud")
    @Operation(summary = "Read the immutable fraud decision; funding and legacy transfers return 404")
    dev.sentinel.fraud.FraudService.Decision fraud(@PathVariable UUID id) {
        transfers.get(id);
        var decision = fraud.find(id);
        if (decision == null) throw new dev.sentinel.shared.ResourceNotFoundException("Fraud decision not found");
        return decision;
    }
    @GetMapping("/{id}/audit")
    List<AuditService.AuditEvent> audit(@PathVariable UUID id) { transfers.get(id); return audit.history(id); }
}
