package com.inventory.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.application.OraclePostingService;
import com.inventory.application.OraclePostingService.ReturnCommand;
import com.inventory.application.OraclePostingService.ReturnItem;
import com.inventory.application.OraclePostingService.ShipmentCommand;
import com.inventory.application.OraclePostingService.ShipmentItem;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.session.Configuration;
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
 * Opt-in Oracle integration test. It requires an already-provisioned, dedicated test
 * schema from oracle/schema.sql. It never executes DDL or deletes existing data;
 * UUID-namespaced test rows remain for inspection after a run. These tests are not
 * PostgreSQL evidence, production traffic, or a guarantee about other writers.
 */
@EnabledIfEnvironmentVariable(named = "ORACLE_INTEGRATION_TEST", matches = "true")
class OraclePostingIntegrationTest {
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static OracleInventoryMapper mapper;
    private static OraclePostingService service;
    private String warehouse;
    private String sku;
    private String requestPrefix;

    @BeforeAll
    static void connectToPreparedDedicatedSchema() throws Exception {
        String url = requiredEnvironment("ORACLE_TEST_URL");
        String user = requiredEnvironment("ORACLE_TEST_USER");
        String password = requiredEnvironment("ORACLE_TEST_PASSWORD");
        // Credentials belong in their own environment variables, never in the URL.
        if (url.toLowerCase(java.util.Locale.ROOT).contains("password=") || url.contains(password)) {
            throw new IllegalArgumentException("Credentials must not be embedded in ORACLE_TEST_URL");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(30000);
        config.setInitializationFailTimeout(5000);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        Configuration mybatis = new Configuration();
        mybatis.addMapper(OracleInventoryMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(mybatis);
        mapper = new SqlSessionTemplate(java.util.Objects.requireNonNull(factory.getObject())).getMapper(OracleInventoryMapper.class);
        service = new OraclePostingService(mapper, transaction, new ObjectMapper());
        int tables = jdbc.queryForObject("""
            SELECT count(*) FROM user_tables WHERE table_name IN
              ('IR_ORDERS','IR_ORDER_LINES','IR_SHIPMENTS','IR_SHIPMENT_LINES',
               'IR_RETURNS','IR_RETURN_LINES','IR_STOCK','IR_STOCK_MOVEMENTS',
               'IR_JOURNAL_ENTRIES','IR_REQUEST_KEYS','IR_OUTBOX')
            """, Integer.class);
        assertEquals(11, tables, "A prepared dedicated test schema is required; tests never run DDL");
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) dataSource.close();
    }

    @BeforeEach
    void provisionUniqueStockAndOpeningMovement() {
        String id = UUID.randomUUID().toString();
        warehouse = "test-w-" + id;
        sku = "test-s-" + id;
        requestPrefix = "oracle-it-" + id + "-";
        transaction.executeWithoutResult(status -> {
            assertEquals(1, mapper.insertStock(warehouse, sku));
            assertEquals(1, mapper.incrementStock(warehouse, sku, BigDecimal.valueOf(60)));
            assertEquals(1, mapper.insertMovement(uuid(), "OPENING", uuid(), uuid(), warehouse, sku, BigDecimal.valueOf(60)));
        });
        assertStockAndLedger(60);
    }

    @Test
    void oneHundredConcurrentOrdersCannotExceedSixtyAvailableUnits() throws Exception {
        List<Attempt> attempts = concurrently(100, i -> submit(requestPrefix + i, shipment(1, "1.25")));
        assertEquals(60, attempts.stream().filter(a -> a.created).count());
        assertEquals(40, attempts.stream().filter(a -> "StockUnavailable".equals(a.error)).count());
        assertEquals(0, attempts.stream().filter(a -> a.error != null && !a.error.equals("StockUnavailable")).count(),
            "Unexpected errors are not treated as stock rejection");
        assertStockAndLedger(0);
        assertEquals(60, count("SELECT count(*) FROM ir_request_keys WHERE request_key LIKE ?", requestPrefix + "%"));
        assertEquals(60, count("SELECT count(*) FROM ir_shipment_lines WHERE warehouse_id=? AND sku=?", warehouse, sku));
        assertEquals(60, count("""
            SELECT count(*) FROM ir_shipments s JOIN ir_shipment_lines l ON l.shipment_id=s.id
            WHERE l.warehouse_id=? AND l.sku=? AND s.status='POSTED'
            """, warehouse, sku));
        assertEquals(60, count("SELECT count(*) FROM ir_stock_movements WHERE warehouse_id=? AND sku=? AND document_type='SHIPMENT'", warehouse, sku));
        assertEquals(60, count("""
            SELECT count(*) FROM ir_outbox o JOIN ir_shipment_lines l ON l.shipment_id=o.document_id
            WHERE o.document_type='SHIPMENT' AND l.warehouse_id=? AND l.sku=?
            """, warehouse, sku));
        assertMoney("75.00", moneyForSku("RECEIVABLE"));
        assertMoney("-75.00", moneyForSku("REVENUE"));
    }

    @Test
    void simultaneousRetriesOfSameRequestProduceOneDocument() throws Exception {
        String key = requestPrefix + "same";
        List<Attempt> attempts = concurrently(40, i -> submit(key, shipment(1, "2.50")));
        assertEquals(0, attempts.stream().filter(a -> a.error != null).count());
        assertEquals(1, attempts.stream().filter(a -> a.created).count());
        Set<String> documentIds = new HashSet<>();
        attempts.forEach(a -> documentIds.add(a.documentId));
        assertEquals(1, documentIds.size());
        String id = documentIds.iterator().next();
        assertEquals(1, count("SELECT count(*) FROM ir_request_keys WHERE request_key=?", key));
        assertEquals(1, count("SELECT count(*) FROM ir_shipments WHERE id=? AND status='POSTED'", id));
        assertEquals(1, count("SELECT count(*) FROM ir_outbox WHERE document_type='SHIPMENT' AND document_id=?", id));
        assertEquals(1, count("SELECT count(*) FROM ir_stock_movements WHERE document_type='SHIPMENT' AND document_id=?", id));
        assertEquals(2, count("SELECT count(*) FROM ir_journal_entries WHERE document_type='SHIPMENT' AND document_id=?", id));
        assertEquals(1, count("SELECT count(*) FROM ir_action_audit WHERE document_type='SHIPMENT' AND document_id=? AND actor='system' AND action='POST'", id));
        assertStockAndLedger(59);
        assertThrows(OraclePostingService.RequestConflict.class, () -> service.postShipment(key, shipment(2, "2.50")));
        assertStockAndLedger(59);
    }

    @Test
    void threePartialReturnsFullyReverseTheOriginalAndRejectAnExcess() throws Exception {
        var original = service.postShipment(requestPrefix + "shipment", shipment(3, "2.50"));
        String line = jdbc.queryForObject("SELECT id FROM ir_shipment_lines WHERE shipment_id=?", String.class, original.documentId());
        assertNotNull(line);
        assertEvent(original.documentId(), "SHIPMENT", -3, "7.50");
        List<String> returns = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var returned = service.postReturn(requestPrefix + "return-" + i,
                new ReturnCommand(original.documentId(), List.of(new ReturnItem(line, 1))));
            assertTrue(returned.created());
            returns.add(returned.documentId());
            assertEvent(returned.documentId(), "RETURN", 1, "-2.50");
            assertStockAndLedger(58 + i);
        }
        assertEquals(3, new HashSet<>(returns).size());
        assertEquals(3, count("SELECT count(*) FROM ir_returns WHERE shipment_id=? AND status='POSTED'", original.documentId()));
        assertMoney("3", decimal("SELECT returned_quantity FROM ir_shipment_lines WHERE id=?", line));
        assertMoney("7.50", decimal("SELECT returned_amount FROM ir_shipment_lines WHERE id=?", line));
        assertMoney("3", decimal("SELECT SUM(quantity) FROM ir_return_lines WHERE shipment_line_id=?", line));
        assertMoney("7.50", decimal("SELECT SUM(amount) FROM ir_return_lines WHERE shipment_line_id=?", line));
        assertEquals(8, count("""
            SELECT count(*) FROM ir_journal_entries j
            JOIN ir_stock_movements m ON m.document_type=j.document_type AND m.document_line_id=j.document_line_id
            WHERE m.warehouse_id=? AND m.sku=?
            """, warehouse, sku));
        assertMoney("0", moneyForSku("RECEIVABLE"));
        assertMoney("0", moneyForSku("REVENUE"));
        assertEquals(3, count("""
            SELECT count(*) FROM ir_outbox o JOIN ir_returns r ON r.id=o.document_id
            WHERE o.document_type='RETURN' AND r.shipment_id=?
            """, original.documentId()));
        assertThrows(IllegalArgumentException.class, () -> service.postReturn(requestPrefix + "excess",
            new ReturnCommand(original.documentId(), List.of(new ReturnItem(line, 1)))));
        assertEquals(0, count("SELECT count(*) FROM ir_request_keys WHERE request_key=?", requestPrefix + "excess"));
        assertEquals(3, count("SELECT count(*) FROM ir_returns WHERE shipment_id=?", original.documentId()));
        assertStockAndLedger(60);
    }

