package dev.sentinel.payments;

import dev.sentinel.shared.RequestValidationException;
import java.util.UUID;

public record TransferCommand(UUID sourceAccountId, UUID targetAccountId, long amountCents) {
    public TransferCommand {
        if (sourceAccountId == null || targetAccountId == null)
            throw new RequestValidationException("Both account IDs are required");
        if (sourceAccountId.equals(targetAccountId))
            throw new RequestValidationException("Source and target accounts must differ");
        if (amountCents <= 0) throw new RequestValidationException("Amount must be positive cents");
    }
    public static void validateKey(String key) {
        if (key == null || !key.matches("[!-~]{1,128}"))
            throw new RequestValidationException("Idempotency-Key must contain 1 to 128 printable non-space ASCII characters");
    }
}
