package com.inventory.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.StringReader;
import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.cursor.Cursor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in integration tests using the unmodified global outbox cursor. The Oracle
 * account must be dedicated solely to this test run, with no concurrent external
 * worker or writer. Before any fixture is created, every project table must be
 * empty. No data is deleted; rerunning requires a fresh, separately prepared schema.
 */
@EnabledIfEnvironmentVariable(named = "ORACLE_INTEGRATION_TEST", matches = "true")
class OracleOutboxIntegrationTest {
    private static final Set<String> ALLOWED_TEST_USERS = Set.of("INVENTORY_OUTBOX_TEST", "INVENTORY_OUTBOX_TEST2");
    private static final List<String> TABLES = List.of("IR_ORDERS", "IR_ORDER_LINES", "IR_SHIPMENTS",
        "IR_SHIPMENT_LINES", "IR_RETURNS", "IR_RETURN_LINES", "IR_STOCK", "IR_STOCK_MOVEMENTS",
        "IR_JOURNAL_ENTRIES", "IR_REQUEST_KEYS", "IR_OUTBOX", "IR_OUTBOX_HISTORY", "IR_ACTION_AUDIT");
    private static final Set<String> OWN_EVENTS = ConcurrentHashMap.newKeySet();
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static OracleOutboxMapper mapper;

