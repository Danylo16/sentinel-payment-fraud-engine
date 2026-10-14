package dev.sentinel.fraud;

import dev.sentinel.SentinelApplication;
import dev.sentinel.accounts.AccountService;
import dev.sentinel.payments.TransferCommand;
import dev.sentinel.payments.TransferService;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class FraudMigrationIT {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Test void upgradePreservesPhaseTwoBalancesAndHistory() throws Exception {
        Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).target("2").load().migrate();
        UUID account=UUID.randomUUID(), legacy=UUID.randomUUID();
        UUID contra=UUID.fromString("00000000-0000-0000-0000-000000000001");
        try(var connection=java.sql.DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())) {
            var sql=DSL.using(connection,org.jooq.SQLDialect.POSTGRES);
            sql.execute("INSERT INTO accounts(id,name) VALUES (?,'Legacy')",account);
            sql.transaction(c->{var tx=DSL.using(c);
                tx.execute("INSERT INTO transfers(id,idempotency_key,operation,source_account_id,target_account_id,amount_cents,status) VALUES (?,?,'FUNDING',?,?,1000,'RECEIVED')",legacy,legacy.toString(),contra,account);
                tx.execute("UPDATE accounts SET balance_cents=-1000 WHERE id=?",contra);
                tx.execute("UPDATE accounts SET balance_cents=1000 WHERE id=?",account);
                tx.execute("INSERT INTO ledger_entries(transfer_id,account_id,signed_cents) VALUES (?,?,-1000),(?,?,1000)",legacy,contra,legacy,account);
                tx.execute("UPDATE transfers SET status='COMPLETED' WHERE id=?",legacy);
                tx.execute("INSERT INTO audit_events(transfer_id,event_type) VALUES (?,'RECEIVED'),(?,'COMPLETED')",legacy,legacy);
            });
        }
        try(var app=SpringApplication.run(SentinelApplication.class,"--server.port=0","--spring.profiles.active=default",
                "--spring.datasource.url="+DB.getJdbcUrl(),"--spring.datasource.username="+DB.getUsername(),"--spring.datasource.password="+DB.getPassword())) {
            var accounts=app.getBean(AccountService.class); var transfers=app.getBean(TransferService.class);
            assertEquals(1000,accounts.get(account).balanceCents());
            assertEquals("COMPLETED",transfers.get(legacy).status());
            assertNull(app.getBean(FraudService.class).find(legacy));
            UUID target=accounts.create("New target").id();
            var result=transfers.transfer(UUID.randomUUID().toString(),new TransferCommand(account,target,100));
            assertEquals("COMPLETED",result.transfer().status());
            assertEquals("APPROVE",app.getBean(FraudService.class).find(result.transfer().id()).decision());
            assertEquals(900,accounts.get(account).balanceCents());
            assertEquals(4,app.getBean(DSLContext.class).fetchOne("SELECT count(*) AS n FROM ledger_entries").get("n",Integer.class));
        }
    }
}
