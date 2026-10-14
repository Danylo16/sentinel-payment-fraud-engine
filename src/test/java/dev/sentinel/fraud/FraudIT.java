package dev.sentinel.fraud;

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
class FraudIT {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17.6-alpine");
    private static final String TOKEN = "integration-test-token-only";
    private ConfigurableApplicationContext app;
    private DSLContext sql;
    private int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll void start() {
        app = SpringApplication.run(SentinelApplication.class, "--server.port=0", "--spring.profiles.active=demo",
                "--sentinel.demo.funding-token=" + TOKEN, "--spring.datasource.url=" + DB.getJdbcUrl(),
                "--spring.datasource.username=" + DB.getUsername(), "--spring.datasource.password=" + DB.getPassword());
        sql = app.getBean(DSLContext.class);
        port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
    }
    @AfterAll void stop() { if (app != null) app.close(); http.close(); }
    @AfterEach void invariants() {
        assertEquals(0, sql.fetchOne("SELECT COALESCE(sum(balance_cents),0) AS n FROM accounts").get("n", BigDecimal.class).signum());
        assertEquals(0, sql.fetchOne("SELECT count(*) AS n FROM ledger_entries l JOIN transfers t ON t.id=l.transfer_id WHERE t.status<>'COMPLETED'").get("n", Integer.class));
        assertEquals(0, sql.fetchOne("SELECT count(*) AS n FROM transfers t LEFT JOIN fraud_decisions f ON f.transfer_id=t.id WHERE t.fraud_required AND f.transfer_id IS NULL").get("n", Integer.class));
    }
    @Test void approvedDecisionIsStoredAndReadableOverHttp() throws Exception {
        UUID a=account(), b=account(); fund(a,1000);
        var response=transfer(key(),a,b,100); assertEquals(201,response.statusCode());
        var decision=body(get("/transfers/"+body(response).get("id").asText()+"/fraud"));
        assertEquals("APPROVE",decision.get("decision").asText()); assertEquals(20,decision.get("score").asInt());
        assertEquals("NEW_RECIPIENT",decision.get("rules").get(0).get("ruleId").asText());
        assertEquals(900,balance(a)); assertEquals(100,balance(b));
    }
    @Test void reviewDoesNotMoveMoneyAndReplayDoesNotReevaluate() throws Exception {
        UUID a=account(), b=account(); fund(a,200000); String k=key();
        var first=transfer(k,a,b,100000); assertEquals(202,first.statusCode());
        UUID id=UUID.fromString(body(first).get("id").asText());
        var before=body(get("/transfers/"+id+"/fraud"));
        assertEquals("REVIEW",body(first).get("status").asText()); assertEquals(55,before.get("score").asInt());
        var repeat=transfer(k,a,b,100000); assertEquals(200,repeat.statusCode()); assertEquals(body(first),body(repeat));
        assertEquals(before,body(get("/transfers/"+id+"/fraud")));
        assertEquals(0,entries(id)); assertEquals(200000,balance(a)); assertEquals(0,balance(b));
        assertEquals("REVIEW",body(get("/transfers/"+id+"/audit")).get(1).get("eventType").asText());
        assertIntegrity(()->sql.execute("UPDATE transfers SET status='COMPLETED' WHERE id=?",id));
        assertIntegrity(()->sql.execute("INSERT INTO ledger_entries(transfer_id,account_id,signed_cents) VALUES (?,?,100000)",id,b));
        assertEquals(409,transfer(k,a,b,100001).statusCode());
    }
    @Test void highScoreDeclinesWithoutMovingMoney() throws Exception {
        UUID a=account(), old=account(), fresh=account(); fund(a,200000);
        for(int i=0;i<5;i++) assertEquals(201,transfer(key(),a,old,100).statusCode());
        String k=key(); var result=transfer(k,a,fresh,100000); assertEquals(201,result.statusCode());
        UUID id=UUID.fromString(body(result).get("id").asText());
        assertEquals("DECLINED",body(result).get("status").asText());
        var decision=body(get("/transfers/"+id+"/fraud"));
        assertEquals(100,decision.get("score").asInt()); assertEquals(4,decision.get("rules").size());
        assertEquals(0,entries(id)); assertEquals(199500,balance(a)); assertEquals(0,balance(fresh));
        assertEquals(200,transfer(k,a,fresh,100000).statusCode());
        assertIntegrity(()->sql.execute("UPDATE transfers SET status='COMPLETED' WHERE id=?",id));
    }
    @Test void concurrentAttemptsCannotBypassVelocityRule() throws Exception {
        UUID a=account(); fund(a,10000); List<UUID> targets=new ArrayList<>();
        for(int i=0;i<6;i++) targets.add(account());
        var results=parallel(6,i->()->transfer(key(),a,targets.get(i),100));
        assertEquals(5,results.stream().filter(r->r.statusCode()==201).count());
        assertEquals(1,results.stream().filter(r->r.statusCode()==202).count());
        var review=results.stream().filter(r->r.statusCode()==202).findFirst().orElseThrow();
        var decision=body(get("/transfers/"+body(review).get("id").asText()+"/fraud"));
        assertEquals(50,decision.get("score").asInt());
        assertEquals(9500,balance(a));
    }
    @Test void concurrentIdenticalReviewCreatesOneDecision() throws Exception {
        UUID a=account(), b=account(); fund(a,200000); String k=key();
        var results=parallel(8,i->()->transfer(k,a,b,100000));
        assertEquals(1,results.stream().filter(r->r.statusCode()==202).count());
        assertEquals(7,results.stream().filter(r->r.statusCode()==200).count());
        UUID id=UUID.fromString(body(results.get(0)).get("id").asText());
        assertEquals(1,sql.fetchOne("SELECT count(*) AS n FROM fraud_decisions WHERE transfer_id=?",id).get("n",Integer.class));
        assertEquals(200000,balance(a)); assertEquals(0,entries(id));
    }
    @Test void knownRecipientDoesNotTriggerNewRecipientRule() throws Exception {
        UUID a=account(), b=account(); fund(a,1000);
        transfer(key(),a,b,100); var second=transfer(key(),a,b,100);
        var decision=body(get("/transfers/"+body(second).get("id").asText()+"/fraud"));
        assertEquals(0,decision.get("score").asInt()); assertEquals(0,decision.get("rules").size());
    }
    @Test void decisionWriteFailureRollsBackAndSameKeyCanRetry() throws Exception {
        UUID a=account(), b=account(); fund(a,1000); String k=key();
        sql.execute("CREATE TRIGGER test_fraud_failure BEFORE INSERT ON fraud_decisions FOR EACH ROW EXECUTE FUNCTION forbid_accounting_mutation()");
        try {
            assertEquals(500,transfer(k,a,b,100).statusCode());
            assertEquals(0,sql.fetchOne("SELECT count(*) AS n FROM transfers WHERE idempotency_key=?",k).get("n",Integer.class));
            assertEquals(1000,balance(a)); assertEquals(0,balance(b));
        } finally { sql.execute("DROP TRIGGER test_fraud_failure ON fraud_decisions"); }
        assertEquals(201,transfer(k,a,b,100).statusCode());
    }
    @Test void decisionsAreImmutableIncludingTruncate() throws Exception {
        UUID a=account(), b=account(); fund(a,1000);
        UUID id=UUID.fromString(body(transfer(key(),a,b,100)).get("id").asText());
        assertIntegrity(()->sql.execute("UPDATE fraud_decisions SET engine_version='tampered' WHERE transfer_id=?",id));
        assertIntegrity(()->sql.execute("DELETE FROM fraud_decisions WHERE transfer_id=?",id));
        assertIntegrity(()->sql.execute("TRUNCATE fraud_decisions"));
    }
    @Test void databaseRequiresDecisionEvenWhenJavaIsBypassed() throws Exception {
        UUID a=account(),b=account();
        assertIntegrity(()->{ sql.transaction(c->{var tx=org.jooq.impl.DSL.using(c); UUID id=UUID.randomUUID();
            tx.execute("INSERT INTO transfers(id,idempotency_key,operation,source_account_id,target_account_id,amount_cents,status,fraud_required) VALUES (?,?,'TRANSFER',?,?,100,'RECEIVED',false)",id,key(),a,b);
            tx.execute("UPDATE transfers SET status='REVIEW' WHERE id=?",id);
            tx.execute("INSERT INTO audit_events(transfer_id,event_type) VALUES (?,'RECEIVED'),(?,'REVIEW')",id,id);
        }); return null;});
    }
    @Test void fundingBypassesFraudAndHasNoDecision() throws Exception {
        UUID a=account(); var response=fund(a,10000000);
        assertEquals(404,get("/transfers/"+body(response).get("id").asText()+"/fraud").statusCode());
        assertEquals(10000000,balance(a));
    }
    @Test void rejectedAttemptsAlsoCountTowardVelocity() throws Exception {
        UUID a=account(),b=account();
        for(int i=0;i<5;i++) assertEquals(409,transfer(key(),a,b,100).statusCode());
        var sixth=transfer(key(),a,b,100);
        assertEquals(202,sixth.statusCode());
        assertEquals(50,body(get("/transfers/"+body(sixth).get("id").asText()+"/fraud")).get("score").asInt());
        assertEquals(0,balance(a)); assertEquals(0,balance(b));
    }
    @Test void expiredAttemptsDoNotTriggerVelocity() throws Exception {
        UUID a=account(),b=account();
        // Insert-time fixture ages observations; production history remains immutable.
        sql.execute("CREATE FUNCTION test_age_fraud() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN NEW.evaluated_at=clock_timestamp()-interval '120 seconds'; RETURN NEW; END $$");
        sql.execute("CREATE TRIGGER test_age BEFORE INSERT ON fraud_decisions FOR EACH ROW EXECUTE FUNCTION test_age_fraud()");
        try { for(int i=0;i<5;i++) assertEquals(409,transfer(key(),a,b,100).statusCode()); }
        finally { sql.execute("DROP TRIGGER test_age ON fraud_decisions");sql.execute("DROP FUNCTION test_age_fraud()"); }
        var sixth=transfer(key(),a,b,100); assertEquals(409,sixth.statusCode());
        UUID id=UUID.fromString(body(sixth).get("fields").get("transferId").asText());
        assertEquals(20,body(get("/transfers/"+id+"/fraud")).get("score").asInt());
    }
    @Test void databaseRejectsStatusThatContradictsDecision() throws Exception {
        UUID a=account(),b=account();
        assertIntegrity(()->{sql.transaction(c->{var tx=org.jooq.impl.DSL.using(c); UUID id=UUID.randomUUID();
            tx.execute("INSERT INTO transfers(id,idempotency_key,operation,source_account_id,target_account_id,amount_cents,status) VALUES (?,?,'TRANSFER',?,?,100,'RECEIVED')",id,key(),a,b);
            tx.execute("UPDATE transfers SET status='REVIEW' WHERE id=?",id);
            tx.execute("INSERT INTO audit_events(transfer_id,event_type) VALUES (?,'RECEIVED'),(?,'REVIEW')",id,id);
            tx.execute("INSERT INTO fraud_decisions VALUES (?,'test',clock_timestamp(),0,'APPROVE',40,70,'[]'::jsonb)",id);
        }); return null;});
    }
    @Test void databaseRejectsForgedScore() throws Exception {
        UUID a=account(),b=account(); fund(a,1000);
        UUID id=UUID.fromString(body(transfer(key(),a,b,100)).get("id").asText());
        assertIntegrity(()->sql.execute("INSERT INTO fraud_decisions VALUES (?,'test',clock_timestamp(),20,'APPROVE',40,70,'[]'::jsonb)",id));
    }

