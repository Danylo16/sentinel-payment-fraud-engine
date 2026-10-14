package dev.sentinel.fraud;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("sentinel.fraud")
public record FraudProperties(
        @DefaultValue("40") int reviewThreshold,
        @DefaultValue("70") int declineThreshold,
        @DefaultValue("100000") long largeAmountCents,
        @DefaultValue("35") int largeAmountRisk,
        @DefaultValue("5") int velocityLimit,
        @DefaultValue("60") int velocityWindowSeconds,
        @DefaultValue("30") int velocityRisk,
        @DefaultValue("20") int newRecipientRisk,
        @DefaultValue("5") int minimumHistory,
        @DefaultValue("3") int deviationMultiplier,
        @DefaultValue("35") int deviationRisk) {
    public FraudProperties {
        if (reviewThreshold < 1 || reviewThreshold >= declineThreshold || declineThreshold > 100
                || largeAmountCents < 1 || velocityLimit < 1 || velocityWindowSeconds < 1
                || minimumHistory < 1 || deviationMultiplier < 2)
            throw new IllegalArgumentException("Invalid fraud thresholds or observation settings");
        for (int risk : new int[]{largeAmountRisk, velocityRisk, newRecipientRisk, deviationRisk})
            if (risk < 1 || risk > 100) throw new IllegalArgumentException("Rule contributions must be 1..100");
    }
}
