package dev.sentinel.fraud;

import java.math.BigDecimal;

/** Database observations captured after locking the source account. Current attempt is excluded. */
public record FraudContext(long amountCents, long recentAttempts, boolean knownRecipient,
                           long historyCount, BigDecimal averageCents) {}
