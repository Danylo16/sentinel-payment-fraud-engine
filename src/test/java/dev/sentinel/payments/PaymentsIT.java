package dev.sentinel.payments;

import dev.sentinel.SentinelApplication;
import dev.sentinel.accounts.AccountFunds;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentsIT {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17.6-alpine");
    private static final String TOKEN = "integration-test-token-only";
    private ConfigurableApplicationContext app;
    private DSLContext sql;
    private int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll void start() {
        app = SpringApplication.run(SentinelApplication.class, "--server.port=0", "--spring.profiles.active=demo",
                "--sentinel.fraud.velocity-limit=100", // Keep accounting stress tests separate from velocity policy.
                "--sentinel.demo.funding-token=" + TOKEN, "--spring.datasource.url=" + DB.getJdbcUrl(),
                "--spring.datasource.username=" + DB.getUsername(), "--spring.datasource.password=" + DB.getPassword());
        sql = app.getBean(DSLContext.class);
        port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
    }
    @AfterAll void stop() { if (app != null) app.close(); http.close(); }
    @AfterEach void invariants() {
        assertEquals(0, sql.fetchOne("SELECT COALESCE(sum(balance_cents),0) AS total FROM accounts").get("total", BigDecimal.class).signum());
        assertEquals(0, sql.fetchOne("""
                SELECT count(*) AS n FROM accounts a WHERE a.balance_cents::NUMERIC <>
                (SELECT COALESCE(sum(l.signed_cents),0) FROM ledger_entries l WHERE l.account_id=a.id)
                """).get("n", Integer.class));
        assertEquals(0, sql.fetchOne("""
                SELECT count(*) AS n FROM transfers t WHERE
                (t.status='COMPLETED' AND (SELECT count(*) FROM ledger_entries WHERE transfer_id=t.id)<>2)
                OR (t.status='REJECTED' AND EXISTS (SELECT 1 FROM ledger_entries WHERE transfer_id=t.id))
                OR t.status='RECEIVED'
                """).get("n", Integer.class));
        assertEquals(0, sql.fetchOne("""
                SELECT count(*) AS n FROM (SELECT transfer_id FROM ledger_entries GROUP BY transfer_id
                HAVING sum(signed_cents)<>0) broken
                """).get("n", Integer.class));
    }

    @Test void successfulTransferIsBalancedAndAudited() throws Exception {
        UUID a = account(), b = account(); fund(a, 1000);
        var response = transfer(key(), a, b, 250);
        assertEquals(201, response.statusCode());
        JsonNode t = body(response);
        assertEquals("COMPLETED", t.get("status").asText());
        assertEquals(750, balance(a)); assertEquals(250, balance(b));
        UUID id = UUID.fromString(t.get("id").asText());
        assertEquals(2, entries(id));
        assertEquals(2, body(get("/transfers/" + id + "/audit")).size());
        assertEquals(t, body(get("/transfers/" + id)));
    }
    @Test void insufficientFundsPersistsRejectionWithoutMoneyChanges() throws Exception {
        UUID a = account(), b = account();
        var response = transfer(key(), a, b, 100);
        assertEquals(409, response.statusCode());
        assertEquals("INSUFFICIENT_FUNDS", body(response).get("code").asText());
        UUID id = UUID.fromString(body(response).get("fields").get("transferId").asText());
        assertEquals("REJECTED", body(get("/transfers/" + id)).get("status").asText());
        assertEquals(0, entries(id)); assertEquals(0, balance(a)); assertEquals(0, balance(b));
    }
    @Test void completedReplayDoesNotSpendAgain() throws Exception {
        UUID a = account(), b = account(); fund(a, 1000); String k = key();
        var first = transfer(k, a, b, 100); var repeat = transfer(k, a, b, 100);
        assertEquals(201, first.statusCode()); assertEquals(200, repeat.statusCode());
        assertEquals(body(first), body(repeat));
        assertEquals("true", repeat.headers().firstValue("Idempotency-Replayed").orElseThrow());
        assertEquals(900, balance(a)); assertEquals(100, balance(b));
    }
    @Test void rejectedReplayRemainsRejectedAfterFunding() throws Exception {
        UUID a = account(), b = account(); String k = key();
        var first = transfer(k, a, b, 100); fund(a, 1000);
        var repeat = transfer(k, a, b, 100);
        assertEquals(409, repeat.statusCode()); assertEquals(body(first), body(repeat));
        assertEquals(1000, balance(a)); assertEquals(0, balance(b));
    }
    @Test void keyReuseWithChangedParametersConflicts() throws Exception {
        UUID a = account(), b = account(), c = account(); fund(a, 1000); String k = key();
        assertEquals(201, transfer(k, a, b, 100).statusCode());
        var response = transfer(k, a, c, 100);
        assertEquals(409, response.statusCode()); assertEquals("IDEMPOTENCY_CONFLICT", body(response).get("code").asText());
        assertEquals(409, transfer(k, a, b, 101).statusCode());
        assertEquals(900, balance(a)); assertEquals(0, balance(c));
    }
    @Test void simultaneousIdenticalRequestsCommitExactlyOnce() throws Exception {
        UUID a = account(), b = account(); fund(a, 1000); String k = key();
        var responses = parallel(12, i -> () -> transfer(k, a, b, 100));
        assertEquals(1, responses.stream().filter(x -> x.statusCode() == 201).count());
        assertEquals(11, responses.stream().filter(x -> x.statusCode() == 200).count());
        Set<String> ids = new HashSet<>(); for (var response : responses) ids.add(body(response).get("id").asText());
        assertEquals(1, ids.size()); assertEquals(900, balance(a)); assertEquals(100, balance(b));
    }
    @Test void parallelDebitsCannotOverspend() throws Exception {
        UUID a = account(); fund(a, 1000); List<UUID> targets = new ArrayList<>();
        for (int i = 0; i < 20; i++) targets.add(account());
        var responses = parallel(20, i -> () -> transfer(key(), a, targets.get(i), 200));
        assertEquals(5, responses.stream().filter(x -> x.statusCode() == 201).count());
        assertEquals(15, responses.stream().filter(x -> x.statusCode() == 409).count());
        assertEquals(0, balance(a));
        long sum = 0; for (UUID b : targets) sum += balance(b); assertEquals(1000, sum);
    }
    @Test void oppositeDirectionTransfersUseConsistentLocks() throws Exception {
        UUID a = account(), b = account(); fund(a, 1000); fund(b, 1000);
        var responses = parallel(20, i -> () -> i % 2 == 0 ? transfer(key(), a, b, 100) : transfer(key(), b, a, 100));
        assertTrue(responses.stream().allMatch(x -> x.statusCode() == 201), responses.toString());
        assertEquals(1000, balance(a)); assertEquals(1000, balance(b));
    }
    @Test void failureDuringSecondLedgerWriteRollsBackEverythingAndCanRetry() throws Exception {
        UUID a = account(), b = account(); fund(a, 1000); String k = key();
        sql.execute("""
                CREATE FUNCTION test_fail_credit() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN IF NEW.signed_cents > 0 THEN RAISE EXCEPTION 'Injected second entry failure'; END IF; RETURN NEW; END $$
                """);
        sql.execute("CREATE TRIGGER test_credit_failure BEFORE INSERT ON ledger_entries FOR EACH ROW EXECUTE FUNCTION test_fail_credit()");
        try {
            var response = transfer(k, a, b, 100);
            assertEquals(500, response.statusCode());
            assertEquals("INTERNAL_ERROR", body(response).get("code").asText());
            assertEquals(1000, balance(a)); assertEquals(0, balance(b));
            assertEquals(0, sql.fetchOne("SELECT count(*) AS n FROM transfers WHERE idempotency_key=?", k).get("n", Integer.class));
            assertEquals(0, sql.fetchOne("SELECT count(*) AS n FROM ledger_entries WHERE account_id=?", b).get("n", Integer.class));
        } finally {
            sql.execute("DROP TRIGGER test_credit_failure ON ledger_entries"); sql.execute("DROP FUNCTION test_fail_credit()");
        }
        assertEquals(201, transfer(k, a, b, 100).statusCode());
        assertEquals(900, balance(a)); assertEquals(100, balance(b));
    }
    @Test void databasePreventsLedgerUpdateDeleteAndTruncate() throws Exception {
        UUID a = account(); fund(a, 100);
        assertIntegrity(() -> sql.execute("UPDATE ledger_entries SET signed_cents=signed_cents+1 WHERE account_id=?", a));
        assertIntegrity(() -> sql.execute("DELETE FROM ledger_entries WHERE account_id=?", a));
        assertIntegrity(() -> sql.execute("TRUNCATE ledger_entries"));
    }
    @Test void databasePreventsAuditMutationAndTerminalStatusChange() throws Exception {
        UUID a = account(); var response = fund(a, 100); UUID id = UUID.fromString(body(response).get("id").asText());
        assertIntegrity(() -> sql.execute("DELETE FROM audit_events WHERE transfer_id=?", id));
        assertIntegrity(() -> sql.execute("UPDATE transfers SET status='REJECTED', failure_reason='INSUFFICIENT_FUNDS' WHERE id=?", id));
    }
    @Test void databaseRejectsUnexplainedBalanceWrite() throws Exception {
        UUID a = account();
        assertIntegrity(() -> sql.execute("UPDATE accounts SET balance_cents=100 WHERE id=?", a));
        assertEquals(0, balance(a));
    }
    @Test void databaseRejectsUnbalancedTransferEvenWhenJavaIsBypassed() throws Exception {
        UUID a = account(), b = account(); fund(a, 1000);
        assertIntegrity(() -> {
            sql.transaction(config -> {
                DSLContext tx = org.jooq.impl.DSL.using(config); UUID id = UUID.randomUUID();
                tx.execute("INSERT INTO transfers(id,idempotency_key,operation,source_account_id,target_account_id,amount_cents,status) VALUES (?,?,'TRANSFER',?,?,100,'COMPLETED')", id, key(), a, b);
                tx.execute("INSERT INTO audit_events(transfer_id,event_type) VALUES (?,'RECEIVED'),(?,'COMPLETED')", id, id);
                tx.execute("UPDATE accounts SET balance_cents=balance_cents-100 WHERE id=?", a);
                tx.execute("UPDATE accounts SET balance_cents=balance_cents+99 WHERE id=?", b);
                tx.execute("INSERT INTO ledger_entries(transfer_id,account_id,signed_cents) VALUES (?,?,-100),(?,?,99)", id, a, id, b);
            });
            return null;
        });
        assertEquals(1000, balance(a)); assertEquals(0, balance(b));
    }
    @Test void databaseRejectsCompletedTransferWithoutEntries() throws Exception {
        UUID a = account(), b = account();
        assertIntegrity(() -> sql.execute("INSERT INTO transfers(id,idempotency_key,operation,source_account_id,target_account_id,amount_cents,status) VALUES (?,?,'TRANSFER',?,?,100,'COMPLETED')", UUID.randomUUID(), key(), a, b));
    }
    @Test void invalidRequestsDoNotCreatePayments() throws Exception {
        UUID a = account(), b = account();
        assertEquals(400, transfer(key(), a, b, 0).statusCode());
        assertEquals(400, transfer(key(), a, b, -1).statusCode());
        assertEquals(400, transfer(key(), a, a, 100).statusCode());
        assertEquals(400, send("POST", "/transfers", Map.of("sourceAccountId", a, "targetAccountId", b, "amountCents", 0.5), Map.of("Idempotency-Key", key())).statusCode());
        assertEquals(400, transfer("a".repeat(129), a, b, 100).statusCode());
        assertEquals(400, send("POST", "/transfers", request(a, b, 100), Map.of()).statusCode());
        assertEquals(404, transfer(key(), UUID.randomUUID(), b, 100).statusCode());
        assertEquals(400, transfer(key(), AccountFunds.SYSTEM_CONTRA, b, 100).statusCode());
    }
    @Test void demoFundingRequiresTokenAndIsIdempotent() throws Exception {
        UUID a = account(); String k = key();
        var rejected = send("POST", "/demo/fundings", Map.of("targetAccountId", a, "amountCents", 100),
                Map.of("Idempotency-Key", k, "X-Demo-Token", "wrong-token"));
        assertEquals(403, rejected.statusCode()); assertEquals(0, balance(a));
        var first = funding(k, a, 100); var repeat = funding(k, a, 100);
        assertEquals(201, first.statusCode()); assertEquals(200, repeat.statusCode());
        assertEquals(body(first), body(repeat)); assertEquals(100, balance(a));
        assertEquals(409, transfer(k, a, account(), 100).statusCode());
    }
    @Test void historyIsPaginatedAndContainsSignedEntries() throws Exception {
        UUID a = account(), b = account(); fund(a, 1000);
        transfer(key(), a, b, 100); transfer(key(), a, b, 200);
        JsonNode page = body(get("/accounts/" + a + "/transactions?limit=2"));
        assertEquals(2, page.get("items").size());
        assertEquals(-200, page.get("items").get(0).get("signedCents").asLong());
        assertFalse(page.get("nextBeforeId").isNull());
        JsonNode second = body(get("/accounts/" + a + "/transactions?limit=2&beforeId=" + page.get("nextBeforeId").asLong()));
        assertEquals(1, second.get("items").size()); assertEquals(1000, second.get("items").get(0).get("signedCents").asLong());
        assertEquals(400, get("/accounts/" + a + "/transactions?limit=101").statusCode());
    }
    @Test void lockTimeoutRollsBackAndReturnsRetryableResponse() throws Exception {
        UUID a = account(), b = account(); fund(a, 1000); String k = key();
        try (var connection = java.sql.DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM accounts WHERE id=? FOR NO KEY UPDATE")) {
                statement.setObject(1, a); statement.executeQuery().close();
            }
            var response = transfer(k, a, b, 100);
            assertEquals(503, response.statusCode());
            assertEquals("RETRYABLE_TRANSACTION_FAILURE", body(response).get("code").asText());
            assertEquals(0, sql.fetchOne("SELECT count(*) AS n FROM transfers WHERE idempotency_key=?", k).get("n", Integer.class));
            connection.rollback();
        }
        assertEquals(201, transfer(k, a, b, 100).statusCode());
    }

    @Test void monitoringPagesExcludeContraAndReturnRealScoresWithoutDuplicates() throws Exception {
        UUID a=account(),b=account(); fund(a,1000);
        UUID id=UUID.fromString(body(transfer(key(),a,b,100)).get("id").asText());
        var page=body(get("/transfers?limit=1"));
        assertEquals(1,page.get("items").size());
        assertEquals(id.toString(),page.get("items").get(0).get("transfer").get("id").asText());
        assertEquals(20,page.get("items").get(0).get("riskScore").asInt());
        assertEquals("APPROVE",page.get("items").get(0).get("fraudDecision").asText());
        assertFalse(page.get("nextBeforeId").isNull());
        var older=body(get("/transfers?limit=1&beforeId="+page.get("nextBeforeId").asText()));
        assertNotEquals(id.toString(),older.get("items").get(0).get("transfer").get("id").asText());
        var accountPage=body(get("/accounts?limit=100")); Set<String> ids=new HashSet<>();
        for(var item:accountPage.get("items")) ids.add(item.get("id").asText());
        assertTrue(ids.contains(a.toString())); assertTrue(ids.contains(b.toString()));
        assertFalse(ids.contains(AccountFunds.SYSTEM_CONTRA.toString()));
        assertEquals(400,get("/accounts?limit=0").statusCode());
        assertEquals(400,get("/transfers?limit=101").statusCode());
        assertEquals(404,get("/transfers?beforeId="+UUID.randomUUID()).statusCode());
    }

    private UUID account() throws Exception {
        var response = send("POST", "/accounts", Map.of("name", "Demo " + key()), Map.of());
        assertEquals(201, response.statusCode(), response.body()); return UUID.fromString(body(response).get("id").asText());
    }
    private HttpResponse<String> fund(UUID a, long amount) throws Exception {
        var response = funding(key(), a, amount); assertEquals(201, response.statusCode(), response.body()); return response;
    }
    private HttpResponse<String> funding(String k, UUID a, long amount) throws Exception {
        return send("POST", "/demo/fundings", Map.of("targetAccountId", a, "amountCents", amount), Map.of("Idempotency-Key", k, "X-Demo-Token", TOKEN));
    }
    private HttpResponse<String> transfer(String k, UUID a, UUID b, long amount) throws Exception {
        return send("POST", "/transfers", request(a, b, amount), Map.of("Idempotency-Key", k));
    }
    private static Map<String, Object> request(UUID a, UUID b, long amount) {
        return Map.of("sourceAccountId", a, "targetAccountId", b, "amountCents", amount);
    }
    private long balance(UUID a) throws Exception { return body(get("/accounts/" + a)).get("balanceCents").asLong(); }
    private int entries(UUID id) { return sql.fetchOne("SELECT count(*) AS n FROM ledger_entries WHERE transfer_id=?", id).get("n", Integer.class); }
    private JsonNode body(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    private HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, Map.of()); }
    private HttpResponse<String> send(String method, String path, Object data, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(15));
        headers.forEach(builder::header);
        if (data != null) builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data)));
        else builder.method(method, HttpRequest.BodyPublishers.noBody());
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String key() { return UUID.randomUUID().toString(); }
    private static void assertIntegrity(java.util.function.Supplier<?> operation) {
        RuntimeException error = assertThrows(RuntimeException.class, operation::get);
        SQLException cause = null;
        for (Throwable t = error; t != null; t = t.getCause()) if (t instanceof SQLException e) cause = e;
        assertNotNull(cause, "Expected a PostgreSQL constraint failure"); assertEquals("23514", cause.getSQLState());
    }
    private static List<HttpResponse<String>> parallel(int n, IntFunction<Callable<HttpResponse<String>>> task) throws Exception {
        try (var pool = Executors.newFixedThreadPool(n)) {
            var ready = new CountDownLatch(n); var start = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int index = i;
                futures.add(pool.submit(() -> { ready.countDown(); if (!start.await(5, TimeUnit.SECONDS)) throw new TimeoutException(); return task.apply(index).call(); }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            List<HttpResponse<String>> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        }
    }
}