    @Test
    void concurrentReturnsCannotExceedTheOriginalShipment() throws Exception {
        var original = service.postShipment(requestPrefix + "shipment", shipment(3, "2.50"));
        String line = jdbc.queryForObject("SELECT id FROM ir_shipment_lines WHERE shipment_id=?", String.class, original.documentId());
        ReturnCommand command = new ReturnCommand(original.documentId(), List.of(new ReturnItem(line, 1)));
        List<Attempt> attempts = concurrently(20, i -> {
            try {
                var result = service.postReturn(requestPrefix + "parallel-return-" + i, command);
                return new Attempt(result.documentId(), result.created(), null);
            } catch (RuntimeException e) {
                return new Attempt(null, false, e.getClass().getSimpleName());
            }
        });
        assertEquals(3, attempts.stream().filter(a -> a.created).count());
        assertEquals(17, attempts.stream().filter(a -> "IllegalArgumentException".equals(a.error)).count());
        assertEquals(0, attempts.stream().filter(a -> a.error != null && !a.error.equals("IllegalArgumentException")).count());
        assertEquals(3, count("SELECT count(*) FROM ir_returns WHERE shipment_id=? AND status='POSTED'", original.documentId()));
        assertMoney("3", decimal("SELECT returned_quantity FROM ir_shipment_lines WHERE id=?", line));
        assertMoney("7.50", decimal("SELECT returned_amount FROM ir_shipment_lines WHERE id=?", line));
        assertMoney("0", moneyForSku("RECEIVABLE"));
        assertMoney("0", moneyForSku("REVENUE"));
        assertStockAndLedger(60);
    }

