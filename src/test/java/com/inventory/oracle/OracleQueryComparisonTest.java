package com.inventory.oracle;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in local Oracle experiment, not a production benchmark. It inserts unique
 * synthetic stock/movement rows into the explicitly supplied test schema and does
 * not delete them. Two queries differ only in an intentionally forced access-path
 * hint. EXPLAIN PLAN, when available, is an estimated plan, not proof of the actual
 * executed cursor plan. Cache conditions, other rows, and concurrent activity can
 * affect the result. Never compare these timings to PostgreSQL timings as a before
 * and after measurement. No external SQL, DDL, deletes, or statistics changes run.
 */
@EnabledIfEnvironmentVariable(named = "ORACLE_QUERY_EXPERIMENT", matches = "true")
class OracleQueryComparisonTest {
    private static final int SKUS = 100;
    private static final int ROWS_PER_SKU = 200;
    private static final int WARMUPS_PER_ARM = 20;
    private static final int ROUNDS = 3;
    private static final int SAMPLES_PER_ROUND_PER_ARM = 100;
    private static final String QUERY = "SELECT %s NVL(SUM(m.quantity_delta), 0), COUNT(*) "
        + "FROM ir_stock_movements m WHERE m.warehouse_id = ? AND m.sku = ?";
    private static final String FULL = QUERY.formatted("/*+ FULL(m) */");
    private static final String INDEX = QUERY.formatted("/*+ INDEX(m ir_movement_sku_date_idx) */");
    private static final BigDecimal EXPECTED_BALANCE = new BigDecimal("999");
    private record Result(BigDecimal amount, long count) {}
    private record Sample(double milliseconds, Result result) {}

