package dev.sentinel.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    public record AuditEvent(long id, String eventType, OffsetDateTime createdAt) {}
    private final DSLContext sql;
    public AuditService(DSLContext sql) { this.sql = sql; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID transferId, String eventType) {
        sql.execute("INSERT INTO audit_events(transfer_id, event_type) VALUES (?, ?)", transferId, eventType);
    }
    @Transactional(readOnly = true)
    public List<AuditEvent> history(UUID transferId) {
        return sql.fetch("SELECT id, event_type, created_at FROM audit_events WHERE transfer_id = ? ORDER BY id", transferId)
                .map(row -> new AuditEvent(row.get("id", Long.class), row.get("event_type", String.class),
                        row.get("created_at", OffsetDateTime.class)));
    }
}
