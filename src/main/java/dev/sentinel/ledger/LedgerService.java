package dev.sentinel.ledger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {
    public record HistoryEntry(long id, UUID transferId, long signedCents, String currency,
                               String operation, OffsetDateTime createdAt) {}
    public record HistoryPage(List<HistoryEntry> items, Long nextBeforeId) {}
    private final DSLContext sql;
    public LedgerService(DSLContext sql) { this.sql = sql; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void post(UUID transferId, UUID source, UUID target, long amount) {
        sql.execute("INSERT INTO ledger_entries(transfer_id, account_id, signed_cents) VALUES (?, ?, ?)", transferId, source, -amount);
        sql.execute("INSERT INTO ledger_entries(transfer_id, account_id, signed_cents) VALUES (?, ?, ?)", transferId, target, amount);
    }

    @Transactional(readOnly = true)
    public HistoryPage history(UUID accountId, int limit, Long beforeId) {
        var rows = sql.fetch("""
                SELECT l.id, l.transfer_id, l.signed_cents, l.currency, t.operation, l.created_at
                FROM ledger_entries l JOIN transfers t ON t.id = l.transfer_id
                WHERE l.account_id = ? AND (?::BIGINT IS NULL OR l.id < ?)
                ORDER BY l.id DESC LIMIT ?
                """, accountId, beforeId, beforeId, limit + 1);
        var entries = rows.stream().limit(limit).map(row -> new HistoryEntry(row.get("id", Long.class),
                row.get("transfer_id", UUID.class), row.get("signed_cents", Long.class),
                row.get("currency", String.class), row.get("operation", String.class),
                row.get("created_at", OffsetDateTime.class))).toList();
        return new HistoryPage(entries, rows.size() > limit ? entries.getLast().id() : null);
    }
}