    @Test void observationTimeIsCapturedAfterWaitingForAccountLock() throws Exception {
        UUID a=account(),b=account(); fund(a,1000);
        try(var connection=java.sql.DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
            var pool=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT id FROM accounts WHERE id=? FOR NO KEY UPDATE")) {
                statement.setObject(1,a); statement.executeQuery().close();
            }
            var pending=pool.submit(()->transfer(key(),a,b,100));
            boolean waiting=false; long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            try {
                while(System.nanoTime()<deadline) {
                    waiting=sql.fetchOne("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%ORDER BY id FOR NO KEY UPDATE%') AS waiting").get("waiting",Boolean.class);
                    if(waiting) break;
                    Thread.sleep(10);
                }
                assertTrue(waiting,"Payment must actually be waiting for the held row lock");
                var releasedAt=sql.fetchOne("SELECT clock_timestamp() AS now").get("now",java.time.OffsetDateTime.class);
                connection.commit();
                var response=pending.get(10,TimeUnit.SECONDS); assertEquals(201,response.statusCode());
                var decision=body(get("/transfers/"+body(response).get("id").asText()+"/fraud"));
                var evaluatedAt=java.time.OffsetDateTime.parse(decision.get("evaluatedAt").asText());
                assertFalse(evaluatedAt.isBefore(releasedAt),"Fraud observation must use the time AFTER lock acquisition");
            } finally { connection.rollback(); }
        }
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
