package dev.sentinel.fraud;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FraudEngineTest {
    private final FraudProperties config = new FraudProperties(40,70,100000,35,5,60,30,20,5,3,35);
    private FraudContext context(long amount, long recent, boolean known, long count, String average) {
        return new FraudContext(amount,recent,known,count,average==null?null:new BigDecimal(average));
    }
    private FraudEngine engine() {
        return new FraudEngine(List.of(new LargeAmountRule(config), new VelocityRule(config),
                new NewRecipientRule(config), new AmountDeviationRule(config)), config);
    }
    @Test void largeAmountBoundaryIsInclusive() {
        var rule = new LargeAmountRule(config);
        assertTrue(rule.evaluate(context(99999,0,true,0,null)).isEmpty());
        assertEquals(35, rule.evaluate(context(100000,0,true,0,null)).orElseThrow().contribution());
    }
    @Test void velocityCountsPreviousAttemptsNotCurrent() {
        var rule = new VelocityRule(config);
        assertTrue(rule.evaluate(context(1,4,true,0,null)).isEmpty());
        var hit = rule.evaluate(context(1,5,true,0,null)).orElseThrow();
        assertEquals("5",hit.evidence().get("priorAttempts"));
    }
    @Test void newRecipientNeedsCompletedHistory() {
        var rule = new NewRecipientRule(config);
        assertTrue(rule.evaluate(context(1,0,true,0,null)).isEmpty());
        assertEquals("NEW_RECIPIENT",rule.evaluate(context(1,0,false,0,null)).orElseThrow().ruleId());
    }
    @Test void deviationNeedsEnoughHistory() {
        var rule = new AmountDeviationRule(config);
        assertTrue(rule.evaluate(context(1000,0,true,4,"100")).isEmpty());
        assertTrue(rule.evaluate(context(299,0,true,5,"100")).isEmpty());
        assertEquals(35,rule.evaluate(context(300,0,true,5,"100")).orElseThrow().contribution());
    }
    @Test void deviationDoesNotRoundFractionalAverageOrOverflow() {
        var rule = new AmountDeviationRule(config);
        assertTrue(rule.evaluate(context(300,0,true,5,"100.1")).isEmpty());
        assertTrue(rule.evaluate(context(Long.MAX_VALUE,0,true,5,Long.toString(Long.MAX_VALUE))).isEmpty());
    }
    @Test void scoringBoundariesAndCapAreExact() {
        for (int score : new int[]{0,39,40,69,70,100,150}) {
            FraudRule rule = c -> score==0?Optional.empty():Optional.of(new RuleHit("TEST",score,"fixture",Map.of()));
            var assessment = new FraudEngine(List.of(rule),config).assess(context(1,0,true,0,null));
            assertEquals(Math.min(score,100),assessment.score());
            assertEquals(score>=70?"DECLINE":score>=40?"REVIEW":"APPROVE",assessment.decision());
        }
    }
    @Test void contributionsCombineAndOutputOrderIsStable() {
        var result = engine().assess(context(100000,5,false,5,"100"));
        assertEquals(100,result.score()); assertEquals("DECLINE",result.decision());
        assertEquals(List.of("AMOUNT_DEVIATION","LARGE_AMOUNT","NEW_RECIPIENT","VELOCITY"),
                result.rules().stream().map(RuleHit::ruleId).toList());
    }
    @Test void configuredThresholdsChangeDecision() {
        var changed = new FraudProperties(60,90,100000,35,5,60,30,20,5,3,35);
        var result = new FraudEngine(List.of(new LargeAmountRule(changed),new NewRecipientRule(changed)),changed)
                .assess(context(100000,0,false,0,null));
        assertEquals(55,result.score()); assertEquals("APPROVE",result.decision());
    }
    @Test void invalidConfigurationFailsFast() {
        assertThrows(IllegalArgumentException.class,()->new FraudProperties(70,40,100,35,5,60,30,20,5,3,35));
        assertThrows(IllegalArgumentException.class,()->new FraudProperties(40,70,100,101,5,60,30,20,5,3,35));
        assertThrows(IllegalArgumentException.class,()->new FraudProperties(40,70,100,35,0,60,30,20,5,3,35));
    }
    @Test void newRecipientAloneProducesTwentyAndApprove() {
	
    	var context = new FraudContext(
            100L,
            0L,
            false,
            0L,
            null
    	);

    	var result = engine().assess(context);

    	assertEquals(20, result.score());
    	assertEquals("APPROVE", result.decision());
    	assertEquals(1, result.rules().size());
    	assertEquals("NEW_RECIPIENT", result.rules().get(0).ruleId());
   }
}
