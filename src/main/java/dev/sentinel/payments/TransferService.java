package dev.sentinel.payments;

import dev.sentinel.accounts.AccountFunds;
import dev.sentinel.audit.AuditService;
import dev.sentinel.ledger.LedgerService;
import dev.sentinel.shared.ResourceNotFoundException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {
    public record TransferSummary(Transfer transfer, Integer riskScore, String fraudDecision) {}
    public record TransferPage(java.util.List<TransferSummary> items, UUID nextBeforeId) {}
    private final DSLContext sql;
    private final AccountFunds accounts;
    private final LedgerService ledger;
    private final AuditService audit;
    private final dev.sentinel.fraud.FraudService fraud;
    public TransferService(DSLContext sql, AccountFunds accounts, LedgerService ledger, AuditService audit, dev.sentinel.fraud.FraudService fraud) {
        this.sql = sql; this.accounts = accounts; this.ledger = ledger; this.audit = audit; this.fraud = fraud;
    }

    @Transactional(timeout = 10, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public TransferResult transfer(String key, TransferCommand request) {
        return execute(key, request, "TRANSFER");
    }

    @Transactional(timeout = 10, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public TransferResult fundDemo(String key, UUID target, long amount) {
        return execute(key, new TransferCommand(AccountFunds.SYSTEM_CONTRA, target, amount), "FUNDING");
    }

    private TransferResult execute(String key, TransferCommand request, String operation) {
        TransferCommand.validateKey(key);
        sql.execute("SET LOCAL lock_timeout = '3s'");
        Record known = sql.fetchOne("SELECT * FROM transfers WHERE idempotency_key = ?", key);
        if (known != null) return replay(known, request, operation);
        int found = sql.fetchOne("SELECT count(*) AS n FROM accounts WHERE id IN (?, ?)",
                request.sourceAccountId(), request.targetAccountId()).get("n", Integer.class);
        if (found != 2) throw new ResourceNotFoundException("Account not found");
        UUID id = UUID.randomUUID();
        Record inserted = sql.fetchOne("""
                INSERT INTO transfers(id, idempotency_key, operation, source_account_id, target_account_id, amount_cents, status)
                VALUES (?, ?, ?, ?, ?, ?, 'RECEIVED') ON CONFLICT (idempotency_key) DO NOTHING RETURNING *
                """, id, key, operation, request.sourceAccountId(), request.targetAccountId(), request.amountCents());
        if (inserted == null) {
            Record existing = sql.fetchOne("SELECT * FROM transfers WHERE idempotency_key = ?", key);
            if (existing == null) throw new IllegalStateException("Persisted idempotency result missing");
            return replay(existing, request, operation);
        }
        audit.append(id, "RECEIVED");
        var pair = accounts.lockPair(request.sourceAccountId(), request.targetAccountId());
        var source = pair.stream().filter(a -> a.id().equals(request.sourceAccountId())).findFirst().orElseThrow();
        var target = pair.stream().filter(a -> a.id().equals(request.targetAccountId())).findFirst().orElseThrow();
        accounts.requireCustomer(target);
        if (operation.equals("TRANSFER")) accounts.requireCustomer(source);
        long amount = request.amountCents();
        if (operation.equals("TRANSFER")) {
            var decision = fraud.evaluate(id, source.id(), target.id(), amount);
            if (!decision.decision().equals("APPROVE")) {
                String status = decision.decision().equals("REVIEW") ? "REVIEW" : "DECLINED";
                sql.execute("UPDATE transfers SET status = ? WHERE id = ?", status, id);
                audit.append(id, status);
                return new TransferResult(get(id), false);
            }
        }
        String failure = null;
        if (operation.equals("TRANSFER") && source.balanceCents() < amount) failure = "INSUFFICIENT_FUNDS";
        else if (target.balanceCents() > Long.MAX_VALUE - amount
                || (operation.equals("FUNDING") && source.balanceCents() < Long.MIN_VALUE + amount)) failure = "BALANCE_LIMIT";
        if (failure != null) {
            sql.execute("UPDATE transfers SET status = 'REJECTED', failure_reason = ? WHERE id = ?", failure, id);
            audit.append(id, "REJECTED");
        } else {
            accounts.move(source.id(), target.id(), amount, operation.equals("FUNDING"));
            ledger.post(id, source.id(), target.id(), amount);
            sql.execute("UPDATE transfers SET status = 'COMPLETED' WHERE id = ?", id);
            audit.append(id, "COMPLETED");
        }
        return new TransferResult(get(id), false);
    }

    private static TransferResult replay(Record existing, TransferCommand request, String operation) {
        Transfer previous = map(existing);
        if (!previous.operation().equals(operation) || !previous.sourceAccountId().equals(request.sourceAccountId())
                || !previous.targetAccountId().equals(request.targetAccountId()) || previous.amountCents() != request.amountCents())
            throw new IdempotencyConflictException();
        return new TransferResult(previous, true);
    }

    @Transactional(readOnly = true)
    public TransferPage page(int limit, UUID beforeId) {
        if (beforeId != null) get(beforeId);
        var rows = sql.fetch("""
                SELECT t.*, f.score AS risk_score, f.decision AS fraud_decision
                FROM transfers t LEFT JOIN fraud_decisions f ON f.transfer_id=t.id
                WHERE (?::UUID IS NULL OR (t.created_at,t.id) <
                       (SELECT created_at,id FROM transfers WHERE id=?))
                ORDER BY t.created_at DESC,t.id DESC LIMIT ?
                """, beforeId, beforeId, limit + 1);
        var items = rows.stream().limit(limit).map(row -> new TransferSummary(map(row),
                row.get("risk_score", Integer.class), row.get("fraud_decision", String.class))).toList();
        return new TransferPage(items, rows.size() > limit ? items.getLast().transfer().id() : null);
    }

    @Transactional(readOnly = true)
    public Transfer get(UUID id) {
        Record row = sql.fetchOne("SELECT * FROM transfers WHERE id = ?", id);
        if (row == null) throw new ResourceNotFoundException("Transfer not found");
        return map(row);
    }

    private static Transfer map(Record row) {
        return new Transfer(row.get("id", UUID.class), row.get("operation", String.class),
                row.get("source_account_id", UUID.class), row.get("target_account_id", UUID.class),
                row.get("amount_cents", Long.class), row.get("currency", String.class), row.get("status", String.class),
                row.get("failure_reason", String.class), row.get("created_at", OffsetDateTime.class));
    }
}
