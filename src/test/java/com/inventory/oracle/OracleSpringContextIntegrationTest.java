package com.inventory.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.inventory.InventoryController;
import com.inventory.InventoryService;
import com.inventory.api.OraclePostingController;
import com.inventory.application.OraclePostingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Verifies profile isolation against an explicitly supplied dedicated Oracle test schema. */
@SpringBootTest
@ActiveProfiles("oracle")
@EnabledIfEnvironmentVariable(named = "ORACLE_INTEGRATION_TEST", matches = "true")
class OracleSpringContextIntegrationTest {
    @Autowired ApplicationContext context;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("ORACLE_TEST_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("ORACLE_TEST_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("ORACLE_TEST_PASSWORD"));
    }

    @Test void oracleProfileDisablesLegacyEndpointsAndOptionalControlsByDefault() {
        assertNotNull(context.getBean(OraclePostingService.class));
        assertTrue(context.getBeansOfType(InventoryService.class).isEmpty());
        assertTrue(context.getBeansOfType(InventoryController.class).isEmpty());
        assertTrue(context.getBeansOfType(OraclePostingController.class).isEmpty());
        assertTrue(context.getBeansOfType(OracleOutboxProcessor.class).isEmpty());
    }
}
