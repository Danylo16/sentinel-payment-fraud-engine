package dev.sentinel.payments;

import dev.sentinel.shared.ForbiddenException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Profile("demo")
@RestController
@RequestMapping("/demo/fundings")
class DemoFundingController {
    record FundingRequest(@NotNull UUID targetAccountId, @NotNull @Positive Long amountCents) {}
    private final TransferService transfers;
    private final byte[] token;
    DemoFundingController(TransferService transfers, @Value("${sentinel.demo.funding-token:}") String configuredToken) {
        if (configuredToken.isBlank() || configuredToken.length() < 16)
            throw new IllegalStateException("Demo profile requires a funding token of at least 16 characters");
        this.transfers = transfers;
        this.token = configuredToken.getBytes(StandardCharsets.UTF_8);
    }
    @PostMapping
    ResponseEntity<?> fund(@RequestHeader("X-Demo-Token") String suppliedToken,
                           @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody FundingRequest request) {
        if (!MessageDigest.isEqual(token, suppliedToken.getBytes(StandardCharsets.UTF_8)))
            throw new ForbiddenException();
        return TransferResponses.respond(transfers.fundDemo(key, request.targetAccountId(), request.amountCents()));
    }
}