    @Test
    void compareForcedPathsOnSameOracleDataAndSaveRawEvidence() throws Exception {
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
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(30000);
        config.setInitializationFailTimeout(5000);
        try (HikariDataSource source = new HikariDataSource(config)) {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            int indexes = jdbc.queryForObject("SELECT COUNT(*) FROM user_indexes WHERE index_name='IR_MOVEMENT_SKU_DATE_IDX'", Integer.class);
            assertEquals(1, indexes, "The prepared Oracle test schema and index are required");
            String dbVersion = jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductVersion());
            long existingRows = jdbc.queryForObject("SELECT COUNT(*) FROM ir_stock_movements", Long.class);

            String runId = "oracle-query-" + UUID.randomUUID().toString();
            String runToken = UUID.randomUUID().toString();
            String warehouse = "q-w-" + runToken;
            List<String> skus = new ArrayList<>();
            for (int i = 0; i < SKUS; i++) skus.add("q-s-" + runToken + "-" + i);
            String targetSku = skus.get(0);
            long seedStarted = System.nanoTime();
            seed(jdbc, tx, warehouse, skus);
            double seedMs = (System.nanoTime() - seedStarted) / 1_000_000.0;

            long fixtureRows = jdbc.queryForObject("SELECT COUNT(*) FROM ir_stock_movements WHERE warehouse_id=?", Long.class, warehouse);
            long totalRows = jdbc.queryForObject("SELECT COUNT(*) FROM ir_stock_movements", Long.class);
            assertEquals(SKUS * ROWS_PER_SKU, fixtureRows);
            assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM ir_stock s WHERE s.warehouse_id=? AND s.quantity <>
                  (SELECT NVL(SUM(m.quantity_delta), 0) FROM ir_stock_movements m
                   WHERE m.warehouse_id=s.warehouse_id AND m.sku=s.sku)
                """, Integer.class, warehouse), "Synthetic ledger must match the synthetic balances");

            Map<String, Object> fullPlan = explain(jdbc, tx, "FULL(m)", warehouse, targetSku, false);
            Map<String, Object> indexPlan = explain(jdbc, tx, "INDEX(m ir_movement_sku_date_idx)", warehouse, targetSku, true);
            Result expected = query(jdbc, FULL, warehouse, targetSku);
            assertExpected(expected);
            assertEquivalent(expected, query(jdbc, INDEX, warehouse, targetSku));
            for (int i = 0; i < WARMUPS_PER_ARM; i++) {
                if ((i & 1) == 0) {
                    assertEquivalent(expected, query(jdbc, FULL, warehouse, targetSku));
                    assertEquivalent(expected, query(jdbc, INDEX, warehouse, targetSku));
                } else {
                    assertEquivalent(expected, query(jdbc, INDEX, warehouse, targetSku));
                    assertEquivalent(expected, query(jdbc, FULL, warehouse, targetSku));
                }
            }

            List<Map<String, Object>> rounds = new ArrayList<>();
            List<Double> allFull = new ArrayList<>(), allIndex = new ArrayList<>();
            for (int round = 0; round < ROUNDS; round++) {
                List<Double> full = new ArrayList<>(), indexed = new ArrayList<>();
                for (int i = 0; i < SAMPLES_PER_ROUND_PER_ARM; i++) {
                    // Alternate first arm per sample and round; both see the same committed fixture.
                    if (((round + i) & 1) == 0) {
                        collect(jdbc, FULL, warehouse, targetSku, expected, full);
                        collect(jdbc, INDEX, warehouse, targetSku, expected, indexed);
                    } else {
                        collect(jdbc, INDEX, warehouse, targetSku, expected, indexed);
                        collect(jdbc, FULL, warehouse, targetSku, expected, full);
                    }
                }
                allFull.addAll(full);
                allIndex.addAll(indexed);
                rounds.add(Map.of("round", round + 1, "full", summary(full), "index", summary(indexed),
                    "full_samples_ms", full, "index_samples_ms", indexed));
            }
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("run_id", runId);
            evidence.put("timestamp_utc", Instant.now().toString());
            evidence.put("scope", "local synthetic Oracle test; forced FULL versus forced INDEX; not production or cross-database improvement");
            evidence.put("limitations", List.of("hints create comparison paths and may not represent the normal optimizer choice",
                "EXPLAIN PLAN is an estimate, not the actual executed cursor", "shared cache state is not controlled",
                "elapsed time includes JDBC and client overhead", "other test-schema rows or concurrent workloads can affect timings",
                "no database statistics are modified", "fixture rows remain in the dedicated schema"));
            evidence.put("environment", Map.of("database_version", dbVersion,
                "java_version", System.getProperty("java.version"), "os", System.getProperty("os.name"),
                "os_version", System.getProperty("os.version"), "arch", System.getProperty("os.arch"),
                "available_processors", Runtime.getRuntime().availableProcessors(),
                "jvm_max_memory_bytes", Runtime.getRuntime().maxMemory()));
            evidence.put("fixture", Map.ofEntries(Map.entry("sku_count", SKUS), Map.entry("rows_per_sku", ROWS_PER_SKU),
                Map.entry("movement_rows", fixtureRows), Map.entry("existing_movement_rows_before", existingRows),
                Map.entry("all_movement_rows_after", totalRows), Map.entry("warehouse", warehouse), Map.entry("selected_sku", targetSku),
                Map.entry("target_sum", expected.amount().toPlainString()), Map.entry("target_row_count", expected.count()),
                Map.entry("generation", "one +1000 OPENING and alternating -1 SHIPMENT/+1 RETURN per SKU"),
                Map.entry("seed_duration_ms", seedMs)));
            evidence.put("workload", Map.of("rounds", ROUNDS, "warmups_per_arm", WARMUPS_PER_ARM,
                "samples_per_round_per_arm", SAMPLES_PER_ROUND_PER_ARM, "query_count_per_arm", 1 + WARMUPS_PER_ARM + ROUNDS * SAMPLES_PER_ROUND_PER_ARM,
                "execution_order", "alternates per sample and round", "percentile_method", "nearest rank",
                "full_sql", FULL, "index_sql", INDEX, "all_measured_results_equal", true));
            evidence.put("plan_explain_only", Map.of("full", fullPlan, "index", indexPlan));
            evidence.put("rounds", rounds);
            double fullP95 = percentile(allFull, .95);
            evidence.put("overall", Map.of("full", summary(allFull), "index", summary(allIndex),
                "p95_relative_change_percent", fullP95 == 0 ? "unavailable: zero baseline" : relativeReduction(fullP95, percentile(allIndex, .95))));
            evidence.put("test_source_sha256", sha256IfPresent(Path.of("src/test/java/com/inventory/oracle/OracleQueryComparisonTest.java")));
            evidence.put("schema_source_sha256", sha256IfPresent(Path.of("src/main/resources/oracle/schema.sql")));

            Path output = Path.of("evidence", runId + ".json");
            Files.createDirectories(output.getParent());
            try (var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(stream, evidence);
            }
            System.out.println("Oracle query evidence: " + output);
        }
    }

    private static void seed(JdbcTemplate jdbc, TransactionTemplate tx, String warehouse, List<String> skus) {
        tx.executeWithoutResult(status -> {
            List<Object[]> stock = new ArrayList<>();
            for (String sku : skus) stock.add(new Object[] {warehouse, sku, EXPECTED_BALANCE});
            jdbc.batchUpdate("INSERT INTO ir_stock (warehouse_id, sku, quantity) VALUES (?,?,?)", stock);
            List<Object[]> batch = new ArrayList<>(1000);
            for (String sku : skus) {
                String documentId = UUID.randomUUID().toString();
                for (int row = 0; row < ROWS_PER_SKU; row++) {
                    String type = row == 0 ? "OPENING" : (row % 2 == 1 ? "SHIPMENT" : "RETURN");
                    int delta = row == 0 ? 1000 : (row % 2 == 1 ? -1 : 1);
                    batch.add(new Object[] {UUID.randomUUID().toString(), type, documentId,
                        UUID.randomUUID().toString(), warehouse, sku, delta});
                    if (batch.size() == 1000) {
                        insertBatch(jdbc, batch);
                        batch.clear();
                    }
                }
            }
            if (!batch.isEmpty()) insertBatch(jdbc, batch);
        });
    }
    private static void insertBatch(JdbcTemplate jdbc, List<Object[]> batch) {
        jdbc.batchUpdate("""
            INSERT INTO ir_stock_movements (id, document_type, document_id, document_line_id, warehouse_id, sku, quantity_delta)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, batch);
    }
    private static Result query(JdbcTemplate jdbc, String sql, String warehouse, String sku) {
        return jdbc.queryForObject(sql, (result, index) -> new Result(result.getBigDecimal(1), result.getLong(2)), warehouse, sku);
    }
    private static void collect(JdbcTemplate jdbc, String sql, String warehouse, String sku, Result expected, List<Double> samples) {
        long started = System.nanoTime();
        Result result = query(jdbc, sql, warehouse, sku);
        double elapsed = (System.nanoTime() - started) / 1_000_000.0;
        assertEquivalent(expected, result);
        samples.add(elapsed);
    }
    private static void assertExpected(Result result) {
        assertNotNull(result);
        assertEquals(0, EXPECTED_BALANCE.compareTo(result.amount()));
        assertEquals(ROWS_PER_SKU, result.count());
    }
    private static void assertEquivalent(Result expected, Result actual) {
        assertNotNull(actual);
        assertEquals(0, expected.amount().compareTo(actual.amount()));
        assertEquals(expected.count(), actual.count());
    }
    private static Map<String, Object> summary(List<Double> samples) {
        return Map.of("sample_count", samples.size(), "p50_ms", percentile(samples, .50), "p95_ms", percentile(samples, .95));
    }
    private static double percentile(List<Double> samples, double p) {
        double[] sorted = samples.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        return sorted[Math.max(0, (int) Math.ceil(p * sorted.length) - 1)];
    }
    private static double relativeReduction(double before, double after) { return 100.0 * (before - after) / before; }

    private static Map<String, Object> explain(JdbcTemplate jdbc, TransactionTemplate tx, String hint,
                                               String warehouse, String sku, boolean expectIndex) {
        String id = "Q" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        // Both predicate literals were generated by this test, not supplied externally.
        String query = "SELECT /*+ " + hint + " */ NVL(SUM(m.quantity_delta), 0), COUNT(*) "
            + "FROM ir_stock_movements m WHERE m.warehouse_id='" + warehouse + "' AND m.sku='" + sku + "'";
        try {
            return tx.execute(status -> {
                try {
                    jdbc.execute("EXPLAIN PLAN SET STATEMENT_ID='" + id + "' FOR " + query);
                    List<String> lines = jdbc.queryForList("SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY(NULL, ?, 'BASIC'))", String.class, id);
                    String joined = String.join("\n", lines).toUpperCase(Locale.ROOT);
                    boolean observed = expectIndex ? joined.contains("IR_MOVEMENT_SKU_DATE_IDX") && joined.contains("INDEX")
                        : joined.contains("TABLE ACCESS FULL") && joined.contains("IR_STOCK_MOVEMENTS");
                    return Map.<String, Object>of("status", observed ? "expected_estimated_path_observed" : "unverified",
                        "source", "EXPLAIN PLAN and DBMS_XPLAN.DISPLAY; estimated only", "lines", lines);
                } finally {
                    // Roll back only this test's explain-plan inserts, without changing any preexisting rows.
                    status.setRollbackOnly();
                }
            });
        } catch (RuntimeException e) {
            return Map.of("status", "unverified", "source", "EXPLAIN PLAN unavailable", "error_class", e.getClass().getSimpleName());
        }
    }
    private static String sha256IfPresent(Path path) throws Exception {
        return Files.isRegularFile(path) ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))) : "unavailable";
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set for Oracle query experiment");
        return value;
    }
}
