package dev.sentinel.accounts;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
class AccountRepository {
    // Typed fields keep this first migration readable without a code-generation database.
    private static final Table<Record> ACCOUNTS = DSL.table(DSL.name("accounts"));
    private static final Field<UUID> ID = DSL.field(DSL.name("id"), UUID.class);
    private static final Field<String> NAME = DSL.field(DSL.name("name"), String.class);
    private static final Field<String> CURRENCY = DSL.field(DSL.name("currency"), String.class);
    private static final Field<Long> BALANCE = DSL.field(DSL.name("balance_cents"), Long.class);
    private static final Field<OffsetDateTime> CREATED = DSL.field(DSL.name("created_at"), OffsetDateTime.class);
    private final DSLContext sql;

    AccountRepository(DSLContext sql) { this.sql = sql; }

    Account create(AccountName name) {
        return sql.insertInto(ACCOUNTS).set(ID, UUID.randomUUID()).set(NAME, name.value())
                .returning(ID, NAME, CURRENCY, BALANCE, CREATED)
                .fetchSingle(AccountRepository::map);
    }

    Optional<Account> find(UUID id) {
        return sql.select(ID, NAME, CURRENCY, BALANCE, CREATED).from(ACCOUNTS)
                .where(ID.eq(id)).fetchOptional(AccountRepository::map);
    }

    private static Account map(Record row) {
        return new Account(row.get(ID), row.get(NAME), row.get(CURRENCY), row.get(BALANCE), row.get(CREATED));
    }
}
