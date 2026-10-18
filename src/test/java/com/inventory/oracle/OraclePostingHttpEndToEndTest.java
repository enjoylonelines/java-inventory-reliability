package com.inventory.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.application.OraclePostingService;
import com.inventory.reconciliation.OracleEventReconciliation;
import com.inventory.reconciliation.SnapshotReconciliation;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in end-to-end test for an already prepared dedicated test schema. It leaves
 * UUID-scoped rows in place, and must not be run against operational data. A
 * test-only outbox selection query restricts the worker to its own event: this
 * does not test production's global next-ready selection or worker scheduling.
 */
@EnabledIfEnvironmentVariable(named = "ORACLE_E2E_TEST", matches = "true")
public class OraclePostingHttpEndToEndTest {
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static OracleInventoryMapper inventory;
    private static ScopedOutboxMapper scopedOutbox;
    private static OraclePostingService posting;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void connectToDedicatedSchema() throws Exception {
        if (!"true".equals(System.getenv("ORACLE_E2E_DEDICATED_SCHEMA"))) {
            throw new IllegalStateException("A dedicated test schema must be explicitly confirmed");
        }
        String url = required("ORACLE_TEST_URL");
        String user = required("ORACLE_TEST_USER");
        String password = required("ORACLE_TEST_PASSWORD");
        if (url.toLowerCase(Locale.ROOT).contains("password=") || url.contains(password)) {
            throw new IllegalArgumentException("Credentials must not be embedded in ORACLE_TEST_URL");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(30000);
        config.setInitializationFailTimeout(5000);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        assertEquals(user.toUpperCase(Locale.ROOT), jdbc.queryForObject("SELECT USER FROM dual", String.class),
                "Connected schema differs from configured test schema");
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Configuration configuration = new Configuration();
        configuration.addMapper(OracleInventoryMapper.class);
        configuration.addMapper(ScopedOutboxMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        inventory = session.getMapper(OracleInventoryMapper.class);
        scopedOutbox = session.getMapper(ScopedOutboxMapper.class);
        posting = new OraclePostingService(inventory, tx, JSON);
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void postedShipmentReachesLoopbackMockAndReconciles() throws Exception {
        String id = UUID.randomUUID().toString();
        String warehouse = "e2e-w-" + id;
        String sku = "e2e-s-" + id;
        tx.executeWithoutResult(status -> {
            assertEquals(1, inventory.insertStock(warehouse, sku));
            assertEquals(1, inventory.incrementStock(warehouse, sku, BigDecimal.TEN));
            assertEquals(1, inventory.insertMovement(uuid(), "OPENING", uuid(), uuid(), warehouse, sku, BigDecimal.TEN));
        });

        var result = posting.postShipment("e2e-post-" + id, new OraclePostingService.ShipmentCommand(List.of(
                new OraclePostingService.ShipmentItem(warehouse, sku, 3, new BigDecimal("2.50")))));
        assertTrue(result.created());
        String eventId = jdbc.queryForObject("SELECT id FROM ir_outbox WHERE document_type='SHIPMENT' AND document_id=?",
                String.class, result.documentId());
        assertNotNull(eventId);
        assertEquals(UUID.fromString(eventId).toString(), eventId);
        assertAmount("7", jdbc.queryForObject("SELECT quantity FROM ir_stock WHERE warehouse_id=? AND sku=?",
                BigDecimal.class, warehouse, sku));
        assertAmount("7", jdbc.queryForObject("SELECT SUM(quantity_delta) FROM ir_stock_movements WHERE warehouse_id=? AND sku=?",
                BigDecimal.class, warehouse, sku));
        assertAmount("7.50", journal(result.documentId(), "RECEIVABLE"));
        assertAmount("-7.50", journal(result.documentId(), "REVENUE"));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM ir_outbox WHERE id=?", String.class, eventId));

        AtomicReference<JsonNode> received = new AtomicReference<>();
        AtomicInteger applications = new AtomicInteger();
        HttpServer mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mock.createContext("/apply", exchange -> {
            try (exchange) {
                JsonNode request = JSON.readTree(exchange.getRequestBody().readNBytes(128 * 1024));
                if (!"POST".equals(exchange.getRequestMethod()) || request == null
                        || !eventId.equals(request.path("key").asText()) || !request.path("payload").isObject()) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                JsonNode payload = request.path("payload");
                JsonNode prior = received.get();
                if (prior != null && !prior.equals(payload)) {
                    exchange.sendResponseHeaders(409, -1);
                    return;
                }
                if (received.compareAndSet(null, payload)) applications.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
            }
        });
        mock.createContext("/state", exchange -> {
            try (exchange) {
                JsonNode payload = received.get();
                byte[] response = JSON.writeValueAsBytes(payload == null
                        ? Map.of("applied", Map.of(), "payloads", Map.of())
                        : Map.of("applied", Map.of(eventId, applications.get()), "payloads", Map.of(eventId, payload)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        mock.start();
        try {
            String base = "http://127.0.0.1:" + mock.getAddress().getPort();
            OracleMockHttpDelivery delivery = new OracleMockHttpDelivery(base + "/apply", JSON);
            OracleOutboxProcessor processor = new OracleOutboxProcessor(scopedOutbox, tx, delivery, 30, 5);
            OwnEventSql.set(eventId);
            try {
                assertEquals(OracleOutboxProcessor.Outcome.SENT, processor.processOne());
            } finally {
                OwnEventSql.clear();
            }
            assertEquals(1, applications.get());
            JsonNode payload = received.get();
            assertNotNull(payload);
            assertEquals(eventId, payload.path("eventId").asText());
            assertEquals(result.documentId(), payload.path("documentId").asText());
            assertEquals("SHIPMENT", payload.path("type").asText());
            assertEquals(1, payload.path("lines").size());
            JsonNode line = payload.path("lines").get(0);
            assertEquals(warehouse, line.path("warehouse").asText());
            assertEquals(sku, line.path("sku").asText());
            assertEquals(-3, line.path("quantityDelta").longValue());
            assertAmount("7.50", line.path("receivableDelta").decimalValue());
            assertEquals("SENT", jdbc.queryForObject("SELECT status FROM ir_outbox WHERE id=?", String.class, eventId));

            OracleEventReconciliation.Observation observation =
                    new OracleEventReconciliation(jdbc, base + "/state", JSON).compare(eventId);
            assertEquals("SENT", observation.oracleStatus());
            assertEquals(1, observation.mockApplicationCount());
            assertEquals(SnapshotReconciliation.Status.MATCH, observation.result().status());
        } finally {
            mock.stop(0);
        }
    }

    /** Only this override differs from the production mapper's global selection. */
    public interface ScopedOutboxMapper extends OracleOutboxMapper {
        @Override
        @SelectProvider(type = OwnEventSql.class, method = "select")
        @Options(fetchSize = 1)
        Cursor<Map<String, Object>> lockReady();
    }

    public static final class OwnEventSql {
        private static final ThreadLocal<String> ID = new ThreadLocal<>();
        static void set(String id) { ID.set(UUID.fromString(id).toString()); }
        static void clear() { ID.remove(); }
        public static String select() {
            String id = ID.get();
            if (id == null) throw new IllegalStateException("test event boundary is required");
            // UUID is parsed and formatted above, so it cannot contain SQL syntax.
            return "SELECT id, payload, attempts FROM ir_outbox WHERE id = '" + id + "' AND "
                    + "((status = 'PENDING' AND next_attempt_at <= SYSTIMESTAMP) OR "
                    + "(status = 'PROCESSING' AND lease_until <= SYSTIMESTAMP)) FOR UPDATE SKIP LOCKED";
        }
    }

    private static BigDecimal journal(String documentId, String account) {
        return jdbc.queryForObject("SELECT SUM(amount) FROM ir_journal_entries WHERE document_type='SHIPMENT' AND document_id=? AND account=?",
                BigDecimal.class, documentId, account);
    }
    private static void assertAmount(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
    private static String uuid() { return UUID.randomUUID().toString(); }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set for the Oracle end-to-end test");
        return value;
    }
}
