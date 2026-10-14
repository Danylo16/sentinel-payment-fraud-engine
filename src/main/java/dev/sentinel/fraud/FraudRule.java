package dev.sentinel.fraud;

import java.util.Optional;

public interface FraudRule {
    Optional<RuleHit> evaluate(FraudContext context);
}
