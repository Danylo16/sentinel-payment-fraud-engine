package dev.sentinel.fraud;

import java.util.Map;

public record RuleHit(String ruleId, int contribution, String reason, Map<String, String> evidence) {}