    @Test
    void failureAfterAuditInsertRollsBackEveryBusinessWrite() {
        AtomicReference<String> orderId = new AtomicReference<>();
        AtomicReference<String> documentId = new AtomicReference<>();
        OracleInventoryMapper failing = (OracleInventoryMapper) Proxy.newProxyInstance(
            OracleInventoryMapper.class.getClassLoader(), new Class<?>[] { OracleInventoryMapper.class },
            (proxy, method, args) -> {
                try {
                    Object value = method.invoke(mapper, args);
                    if (method.getName().equals("insertOrder")) orderId.set((String) args[0]);
                    if (method.getName().equals("insertOutbox")) documentId.set((String) args[2]);
                    if (method.getName().equals("insertActionAudit")) throw new IllegalStateException("injected after audit insert");
                    return value;
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        var failingService = new OraclePostingService(failing, transaction, new ObjectMapper());
        String key = requestPrefix + "rollback";
        assertThrows(IllegalStateException.class, () -> failingService.postShipment(key, shipment(2, "2.50")));
        assertNotNull(orderId.get());
        assertNotNull(documentId.get());
        assertEquals(0, count("SELECT count(*) FROM ir_request_keys WHERE request_key=?", key));
        assertEquals(0, count("SELECT count(*) FROM ir_orders WHERE id=?", orderId.get()));
        assertEquals(0, count("SELECT count(*) FROM ir_order_lines WHERE order_id=?", orderId.get()));
        assertEquals(0, count("SELECT count(*) FROM ir_shipments WHERE id=?", documentId.get()));
        assertEquals(0, count("SELECT count(*) FROM ir_shipment_lines WHERE shipment_id=?", documentId.get()));
        assertEquals(0, count("SELECT count(*) FROM ir_stock_movements WHERE document_type='SHIPMENT' AND document_id=?", documentId.get()));
        assertEquals(0, count("SELECT count(*) FROM ir_journal_entries WHERE document_type='SHIPMENT' AND document_id=?", documentId.get()));
        assertEquals(0, count("SELECT count(*) FROM ir_outbox WHERE document_type='SHIPMENT' AND document_id=?", documentId.get()));
        assertEquals(0, count("SELECT count(*) FROM ir_action_audit WHERE document_type='SHIPMENT' AND document_id=?", documentId.get()));
        assertStockAndLedger(60);
        assertTrue(service.postShipment(key, shipment(2, "2.50")).created());
        assertStockAndLedger(58);
    }

    private ShipmentCommand shipment(long quantity, String unitPrice) {
        return new ShipmentCommand(List.of(new ShipmentItem(warehouse, sku, quantity, new BigDecimal(unitPrice))));
    }
    private void assertStockAndLedger(long expected) {
        BigDecimal balance = decimal("SELECT quantity FROM ir_stock WHERE warehouse_id=? AND sku=?", warehouse, sku);
        BigDecimal ledger = decimal("SELECT NVL(SUM(quantity_delta), 0) FROM ir_stock_movements WHERE warehouse_id=? AND sku=?", warehouse, sku);
        assertMoney(Long.toString(expected), balance);
        assertEquals(0, balance.compareTo(ledger), "current balance must equal signed movement sum");
        assertTrue(balance.signum() >= 0);
    }
    private BigDecimal moneyForSku(String account) {
        return decimal("""
            SELECT NVL(SUM(j.amount), 0) FROM ir_journal_entries j
            JOIN ir_stock_movements m ON m.document_type=j.document_type AND m.document_line_id=j.document_line_id
            WHERE m.warehouse_id=? AND m.sku=? AND j.account=?
            """, warehouse, sku, account);
    }
    private void assertEvent(String documentId, String type, long quantityDelta, String receivableDelta) throws Exception {
        String eventId = jdbc.queryForObject("SELECT id FROM ir_outbox WHERE document_type=? AND document_id=?", String.class, type, documentId);
        String payload = jdbc.queryForObject("SELECT payload FROM ir_outbox WHERE id=?", String.class, eventId);
        var json = new ObjectMapper().readTree(payload);
        assertEquals(eventId, json.path("eventId").asText());
        assertEquals(type, json.path("type").asText());
        assertEquals(documentId, json.path("documentId").asText());
        assertEquals(1, json.path("lines").size());
        var line = json.path("lines").get(0);
        assertEquals(warehouse, line.path("warehouse").asText());
        assertEquals(sku, line.path("sku").asText());
        assertEquals(quantityDelta, line.path("quantityDelta").longValue());
        assertMoney(receivableDelta, line.path("receivableDelta").decimalValue());
    }
    private static int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private static BigDecimal decimal(String sql, Object... args) { return jdbc.queryForObject(sql, BigDecimal.class, args); }
    private static void assertMoney(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual)); }
    private static String uuid() { return UUID.randomUUID().toString(); }
    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set for Oracle integration tests");
        return value;
    }
    private record Attempt(String documentId, boolean created, String error) {}
    @FunctionalInterface private interface Work { Attempt run(int index); }
    private static Attempt submit(String key, ShipmentCommand command) {
        try {
            var result = service.postShipment(key, command);
            return new Attempt(result.documentId(), result.created(), null);
        } catch (RuntimeException e) {
            // Record only the exception class, never connection details or credentials.
            return new Attempt(null, false, e.getClass().getSimpleName());
        }
    }
    private static List<Attempt> concurrently(int count, Work work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Attempt>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) return new Attempt(null, false, "start timeout");
                    return work.run(index);
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "workers did not reach the start gate");
            start.countDown();
            List<Attempt> result = new ArrayList<>();
            for (Future<Attempt> future : futures) result.add(future.get(120, TimeUnit.SECONDS));
            return result;
        } finally {
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "workers did not finish");
        }
    }
}
