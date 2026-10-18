package com.inventory.report;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.application.OraclePostingService;
import com.inventory.oracle.OracleInventoryMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in test for an already prepared, dedicated Oracle schema. No DDL, deletion,
 * or writes to existing stock rows are performed; UUID-scoped rows remain afterward.
 * Financial differences assume no other writer changes the chosen period between reads.
 * This executes real MyBatis SQL but does not verify the HTTP authorization layer.
 */
@EnabledIfEnvironmentVariable(named = "ORACLE_INTEGRATION_TEST", matches = "true")
class OracleReportIntegrationTest {
    private static HikariDataSource dataSource;
    private static TransactionTemplate transaction;
    private static OracleInventoryMapper inventory;
    private static OraclePostingService posting;
    private static OracleReportService reports;

    @BeforeAll
    static void connectToDedicatedSchema() throws Exception {
        String url = requiredEnvironment("ORACLE_TEST_URL");
        String user = requiredEnvironment("ORACLE_TEST_USER");
        String password = requiredEnvironment("ORACLE_TEST_PASSWORD");
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
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Configuration mybatis = new Configuration();
        mybatis.addMapper(OracleInventoryMapper.class);
        mybatis.addMapper(OracleReportMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(mybatis);
        SqlSessionTemplate session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        inventory = session.getMapper(OracleInventoryMapper.class);
        posting = new OraclePostingService(inventory, transaction, new ObjectMapper());
        reports = new OracleReportService(session.getMapper(OracleReportMapper.class));
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void reportReflectsShipmentAndPartialReturnIncludingJournalSigns() {
        String id = UUID.randomUUID().toString();
        String warehouse = "report-w-" + id;
        String sku = "report-s-" + id;
        transaction.executeWithoutResult(status -> {
            assertEquals(1, inventory.insertStock(warehouse, sku));
            assertEquals(1, inventory.incrementStock(warehouse, sku, BigDecimal.TEN));
            assertEquals(1, inventory.insertMovement(uuid(), "OPENING", uuid(), uuid(), warehouse, sku, BigDecimal.TEN));
        });

        // Wide, explicit bounds include these test postings; existing data can be nonempty.
        OffsetDateTime from = OffsetDateTime.parse("1900-01-01T00:00:00Z");
        OffsetDateTime to = OffsetDateTime.parse("2200-01-01T00:00:00Z");
        OracleReportService.Financial before = reports.financial(from, to);
        assertStock(warehouse, sku, "10");

        var shipment = posting.postShipment("report-shipment-" + id,
                new OraclePostingService.ShipmentCommand(List.of(
                        new OraclePostingService.ShipmentItem(warehouse, sku, 3, new BigDecimal("2.50")))));
        assertTrue(shipment.created());
        assertStock(warehouse, sku, "7");
        var shipmentLines = reports.returns(shipment.documentId());
        assertEquals(1, shipmentLines.size());
        OracleReportService.ReturnedLine original = shipmentLines.getFirst();
        assertAmount("3", original.quantity());
        assertAmount("7.50", original.amount());
        assertAmount("0", original.returnedQuantity());
        assertAmount("0", original.ledgerReturnedQuantity());
        OracleReportService.Financial afterShipment = reports.financial(from, to);
        assertFinancialChange(before, afterShipment, "7.50");

        var returned = posting.postReturn("report-return-" + id,
                new OraclePostingService.ReturnCommand(shipment.documentId(),
                        List.of(new OraclePostingService.ReturnItem(original.shipmentLineId(), 1))));
        assertTrue(returned.created());
        assertStock(warehouse, sku, "8");
        OracleReportService.ReturnedLine after = reports.returns(shipment.documentId()).getFirst();
        assertAmount("1", after.returnedQuantity());
        assertAmount("1", after.ledgerReturnedQuantity());
        assertAmount("2.50", after.returnedAmount());
        assertAmount("2.50", after.ledgerReturnedAmount());
        OracleReportService.Financial afterReturn = reports.financial(from, to);
        assertFinancialChange(before, afterReturn, "5.00");
        assertFinancialChange(afterShipment, afterReturn, "-2.50");
    }

    private static void assertStock(String warehouse, String sku, String expected) {
        OracleReportService.Stock stock = reports.stock(warehouse, sku);
        assertAmount(expected, stock.balance());
        assertAmount(expected, stock.ledgerQuantity());
        assertAmount("0", stock.difference());
    }

    private static void assertFinancialChange(OracleReportService.Financial before,
                                              OracleReportService.Financial after, String expected) {
        assertAmount(expected, after.documentNet().subtract(before.documentNet()));
        assertAmount(expected, after.receivableNet().subtract(before.receivableNet()));
        assertAmount(expected, after.revenueNet().subtract(before.revenueNet()));
        assertEquals(before.mismatchedDocuments(), after.mismatchedDocuments());
        assertEquals(before.orphanEntries(), after.orphanEntries());
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
    private static String uuid() { return UUID.randomUUID().toString(); }
    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set for Oracle integration tests");
        return value;
    }
}
