package com.inventory.oracle;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** Dedicated-database context and role wiring; these deterministic tokens are test-only. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("oracle")
@EnabledIfEnvironmentVariable(named = "ORACLE_INTEGRATION_TEST", matches = "true")
class OracleSpringApiIntegrationTest {
    private static final String VIEWER = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!";
    private static final String OPERATOR = new StringBuilder(VIEWER).reverse().toString() + "#";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("ORACLE_TEST_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("ORACLE_TEST_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("ORACLE_TEST_PASSWORD"));
        properties.add("inventory.api.enabled", () -> "true");
        properties.add("inventory.api.viewer-token", () -> VIEWER);
        properties.add("inventory.api.operator-token", () -> OPERATOR);
    }

    @Test void reportAndMutationRequireTheConfiguredRoles() throws Exception {
        String report = "/api/oracle/reports/stock?warehouse=missing&sku=missing";
        mvc.perform(get(report)).andExpect(status().isUnauthorized());
        mvc.perform(get(report).header("Authorization", "Bearer " + VIEWER)).andExpect(status().isNotFound());
        String payload = "{\"items\":[{\"warehouse\":\"w\",\"sku\":\"s\",\"quantity\":1,\"unitPrice\":1}]}";
        mvc.perform(post("/api/oracle/shipments").header("Authorization", "Bearer " + VIEWER)
                .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
        mvc.perform(post("/api/oracle/shipments").header("Authorization", "Bearer " + OPERATOR)
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isNotFound());
    }

    @Test void operatorPostsAndViewerReadsARealOracleBalance() throws Exception {
        String id = UUID.randomUUID().toString();
        String warehouse = "api-w-" + id;
        String sku = "api-s-" + id;
        transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO ir_stock(warehouse_id, sku, quantity) VALUES(?,?,?)", warehouse, sku, 5);
            jdbc.update("INSERT INTO ir_stock_movements(id,document_type,document_id,document_line_id,warehouse_id,sku,quantity_delta) VALUES(?, 'OPENING', ?, ?, ?, ?, 5)",
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), warehouse, sku);
        });
        String request = "{\"items\":[{\"warehouse\":\"" + warehouse + "\",\"sku\":\"" + sku + "\",\"quantity\":2,\"unitPrice\":2.50}]}";
        mvc.perform(post("/api/oracle/shipments").header("Authorization", "Bearer " + OPERATOR)
                .header("Idempotency-Key", "api-" + id).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(true));
        mvc.perform(get("/api/oracle/reports/stock").param("warehouse", warehouse).param("sku", sku)
                .header("Authorization", "Bearer " + VIEWER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(3))
                .andExpect(jsonPath("$.ledgerQuantity").value(3)).andExpect(jsonPath("$.difference").value(0));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM ir_action_audit a JOIN ir_shipment_lines l ON l.shipment_id=a.document_id
                WHERE a.actor='operator' AND a.action='POST' AND l.warehouse_id=? AND l.sku=?
                """, Integer.class, warehouse, sku));
    }
}
