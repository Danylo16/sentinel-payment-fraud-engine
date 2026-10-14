package dev.sentinel.accounts;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import dev.sentinel.SentinelApplication;
import org.springframework.core.env.Environment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountsIT {
    // Deliberately no disabledWithoutDocker: missing Docker must fail verification, not silently skip.
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17.6-alpine");
    private ConfigurableApplicationContext application;
    private Environment environment;
    private DSLContext sql;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll void startApplication() {
        // Boot 4's SpringExtension requires JUnit 6. Plain JUnit 5 can still test a real application.
        application = SpringApplication.run(SentinelApplication.class,
                "--server.port=0", "--spring.datasource.url=" + DB.getJdbcUrl(),
                "--spring.datasource.username=" + DB.getUsername(),
                "--spring.datasource.password=" + DB.getPassword());
        environment = application.getEnvironment();
        sql = application.getBean(DSLContext.class);
    }

    @AfterAll void stopApplication() { if (application != null) application.close(); }

    @Test void createsAndRetrievesZeroBalanceAccount() throws Exception {
        var created = post(json.writeValueAsString(java.util.Map.of("name", "  Demo  ")));
        assertEquals(201, created.statusCode());
        JsonNode body = json.readTree(created.body());
        assertEquals("Demo", body.get("name").asText());
        assertEquals("EUR", body.get("currency").asText());
        assertEquals(0, body.get("balanceCents").asLong());
        assertNotNull(body.get("createdAt"));
        String location = created.headers().firstValue("Location").orElseThrow();
        assertEquals("/accounts/" + body.get("id").asText(), location);
        var retrieved = get(location);
        assertEquals(200, retrieved.statusCode());
        assertEquals(body, json.readTree(retrieved.body()));
    }
    @Test void rejectsBlankName() throws Exception {
        assertEquals(400, post(json.writeValueAsString(java.util.Map.of("name", " "))).statusCode());
    }
    @Test void rejectsMissingName() throws Exception { assertEquals(400, post("{}").statusCode()); }
    @Test void rejectsOversizedNameWithoutWriting() throws Exception {
        long before = sql.fetchCount(org.jooq.impl.DSL.table("accounts"));
        assertEquals(400, post(json.writeValueAsString(java.util.Map.of("name", "a".repeat(101)))).statusCode());
        assertEquals(before, sql.fetchCount(org.jooq.impl.DSL.table("accounts")));
    }
    @Test void reportsMalformedJson() throws Exception {
        var response = post("{");
        assertEquals(400, response.statusCode());
        assertEquals("INVALID_REQUEST", json.readTree(response.body()).get("code").asText());
    }
    @Test void reportsInvalidUuid() throws Exception { assertEquals(400, get("/accounts/invalid").statusCode()); }
    @Test void reportsUnknownRoute() throws Exception { assertEquals(404, get("/missing").statusCode()); }
    @Test void rejectsUnexpectedBalanceProperty() throws Exception {
        var response = post(json.writeValueAsString(java.util.Map.of("name", "Demo", "balanceCents", 100)));
        assertEquals(400, response.statusCode());
    }
    @Test void reportsMissingAccount() throws Exception {
        var response = get("/accounts/" + UUID.randomUUID());
        assertEquals(404, response.statusCode());
        assertEquals("NOT_FOUND", json.readTree(response.body()).get("code").asText());
    }
    @Test void databaseRejectsNegativeBalance() {
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> sql.execute(
                "insert into accounts(id,name,balance_cents) values (?, 'Demo', -1)", UUID.randomUUID()));
    }
    @Test void databaseRejectsOtherCurrencies() {
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> sql.execute(
                "insert into accounts(id,name,currency) values (?, 'Demo', 'USD')", UUID.randomUUID()));
    }
    @Test void databaseRejectsBlankName() {
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> sql.execute(
                "insert into accounts(id,name) values (?, '   ')", UUID.randomUUID()));
    }
    @Test void servesOpenApiContract() throws Exception {
        var response = get("/v3/api-docs");
        assertEquals(200, response.statusCode());
        assertTrue(json.readTree(response.body()).get("paths").has("/accounts"));
    }
    private HttpResponse<String> post(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/accounts"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path); }
}
