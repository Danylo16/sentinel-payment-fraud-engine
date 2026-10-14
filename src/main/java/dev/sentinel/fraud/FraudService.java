package dev.sentinel.fraud;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class FraudService {
    public record Decision(UUID transferId, String engineVersion, OffsetDateTime evaluatedAt, int score,
                           String decision, int reviewThreshold, int declineThreshold, List<RuleHit> rules) {}
    private final DSLContext sql;
    private final FraudEngine engine;
    private final FraudProperties config;
    private final ObjectMapper json = new ObjectMapper();
    public FraudService(DSLContext sql, FraudEngine engine, FraudProperties config) {
        this.sql = sql; this.engine = engine; this.config = config;
    }

    /** Caller holds both account locks; every normal transfer follows the same lock protocol. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Decision evaluate(UUID id, UUID source, UUID target, long amount) {
        OffsetDateTime now = sql.fetchOne("SELECT clock_timestamp() AS now").get("now", OffsetDateTime.class);
        long recent = sql.fetchOne("""
                SELECT count(*) AS n FROM fraud_decisions f JOIN transfers t ON t.id=f.transfer_id
                WHERE t.source_account_id=? AND f.evaluated_at >= ?::timestamptz AND f.evaluated_at <= ?::timestamptz
                """, source, now.minusSeconds(config.velocityWindowSeconds()), now).get("n", Long.class);
        boolean known = sql.fetchOne("""
                SELECT EXISTS (SELECT 1 FROM transfers WHERE source_account_id=? AND target_account_id=?
                AND operation='TRANSFER' AND status='COMPLETED') AS known
                """, source, target).get("known", Boolean.class);
        var history = sql.fetchOne("""
                SELECT count(*) AS n, avg(t.amount_cents) AS average FROM transfers t
                LEFT JOIN fraud_decisions f ON f.transfer_id=t.id
                WHERE t.source_account_id=? AND t.operation='TRANSFER' AND t.status='COMPLETED'
                  AND COALESCE(f.evaluated_at,t.created_at) >= ?::timestamptz
                  AND COALESCE(f.evaluated_at,t.created_at) <= ?::timestamptz
                """, source, now.minusDays(30), now);
        var result = engine.assess(new FraudContext(amount, recent, known,
                history.get("n", Long.class), history.get("average", BigDecimal.class)));
        var decision = new Decision(id, "rules-v1", now, result.score(), result.decision(),
                config.reviewThreshold(), config.declineThreshold(), result.rules());
        sql.execute("""
                INSERT INTO fraud_decisions(transfer_id,engine_version,evaluated_at,score,decision,review_threshold,decline_threshold,rules)
                VALUES (?,?,?::timestamptz,?,?,?,?,?::jsonb)
                """, id, decision.engineVersion(), now, result.score(), result.decision(),
                config.reviewThreshold(), config.declineThreshold(), JSONB.valueOf(json.writeValueAsString(result.rules())));
        return decision;
    }

    @Transactional(readOnly = true)
    public Decision find(UUID transferId) {
        var row = sql.fetchOne("SELECT * FROM fraud_decisions WHERE transfer_id=?", transferId);
        if (row == null) return null; // Funding and legacy transfers deliberately have no fraud decision.
        var type = json.getTypeFactory().constructCollectionType(List.class, RuleHit.class);
        List<RuleHit> rules = json.readValue(row.get("rules", JSONB.class).data(), type);
        return new Decision(transferId, row.get("engine_version", String.class), row.get("evaluated_at", OffsetDateTime.class),
                row.get("score", Integer.class), row.get("decision", String.class), row.get("review_threshold", Integer.class),
                row.get("decline_threshold", Integer.class), rules);
    }
}