    @BeforeAll
    static void connectOnlyToEmptyDedicatedSchema() throws Exception {
        String url = required("ORACLE_TEST_URL");
        String user = required("ORACLE_OUTBOX_TEST_USER");
        String password = required("ORACLE_TEST_PASSWORD");
        if (!ALLOWED_TEST_USERS.contains(user.toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("The dedicated outbox test account is required");
        }
        if (url.toLowerCase(Locale.ROOT).contains("password=") || url.contains(password)) {
            throw new IllegalArgumentException("Credentials must not be embedded in ORACLE_TEST_URL");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(30000);
        config.setInitializationFailTimeout(5000);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        assertEquals(user.toUpperCase(Locale.ROOT), jdbc.queryForObject("SELECT USER FROM dual", String.class), "Wrong Oracle schema");

        // Identifiers come only from the fixed allow-list above, never from environment or DB contents.
        Set<String> installed = new HashSet<>(jdbc.queryForList("SELECT table_name FROM user_tables", String.class));
        assertTrue(installed.containsAll(TABLES), "Prepared project tables are required");
        assertEquals(new HashSet<>(TABLES), installed, "Unexpected tables in the dedicated test schema");
        for (String table : TABLES) {
            assertEquals(0, count("SELECT COUNT(*) FROM " + table), "Dedicated test schema must start empty: " + table);
        }

        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Configuration configuration = new Configuration();
        configuration.addMapper(OracleOutboxMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        mapper = new SqlSessionTemplate(java.util.Objects.requireNonNull(factory.getObject())).getMapper(OracleOutboxMapper.class);
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) dataSource.close();
    }

    @BeforeEach
    void refuseUnknownOrUnfinishedRows() {
        List<String> actualIds = jdbc.queryForList("SELECT id FROM ir_outbox", String.class);
        assertEquals(OWN_EVENTS, new HashSet<>(actualIds), "Unexpected outbox row: refusing global worker");
        assertEquals(0, count("SELECT COUNT(*) FROM ir_outbox WHERE status IN ('PENDING','PROCESSING')"),
            "An earlier test left a pending or leased event: refusing global worker");
    }

    @Test
    void concurrentWorkersClaimDifferentEventsAndReadClobPayloads() throws Exception {
        String firstPayload = "{\"fixture\":\"" + "한".repeat(7000) + "-first\"}";
        String secondPayload = "{\"fixture\":\"" + "x".repeat(9000) + "-second\"}";
        String first = addEvent(firstPayload);
        String second = addEvent(secondPayload);
        var delivered = new ConcurrentHashMap<String, String>();
        CountDownLatch bothDelivering = new CountDownLatch(2);
        OracleMockDelivery delivery = (id, payload) -> {
            if (delivered.putIfAbsent(id, payload) != null) throw new IllegalStateException("duplicate fixture delivery");
            bothDelivering.countDown();
            if (!bothDelivering.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("two claims did not overlap");
        };
        OracleOutboxProcessor worker = processor(delivery, 5);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<OracleOutboxProcessor.Outcome> a = executor.submit(worker::processOne);
            Future<OracleOutboxProcessor.Outcome> b = executor.submit(worker::processOne);
            assertEquals(OracleOutboxProcessor.Outcome.SENT, a.get(60, TimeUnit.SECONDS));
            assertEquals(OracleOutboxProcessor.Outcome.SENT, b.get(60, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS));
        }
        assertEquals(2, delivered.size());
        assertEquals(firstPayload, delivered.get(first));
        assertEquals(secondPayload, delivered.get(second));
        assertEventState(first, "SENT", 1);
        assertEventState(second, "SENT", 1);
        assertHistory(first, "CLAIMED", 1);
        assertHistory(first, "SENT", 1);
        assertHistory(second, "CLAIMED", 1);
        assertHistory(second, "SENT", 1);
    }

    @Test
    void cursorSkipsAnEventLockedByAnotherTransaction() throws Exception {
        String first = addEvent("{\"fixture\":\"lock-one\"}");
        String second = addEvent("{\"fixture\":\"lock-two\"}");
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> holder = executor.submit(() -> tx.execute(status -> {
                try (Cursor<Map<String, Object>> cursor = mapper.lockReady()) {
                    Map<String, Object> row = cursor.iterator().next();
                    String id = String.valueOf(row.containsKey("ID") ? row.get("ID") : row.get("id"));
                    locked.countDown();
                    if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("lock test timed out");
                    status.setRollbackOnly();
                    return id;
                } catch (Exception e) { throw new IllegalStateException(e); }
            }));
            assertTrue(locked.await(20, TimeUnit.SECONDS));
            Future<String> follower = executor.submit(() -> tx.execute(status -> {
                try (Cursor<Map<String, Object>> cursor = mapper.lockReady()) {
                    Map<String, Object> row = cursor.iterator().next();
                    status.setRollbackOnly();
                    return String.valueOf(row.containsKey("ID") ? row.get("ID") : row.get("id"));
                } catch (Exception e) { throw new IllegalStateException(e); }
            }));
            String secondId = follower.get(20, TimeUnit.SECONDS);
            release.countDown();
            String firstId = holder.get(20, TimeUnit.SECONDS);
            assertNotEquals(firstId, secondId);
            assertEquals(Set.of(first, second), Set.of(firstId, secondId));
        } finally {
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
        OracleOutboxProcessor worker = processor((id, payload) -> {}, 5);
        assertEquals(OracleOutboxProcessor.Outcome.SENT, worker.processOne());
        assertEquals(OracleOutboxProcessor.Outcome.SENT, worker.processOne());
    }

    @Test
    void staleLeaseTokenCannotCompleteOrFailAReclaimedEvent() {
        String id = addEvent("{\"fixture\":\"stale\"}");
        String oldToken = uuid(), newToken = uuid();
        assertEquals(Integer.valueOf(1), tx.execute(status -> mapper.claim(id, oldToken, 30)));
        // Alter only this test's event to avoid waiting on wall-clock lease expiry.
        assertEquals(1, jdbc.update("UPDATE ir_outbox SET lease_until=SYSTIMESTAMP-NUMTODSINTERVAL(1, 'SECOND') WHERE id=? AND lease_token=?", id, oldToken));
        assertEquals(Integer.valueOf(1), tx.execute(status -> mapper.claim(id, newToken, 30)));
        assertEquals(Integer.valueOf(0), tx.execute(status -> mapper.sent(id, oldToken)));
        assertEquals(Integer.valueOf(0), tx.execute(status -> mapper.fail(id, oldToken, 5, 1, "stale")));
        assertEquals(Integer.valueOf(1), tx.execute(status -> mapper.sent(id, newToken)));
        assertEventState(id, "SENT", 2);
    }

    @Test
    void lostResponseIsRetriedWithSameEventAndDeduplicatedByTheMock() {
        String payload = "{\"fixture\":\"response-lost\"}";
        String id = addEvent(payload);
        var applied = new ConcurrentHashMap<String, String>();
        AtomicInteger attempts = new AtomicInteger();
        OracleMockDelivery receiver = (eventId, body) -> {
            String prior = applied.putIfAbsent(eventId, body);
            if (prior != null && !prior.equals(body)) throw new IllegalStateException("payload conflict");
            if (attempts.incrementAndGet() == 1) throw new IOException("injected lost response after apply");
        };
        OracleOutboxProcessor worker = processor(receiver, 5);
        assertEquals(OracleOutboxProcessor.Outcome.RETRY, worker.processOne());
        assertEventState(id, "PENDING", 1);
        makeOwnPendingEventReady(id);
        assertEquals(OracleOutboxProcessor.Outcome.SENT, worker.processOne());
        assertEventState(id, "SENT", 2);
        assertEquals(2, attempts.get());
        assertEquals(1, applied.size());
        assertEquals(payload, applied.get(id));
        assertHistory(id, "CLAIMED", 2);
        assertHistory(id, "RETRY", 1);
        assertHistory(id, "SENT", 1);
    }

    @Test
    void exhaustedRetriesBecomeDeadAndManualRequeuePreservesHistory() {
        String id = addEvent("{\"fixture\":\"dead\"}");
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        OracleMockDelivery delivery = (eventId, payload) -> {
            assertEquals(id, eventId);
            attempts.incrementAndGet();
            if (fail.get()) throw new IOException("injected unavailable receiver");
        };
        OracleOutboxProcessor worker = processor(delivery, 3);
        assertEquals(OracleOutboxProcessor.Outcome.RETRY, worker.processOne());
        makeOwnPendingEventReady(id);
        assertEquals(OracleOutboxProcessor.Outcome.RETRY, worker.processOne());
        makeOwnPendingEventReady(id);
        assertEquals(OracleOutboxProcessor.Outcome.DEAD, worker.processOne());
        assertEventState(id, "DEAD", 3);
        assertHistory(id, "RETRY", 2);
        assertHistory(id, "DEAD", 1);
        assertTrue(worker.requeueDead(id, "integration-test"));
        assertEquals(1, count("SELECT COUNT(*) FROM ir_outbox_history WHERE outbox_id=? AND transition='REQUEUED' AND actor='integration-test'", id));
        fail.set(false);
        assertEquals(OracleOutboxProcessor.Outcome.SENT, worker.processOne());
        assertEventState(id, "SENT", 1);
        assertEquals(4, attempts.get());
        assertHistory(id, "CLAIMED", 4);
        assertHistory(id, "RETRY", 2);
        assertHistory(id, "DEAD", 1);
        assertHistory(id, "REQUEUED", 1);
        assertHistory(id, "SENT", 1);
    }

    private static OracleOutboxProcessor processor(OracleMockDelivery delivery, int maxAttempts) {
        return new OracleOutboxProcessor(mapper, tx, delivery, 30, maxAttempts);
    }
    private static String addEvent(String payload) {
        String id = uuid();
        String documentId = uuid();
        int changed = jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ir_outbox (id, document_type, document_id, payload) VALUES (?, 'SHIPMENT', ?, ?)");
            statement.setString(1, id);
            statement.setString(2, documentId);
            statement.setCharacterStream(3, new StringReader(payload), payload.length());
            return statement;
        });
        assertEquals(1, changed);
        OWN_EVENTS.add(id);
        return id;
    }
    private static void makeOwnPendingEventReady(String id) {
        assertTrue(OWN_EVENTS.contains(id));
        assertEquals(1, jdbc.update("UPDATE ir_outbox SET next_attempt_at=SYSTIMESTAMP-NUMTODSINTERVAL(1, 'SECOND') WHERE id=? AND status='PENDING'", id));
    }
    private static void assertEventState(String id, String expectedStatus, int attempts) {
        assertEquals(1, count("SELECT COUNT(*) FROM ir_outbox WHERE id=? AND status=? AND attempts=?", id, expectedStatus, attempts));
    }
    private static void assertHistory(String id, String transition, int expected) {
        assertEquals(expected, count("SELECT COUNT(*) FROM ir_outbox_history WHERE outbox_id=? AND transition=?", id, transition));
    }
    private static int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private static String uuid() { return UUID.randomUUID().toString(); }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set for Oracle integration tests");
        return value;
    }
}
