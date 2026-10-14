package dev.sentinel.payments;

import dev.sentinel.SentinelApplication;
import dev.sentinel.accounts.AccountService;
import java.math.BigDecimal;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

/** Separate database: testing extreme values must not exhaust the contra account of unrelated cases. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BalanceLimitsIT {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17.6-alpine");
    private ConfigurableApplicationContext app;
    @BeforeAll void start() {
        app = SpringApplication.run(SentinelApplication.class, "--server.port=0", "--spring.profiles.active=default",
                "--spring.datasource.url=" + DB.getJdbcUrl(), "--spring.datasource.username=" + DB.getUsername(),
                "--spring.datasource.password=" + DB.getPassword());
    }
    @AfterAll void stop() { if (app != null) app.close(); }
    @Test void recipientOverflowAndContraUnderflowAreRejectedBeforeWrites() {
        var accounts = app.getBean(AccountService.class); var transfers = app.getBean(TransferService.class);
        UUID full = accounts.create("Full").id(), other = accounts.create("Other").id();
        assertEquals("COMPLETED", transfers.fundDemo(key(), full, Long.MAX_VALUE).transfer().status());
        assertEquals("BALANCE_LIMIT", transfers.fundDemo(key(), full, 1).transfer().failureReason());
        assertEquals("COMPLETED", transfers.fundDemo(key(), other, 1).transfer().status());
        assertEquals("BALANCE_LIMIT", transfers.fundDemo(key(), other, 1).transfer().failureReason());
        assertEquals("BALANCE_LIMIT", transfers.transfer(key(), new TransferCommand(other, full, 1)).transfer().failureReason());
        assertEquals(Long.MAX_VALUE, accounts.get(full).balanceCents()); assertEquals(1, accounts.get(other).balanceCents());
        var sql = app.getBean(DSLContext.class);
        assertEquals(0, sql.fetchOne("SELECT sum(balance_cents) AS total FROM accounts").get("total", BigDecimal.class).signum());
        assertEquals(4, sql.fetchOne("SELECT count(*) AS n FROM ledger_entries").get("n", Integer.class));
    }
    private static String key() { return UUID.randomUUID().toString(); }
}
