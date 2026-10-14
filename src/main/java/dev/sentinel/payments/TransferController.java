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
    @Operation(summary = "Assess risk and create a EUR transfer; inspect status to determine settlement")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="201", description="Created COMPLETED or DECLINED transfer", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=Transfer.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="202", description="REVIEW; funds neither moved nor reserved", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=Transfer.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="Idempotent replay of completed, reviewed or declined transfer", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=Transfer.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409", description="Persisted balance rejection or idempotency conflict", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=dev.sentinel.shared.ApiExceptionHandler.ApiError.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="503", description="Retryable transaction failure; retry the SAME key")
    })
    ResponseEntity<?> create(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateTransferRequest request) {
        return TransferResponses.respond(transfers.transfer(key,
                new TransferCommand(request.sourceAccountId(), request.targetAccountId(), request.amountCents())));
    }
    @GetMapping
    @Operation(summary = "List transfers and actual fraud scores using a newest-first cursor")
    TransferService.TransferPage list(@RequestParam(defaultValue = "50") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) int limit,
                                      @RequestParam(required = false) UUID beforeId) {
        return transfers.page(limit, beforeId);
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
