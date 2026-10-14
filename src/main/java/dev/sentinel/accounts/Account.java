package dev.sentinel.accounts;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Account(UUID id, String name, String currency, long balanceCents, OffsetDateTime createdAt) {}
