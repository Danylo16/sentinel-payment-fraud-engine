package dev.sentinel.fraud;

import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class NewRecipientRule implements FraudRule {
    private final FraudProperties config;
    public NewRecipientRule(FraudProperties config) { this.config = config; }
    public Optional<RuleHit> evaluate(FraudContext c) {
        return !c.knownRecipient() ? Optional.of(new RuleHit("NEW_RECIPIENT", config.newRecipientRisk(),
                "No completed transfer to this recipient", Map.of("knownRecipient", "false"))) : Optional.empty();
    }
}
