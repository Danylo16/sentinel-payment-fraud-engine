package dev.sentinel.payments;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Transfer(UUID id, String operation, UUID sourceAccountId, UUID targetAccountId,
                       long amountCents, String currency, String status, String failureReason,
                       OffsetDateTime createdAt) {}
