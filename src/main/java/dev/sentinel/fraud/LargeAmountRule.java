package dev.sentinel.fraud;

import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class LargeAmountRule implements FraudRule {
    private final FraudProperties config;
    public LargeAmountRule(FraudProperties config) { this.config = config; }
    public Optional<RuleHit> evaluate(FraudContext c) {
        return c.amountCents() >= config.largeAmountCents()
                ? Optional.of(new RuleHit("LARGE_AMOUNT", config.largeAmountRisk(), "Amount reaches large-transfer threshold",
                    Map.of("amountCents", Long.toString(c.amountCents()), "thresholdCents", Long.toString(config.largeAmountCents()))))
                : Optional.empty();
    }
}
