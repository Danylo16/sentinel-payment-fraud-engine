package dev.sentinel.fraud;

import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class VelocityRule implements FraudRule {
    private final FraudProperties config;
    public VelocityRule(FraudProperties config) { this.config = config; }
    public Optional<RuleHit> evaluate(FraudContext c) {
        return c.recentAttempts() >= config.velocityLimit()
                ? Optional.of(new RuleHit("VELOCITY", config.velocityRisk(), "Too many previous attempts in the observation window",
                    Map.of("priorAttempts", Long.toString(c.recentAttempts()), "limit", Integer.toString(config.velocityLimit()),
                           "windowSeconds", Integer.toString(config.velocityWindowSeconds())))) : Optional.empty();
    }
}
