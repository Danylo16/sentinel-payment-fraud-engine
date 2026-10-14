package dev.sentinel.fraud;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class AmountDeviationRule implements FraudRule {
    private final FraudProperties config;
    public AmountDeviationRule(FraudProperties config) { this.config = config; }
    public Optional<RuleHit> evaluate(FraudContext c) {
        if (c.historyCount() < config.minimumHistory() || c.averageCents() == null) return Optional.empty();
        BigDecimal threshold = c.averageCents().multiply(BigDecimal.valueOf(config.deviationMultiplier()));
        return BigDecimal.valueOf(c.amountCents()).compareTo(threshold) >= 0
                ? Optional.of(new RuleHit("AMOUNT_DEVIATION", config.deviationRisk(), "Amount reaches a multiple of the 30-day average",
                    Map.of("averageCents", c.averageCents().toPlainString(), "historyCount", Long.toString(c.historyCount()),
                           "multiplier", Integer.toString(config.deviationMultiplier()), "amountCents", Long.toString(c.amountCents()))))
                : Optional.empty();
    }
}
