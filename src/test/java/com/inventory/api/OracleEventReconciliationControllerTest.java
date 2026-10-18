package com.inventory.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.inventory.reconciliation.OracleEventReconciliation;
import com.inventory.reconciliation.SnapshotReconciliation;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OracleEventReconciliationControllerTest {
    private static final String VIEWER = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!";
    private static final String OPERATOR = new StringBuilder(VIEWER).reverse().toString() + "#";

    @Test void endpointRequiresTokenAndAllowsTheViewerRole() throws Exception {
        var service = mock(OracleEventReconciliation.class);
        var mvc = MockMvcBuilders.standaloneSetup(new OracleEventReconciliationController(service))
            .addInterceptors(new OracleTokenGuard(VIEWER, OPERATOR)).build();
        String id = UUID.randomUUID().toString();
        String path = "/api/oracle/reconciliation/outbox/" + id;
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
        when(service.compare(id)).thenReturn(new OracleEventReconciliation.Observation(id, "SENT", 1,
            Instant.now(), Instant.now(), new SnapshotReconciliation.Outcome(id, SnapshotReconciliation.Status.MATCH, "expected", "actual"), "not atomic"));
        mvc.perform(get(path).header("Authorization", "Bearer " + VIEWER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.result.status").value("MATCH"));
    }

    @Test void controllerIsPresentOnlyWithBothExplicitFlagsAndOracleProfile() {
        ApplicationContextRunner base = new ApplicationContextRunner()
            .withUserConfiguration(OracleEventReconciliationController.class)
            // Register the dependency directly: its production type is itself conditional,
            // whereas this test is checking the controller's conditions independently.
            .withInitializer(context -> context.getBeanFactory().registerSingleton(
                "reconciliationTestDependency", mock(OracleEventReconciliation.class)));
        base.withPropertyValues("inventory.api.enabled=true", "inventory.oracle.reconciliation.enabled=true")
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertFalse(context.containsBean("oracleEventReconciliationController"));
            });
        var oracle = base.withInitializer(context -> context.getEnvironment().setActiveProfiles("oracle"));
        oracle.withPropertyValues("inventory.api.enabled=true")
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertFalse(context.containsBean("oracleEventReconciliationController"));
            });
        oracle.withPropertyValues("inventory.oracle.reconciliation.enabled=true")
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertFalse(context.containsBean("oracleEventReconciliationController"));
            });
        oracle.withPropertyValues("inventory.api.enabled=true", "inventory.oracle.reconciliation.enabled=true")
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertTrue(context.containsBean("oracleEventReconciliationController"));
            });
    }
}
