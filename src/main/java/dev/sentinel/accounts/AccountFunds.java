package dev.sentinel.accounts;

import dev.sentinel.shared.RequestValidationException;
import dev.sentinel.shared.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Money-changing account operations require a transaction opened by the payment use case. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AccountFunds {
    public static final UUID SYSTEM_CONTRA = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public record LockedAccount(UUID id, long balanceCents, String type) {}
    private final DSLContext sql;
    public AccountFunds(DSLContext sql) { this.sql = sql; }

    public List<LockedAccount> lockPair(UUID source, UUID target) {
        // FK inserts take KEY SHARE locks. NO KEY UPDATE is compatible with them; FOR UPDATE is not.
        var accounts = sql.fetch("SELECT id, balance_cents, account_type FROM accounts WHERE id IN (?, ?) ORDER BY id FOR NO KEY UPDATE",
                source, target).map(row -> new LockedAccount(row.get("id", UUID.class),
                row.get("balance_cents", Long.class), row.get("account_type", String.class)));
        if (accounts.size() != 2) throw new ResourceNotFoundException("Account not found");
        return accounts;
    }

    public void requireCustomer(LockedAccount account) {
        if (!"CUSTOMER".equals(account.type()))
            throw new RequestValidationException("Ordinary transfers require customer accounts");
    }

    public void move(UUID source, UUID target, long amount, boolean funding) {
        long lowerBound = funding ? Long.MIN_VALUE + amount : amount;
        int debited = sql.execute("UPDATE accounts SET balance_cents = balance_cents - ? WHERE id = ? AND balance_cents >= ?",
                amount, source, lowerBound);
        int credited = sql.execute("UPDATE accounts SET balance_cents = balance_cents + ? WHERE id = ? AND balance_cents <= ?",
                amount, target, Long.MAX_VALUE - amount);
        if (debited != 1 || credited != 1) throw new IllegalStateException("Guarded balance update failed");
    }
}
