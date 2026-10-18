package com.inventory.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.inventory.oracle.OracleOutboxProcessor;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OracleOutboxControllerTest {
    private static final String VIEWER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + "abcdefghijklmnopqrstuvwxyz" + "0123456789!";
    private static final String OPERATOR = new StringBuilder(VIEWER).reverse().toString() + "#";

    @Test
    void onlyOperatorCanRequeueAndTheActorIsARole() throws Exception {
        OracleOutboxProcessor processor = mock(OracleOutboxProcessor.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OracleOutboxController(processor))
                .addInterceptors(new OracleTokenGuard(VIEWER, OPERATOR)).build();
        String event = UUID.randomUUID().toString();
        String path = "/api/oracle/outbox/" + event + "/requeue";
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", "Bearer " + VIEWER)).andExpect(status().isForbidden());
        verifyNoInteractions(processor);
        when(processor.requeueDead(event, "operator")).thenReturn(true);
        mvc.perform(post(path).header("Authorization", "Bearer " + OPERATOR))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requeued").value(true));
        verify(processor).requeueDead(event, "operator");
    }

    @Test
    void invalidIdIsBadRequestAndNonDeadOrMissingEventIsConflict() throws Exception {
        OracleOutboxProcessor processor = mock(OracleOutboxProcessor.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OracleOutboxController(processor))
                .addInterceptors(new OracleTokenGuard(VIEWER, OPERATOR)).build();
        mvc.perform(post("/api/oracle/outbox/not-a-uuid/requeue").header("Authorization", "Bearer " + OPERATOR))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(post("/api/oracle/outbox/1-1-1-1-1/requeue").header("Authorization", "Bearer " + OPERATOR))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(processor);
        String event = UUID.randomUUID().toString();
        when(processor.requeueDead(event, "operator")).thenReturn(false);
        mvc.perform(post("/api/oracle/outbox/" + event + "/requeue").header("Authorization", "Bearer " + OPERATOR))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void activationRequiresOracleProfileAndBothFlags() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(OracleOutboxController.class)
                // Register the mock instance directly: the processor class itself has
                // profile/property conditions, which do not belong to this controller test.
                .withInitializer(context -> context.getBeanFactory().registerSingleton(
                        "outboxProcessorTestDouble", mock(OracleOutboxProcessor.class)));
        runner.withPropertyValues("inventory.api.enabled=true", "inventory.oracle.outbox.enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("oracleOutboxController"));
                });
        ApplicationContextRunner oracle = runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("oracle"));
        oracle.run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("oracleOutboxController"));
        });
        oracle.withPropertyValues("inventory.api.enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("oracleOutboxController"));
                });
        oracle.withPropertyValues("inventory.oracle.outbox.enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("oracleOutboxController"));
                });
        oracle.withPropertyValues("inventory.api.enabled=true", "inventory.oracle.outbox.enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("oracleOutboxController"));
                });
    }
}
