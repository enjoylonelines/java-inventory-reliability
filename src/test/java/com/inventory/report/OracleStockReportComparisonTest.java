package com.inventory.report;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual mapper SQL, no access-path hints; DDL and fixtures only in the named empty test schema. */
@EnabledIfEnvironmentVariable(named = "ORACLE_REPORT_EXPERIMENT", matches = "true")
class OracleStockReportComparisonTest {
    private static final int SKU_COUNT = 100, ROWS_PER_SKU = 1000, ROUNDS = 3, SAMPLES = 100, WARMUPS = 30;

    @Test void compareActualMapperBeforeAndAfterCoveringIndex() throws Exception {
        String user = required("ORACLE_REPORT_USER");
        assertEquals("INVENTORY_REPORT_TEST", user.toUpperCase(Locale.ROOT));
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(required("ORACLE_TEST_URL"));
        config.setUsername(user);
        config.setPassword(required("ORACLE_TEST_PASSWORD"));
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        try (HikariDataSource source = new HikariDataSource(config)) {
            JdbcTemplate db = new JdbcTemplate(source);
            assertEquals("INVENTORY_REPORT_TEST", db.queryForObject("SELECT USER FROM dual", String.class));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM ir_stock", Integer.class), "fresh isolated schema required");
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM ir_stock_movements", Integer.class));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM user_indexes WHERE index_name='IR_MOVEMENT_STOCK_COVER_IDX'", Integer.class));
            String runId = "oracle-stock-report-" + UUID.randomUUID(), warehouse = "report-" + UUID.randomUUID();
            String target = seed(db, new TransactionTemplate(new DataSourceTransactionManager(source)), warehouse);
            assertEquals(SKU_COUNT * ROWS_PER_SKU, db.queryForObject("SELECT COUNT(*) FROM ir_stock_movements", Integer.class));
            db.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'IR_STOCK', CASCADE => TRUE); DBMS_STATS.GATHER_TABLE_STATS(USER, 'IR_STOCK_MOVEMENTS', CASCADE => TRUE); END;");
            Configuration mybatis = new Configuration();
            mybatis.addMapper(OracleReportMapper.class);
            String sql = mybatis.getMappedStatement(OracleReportMapper.class.getName() + ".stock")
                .getBoundSql(Map.of("warehouse", warehouse, "sku", target)).getSql();
            Map<String, Object> before, after;
            String database;
            try (Connection connection = source.getConnection()) {
                database = connection.getMetaData().getDatabaseProductVersion();
                try (Statement session = connection.createStatement()) { session.execute("ALTER SESSION SET STATISTICS_LEVEL=ALL"); }
                before = measure(connection, sql, warehouse, target);
                // This schema is dedicated to the experiment and is never cleaned up automatically.
                try (Statement ddl = connection.createStatement()) {
                    ddl.execute("CREATE INDEX ir_movement_stock_cover_idx ON ir_stock_movements (warehouse_id, sku, quantity_delta)");
                }
                after = measure(connection, sql, warehouse, target);
            }
            double before95 = ((Number) before.get("p95_ms")).doubleValue();
            double after95 = ((Number) after.get("p95_ms")).doubleValue();
            Map<String, Object> evidence = Map.ofEntries(
                Map.entry("run_id", runId), Map.entry("timestamp_utc", Instant.now().toString()),
                Map.entry("scope", "actual OracleReportMapper.stock SQL on a dedicated local Oracle test schema; no access path hints"),
                Map.entry("environment", Map.of("database", database, "java", System.getProperty("java.version"), "os", System.getProperty("os.name"), "arch", System.getProperty("os.arch"))),
                Map.entry("fixture", Map.of("skus", SKU_COUNT, "movements_per_sku", ROWS_PER_SKU, "movement_rows", SKU_COUNT * ROWS_PER_SKU,
                    "warehouse", warehouse, "target_sku", target, "balance", 999, "ledger", 999)),
                Map.entry("workload", Map.of("warmups_each", WARMUPS, "rounds_each", ROUNDS, "samples_per_round", SAMPLES,
                    "order", "all baseline rounds then create index then all candidate rounds", "percentile", "nearest rank", "sql", sql,
                    "candidate_ddl", "CREATE INDEX ir_movement_stock_cover_idx ON ir_stock_movements (warehouse_id, sku, quantity_delta)")),
                Map.entry("baseline", before), Map.entry("candidate", after),
                Map.entry("p95_reduction_percent", (before95 - after95) * 100 / before95),
                Map.entry("limitations", List.of("local synthetic data, not production or cross-database comparison", "arms are sequential, not interleaved",
                    "STATISTICS_LEVEL=ALL and JDBC round-trip add overhead", "shared host and cache state not controlled",
                    "index write/storage cost and production deployment not measured", "fixture and candidate index remain in dedicated test schema")),
                Map.entry("source_sha256", Map.of("mapper", sha256(Path.of("src/main/java/com/inventory/report/OracleReportMapper.java")),
                    "schema", sha256(Path.of("src/main/resources/oracle/schema.sql")),
                    "test", sha256(Path.of("src/test/java/com/inventory/report/OracleStockReportComparisonTest.java")))));
            Path output = Path.of("evidence", runId + ".json");
            try (var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(stream, evidence);
            }
            System.out.println("Stock report evidence: " + output);
        }
    }

    private static String seed(JdbcTemplate db, TransactionTemplate tx, String warehouse) {
        String target = "sku-0-" + UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            List<Object[]> stock = new ArrayList<>(), movements = new ArrayList<>();
            for (int i = 0; i < SKU_COUNT; i++) {
                String sku = i == 0 ? target : "sku-" + i + "-" + UUID.randomUUID();
                stock.add(new Object[]{warehouse, sku, 999});
            }
            db.batchUpdate("INSERT INTO ir_stock (warehouse_id,sku,quantity) VALUES (?,?,?)", stock);
            for (Object[] row : stock) {
                String sku = (String) row[1];
                for (int i = 0; i < ROWS_PER_SKU; i++) {
                    String type = i == 0 ? "OPENING" : i % 2 == 1 ? "SHIPMENT" : "RETURN";
                    movements.add(new Object[]{UUID.randomUUID().toString(), type, UUID.randomUUID().toString(), UUID.randomUUID().toString(), warehouse, sku, i == 0 ? 1000 : i % 2 == 1 ? -1 : 1});
                    if (movements.size() == 1000) { insert(db, movements); movements.clear(); }
                }
            }
            if (!movements.isEmpty()) insert(db, movements);
        });
        return target;
    }
    private static void insert(JdbcTemplate db, List<Object[]> rows) {
        db.batchUpdate("INSERT INTO ir_stock_movements(id,document_type,document_id,document_line_id,warehouse_id,sku,quantity_delta) VALUES(?,?,?,?,?,?,?)", rows);
    }
    private static Map<String, Object> measure(Connection c, String sql, String warehouse, String sku) throws Exception {
        List<Double> samples = new ArrayList<>();
        List<Map<String, Object>> rounds = new ArrayList<>();
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, warehouse); statement.setString(2, sku);
            for (int i = 0; i < WARMUPS; i++) execute(statement);
            for (int round = 1; round <= ROUNDS; round++) {
                List<Double> values = new ArrayList<>();
                for (int i = 0; i < SAMPLES; i++) { long started = System.nanoTime(); execute(statement); values.add((System.nanoTime() - started) / 1_000_000.0); }
                samples.addAll(values);
                rounds.add(Map.of("round", round, "p50_ms", percentile(values, .5), "p95_ms", percentile(values, .95), "samples_ms", values));
            }
        }
        List<String> plan = new ArrayList<>();
        try (Statement statement = c.createStatement(); ResultSet rows = statement.executeQuery("SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL,NULL,'ALLSTATS LAST'))")) {
            while (rows.next()) plan.add(rows.getString(1));
        }
        return Map.of("sample_count", samples.size(), "p50_ms", percentile(samples, .5), "p95_ms", percentile(samples, .95), "rounds", rounds, "actual_cursor_plan", plan);
    }
    private static void execute(PreparedStatement statement) throws Exception {
        try (ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(0, new BigDecimal("999").compareTo(rows.getBigDecimal("BALANCE")));
            assertEquals(0, new BigDecimal("999").compareTo(rows.getBigDecimal("LEDGER_QUANTITY")));
            assertEquals(0, BigDecimal.ZERO.compareTo(rows.getBigDecimal("DIFFERENCE")));
            assertFalse(rows.next());
        }
    }
    private static double percentile(List<Double> values, double rank) { double[] sorted = values.stream().mapToDouble(Double::doubleValue).toArray(); Arrays.sort(sorted); return sorted[(int) Math.ceil(rank * sorted.length) - 1]; }
    private static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException(name + " required"); return value; }
    private static String sha256(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
}
