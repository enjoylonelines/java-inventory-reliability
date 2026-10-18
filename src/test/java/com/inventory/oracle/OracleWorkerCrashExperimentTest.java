package com.inventory.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.application.OraclePostingService;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates data and kills only subprocesses started by this test. Never deletes data. */
@EnabledIfEnvironmentVariable(named = "ORACLE_CRASH_EXPERIMENT", matches = "true")
class OracleWorkerCrashExperimentTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int ROUNDS = 3;
    private static final List<String> TABLES = List.of("IR_ORDERS", "IR_ORDER_LINES", "IR_SHIPMENTS", "IR_SHIPMENT_LINES",
        "IR_RETURNS", "IR_RETURN_LINES", "IR_STOCK", "IR_STOCK_MOVEMENTS", "IR_JOURNAL_ENTRIES", "IR_REQUEST_KEYS",
        "IR_OUTBOX", "IR_OUTBOX_HISTORY", "IR_ACTION_AUDIT");

    @Test void realWorkerDiesAfterMockApplicationAndNewProcessRecovers() throws Exception {
        String user = required("ORACLE_CRASH_USER");
        assertTrue(java.util.Set.of("INVENTORY_CRASH_TEST", "INVENTORY_CRASH_TEST2").contains(user.toUpperCase(java.util.Locale.ROOT)));
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(required("ORACLE_TEST_URL"));
        config.setUsername(user);
        config.setPassword(required("ORACLE_TEST_PASSWORD"));
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        try (HikariDataSource source = new HikariDataSource(config)) {
            JdbcTemplate db = new JdbcTemplate(source);
            assertEquals(user.toUpperCase(java.util.Locale.ROOT), db.queryForObject("SELECT USER FROM dual", String.class));
            for (String table : TABLES) assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), "schema must be empty");
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            Configuration mybatis = new Configuration();
            mybatis.addMapper(OracleInventoryMapper.class);
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(mybatis);
            OracleInventoryMapper mapper = new SqlSessionTemplate(java.util.Objects.requireNonNull(factory.getObject()))
                .getMapper(OracleInventoryMapper.class);
            OraclePostingService posting = new OraclePostingService(mapper, tx, JSON);
            List<Map<String, Object>> rounds = new ArrayList<>();
            for (int round = 1; round <= ROUNDS; round++) rounds.add(runRound(db, tx, mapper, posting, round));

            String runId = "oracle-worker-crash-" + UUID.randomUUID();
            Map<String, Object> evidence = Map.of(
                "run_id", runId, "timestamp_utc", Instant.now().toString(),
                "scope", "local dedicated Oracle schema; synthetic shipment; child JVM terminated after loopback mock applied before response",
                "environment", Map.of("java", System.getProperty("java.version"), "os", System.getProperty("os.name"),
                    "arch", System.getProperty("os.arch"), "database", db.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductVersion())),
                "conditions", Map.of("rounds", ROUNDS, "lease_seconds", 5, "logical_events_per_round", 1,
                    "recovery_interval", "parent kill request to second worker completing SENT", "receiver", "in-memory HTTP receiver retained in parent process"),
                "rounds", rounds,
                "limitations", List.of("small sample; not a latency distribution or production recovery SLO",
                    "receiver and parent share one machine; mock is not an actual ERP", "restart is triggered by the test, not a supervisor",
                    "time includes remaining lease and starting a new JVM", "deduplication depends on receiver event ID handling"),
                "test_source_sha256", sha256(Path.of("src/test/java/com/inventory/oracle/OracleWorkerCrashExperimentTest.java")));
            Path output = Path.of("evidence", runId + ".json");
            try (var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                JSON.writerWithDefaultPrettyPrinter().writeValue(stream, evidence);
            }
            System.out.println("Crash experiment evidence: " + output);
        }
    }

    private static Map<String, Object> runRound(JdbcTemplate db, TransactionTemplate tx, OracleInventoryMapper mapper,
            OraclePostingService posting, int round) throws Exception {
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM ir_outbox WHERE status IN ('PENDING','PROCESSING')", Integer.class));
        String key = UUID.randomUUID().toString(), warehouse = "crash-w-" + key, sku = "crash-s-" + key;
        tx.executeWithoutResult(status -> {
            assertEquals(1, mapper.insertStock(warehouse, sku));
            assertEquals(1, mapper.incrementStock(warehouse, sku, BigDecimal.TEN));
            assertEquals(1, mapper.insertMovement(uuid(), "OPENING", uuid(), uuid(), warehouse, sku, BigDecimal.TEN));
        });
        var posted = posting.postShipment("crash-" + key, new OraclePostingService.ShipmentCommand(List.of(
            new OraclePostingService.ShipmentItem(warehouse, sku, 3, new BigDecimal("2.50")))));
        String event = db.queryForObject("SELECT id FROM ir_outbox WHERE document_id=?", String.class, posted.documentId());
        var applied = new ConcurrentHashMap<String, JsonNode>();
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch firstApplied = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        receiver.createContext("/apply", exchange -> {
            try (exchange) {
                JsonNode request = JSON.readTree(exchange.getRequestBody().readNBytes(128 * 1024));
                String receivedId = request.path("key").asText();
                if (!event.equals(receivedId) || !request.path("idempotent").asBoolean()) { exchange.sendResponseHeaders(400, -1); return; }
                JsonNode payload = request.path("payload"), previous = applied.putIfAbsent(receivedId, payload);
                if (previous != null && !previous.equals(payload)) { exchange.sendResponseHeaders(409, -1); return; }
                if (attempts.incrementAndGet() == 1) {
                    firstApplied.countDown();
                    try { releaseFirst.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                exchange.sendResponseHeaders(200, -1);
            } catch (Exception ignored) { /* the killed client's socket can close before the response */ }
        });
        receiver.start();
        Process first = null, second = null;
        try {
            String url = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/apply";
            first = worker(url);
            assertTrue(firstApplied.await(25, TimeUnit.SECONDS), "worker did not reach receiver");
            assertTrue(first.isAlive(), "worker exited before termination");
            assertEquals("PROCESSING", db.queryForObject("SELECT status FROM ir_outbox WHERE id=?", String.class, event));
            long started = System.nanoTime();
            first.destroyForcibly();
            assertTrue(first.waitFor(10, TimeUnit.SECONDS), "first worker did not terminate");
            releaseFirst.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (db.queryForObject("SELECT COUNT(*) FROM ir_outbox WHERE id=? AND lease_until <= SYSTIMESTAMP", Integer.class, event) == 0) {
                if (System.nanoTime() > deadline) fail("lease did not expire");
                Thread.sleep(100);
            }
            second = worker(url);
            assertTrue(second.waitFor(25, TimeUnit.SECONDS), "replacement worker timed out");
            assertEquals(0, second.exitValue(), "replacement worker failed");
            double recoveryMs = (System.nanoTime() - started) / 1_000_000.0;
            assertEquals("SENT", db.queryForObject("SELECT status FROM ir_outbox WHERE id=?", String.class, event));
            assertEquals(2, db.queryForObject("SELECT attempts FROM ir_outbox WHERE id=?", Integer.class, event));
            assertEquals(2, attempts.get());
            assertEquals(1, applied.size());
            JsonNode delivered = applied.get(event);
            assertNotNull(delivered);
            assertEquals(posted.documentId(), delivered.path("documentId").asText());
            assertEquals(-3, delivered.path("lines").get(0).path("quantityDelta").asInt());
            assertEquals(0, new BigDecimal("7.50").compareTo(delivered.path("lines").get(0).path("receivableDelta").decimalValue()));
            BigDecimal balance = db.queryForObject("SELECT quantity FROM ir_stock WHERE warehouse_id=? AND sku=?", BigDecimal.class, warehouse, sku);
            BigDecimal ledger = db.queryForObject("SELECT SUM(quantity_delta) FROM ir_stock_movements WHERE warehouse_id=? AND sku=?", BigDecimal.class, warehouse, sku);
            BigDecimal receivable = db.queryForObject("SELECT SUM(amount) FROM ir_journal_entries WHERE document_id=? AND account='RECEIVABLE'", BigDecimal.class, posted.documentId());
            BigDecimal revenue = db.queryForObject("SELECT SUM(amount) FROM ir_journal_entries WHERE document_id=? AND account='REVENUE'", BigDecimal.class, posted.documentId());
            assertEquals(0, new BigDecimal("7").compareTo(balance));
            assertEquals(0, balance.compareTo(ledger));
            assertEquals(0, new BigDecimal("7.50").compareTo(receivable));
            assertEquals(0, new BigDecimal("-7.50").compareTo(revenue));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM ir_shipments WHERE id=? AND status='POSTED'", Integer.class, posted.documentId()));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM ir_outbox WHERE document_id=?", Integer.class, posted.documentId()));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM ir_outbox_history WHERE outbox_id=? AND transition='SENT'", Integer.class, event));
            return Map.of("round", round, "event_id", event, "document_id", posted.documentId(), "delivery_attempts", attempts.get(),
                "applied_count", applied.size(), "state", "SENT", "remaining_stock", 7, "ledger_quantity", 7,
                "receivable", "7.50", "recovery_ms", recoveryMs);
        } finally {
            releaseFirst.countDown();
            if (first != null && first.isAlive()) first.destroyForcibly();
            if (second != null && second.isAlive()) second.destroyForcibly();
            receiver.stop(0);
        }
    }
    private static Process worker(String url) throws Exception {
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", cp,
            OracleCrashWorker.class.getName(), url).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }
    private static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException(name + " required"); return value; }
    private static String uuid() { return UUID.randomUUID().toString(); }
    private static String sha256(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
}
