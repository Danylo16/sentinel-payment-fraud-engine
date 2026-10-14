package dev.sentinel.fraud;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FraudEngine {
    public record Assessment(int score, String decision, List<RuleHit> rules) {}
    private final List<FraudRule> rules;
    private final FraudProperties config;
    public FraudEngine(List<FraudRule> rules, FraudProperties config) { this.rules = List.copyOf(rules); this.config = config; }
    public Assessment assess(FraudContext context) {
        var hits = rules.stream().flatMap(rule -> rule.evaluate(context).stream())
                .sorted(Comparator.comparing(RuleHit::ruleId)).toList();
        int score = (int) Math.min(100L, hits.stream().mapToLong(RuleHit::contribution).sum());
        return new Assessment(score, score >= config.declineThreshold() ? "DECLINE"
                : score >= config.reviewThreshold() ? "REVIEW" : "APPROVE", hits);
    }
}
