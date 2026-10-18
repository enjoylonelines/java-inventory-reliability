package com.inventory.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.inventory.application.OraclePostingService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OracleApiTest {
    // Deterministic values used only by unit tests; they are not deployment credentials.
    private static final String VIEWER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + "abcdefghijklmnopqrstuvwxyz" + "0123456789!";
    private static final String OPERATOR = new StringBuilder(VIEWER).reverse().toString() + "#";
    private static final String BODY = "{\"items\":[{\"warehouse\":\"W\",\"sku\":\"S\",\"quantity\":1,\"unitPrice\":1.00}]}";
    private OraclePostingService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(OraclePostingService.class);
        mvc = MockMvcBuilders.standaloneSetup(new OraclePostingController(service))
                .addInterceptors(new OracleTokenGuard(VIEWER, OPERATOR))
                .setControllerAdvice(new OracleApiErrors()).build();
    }

    @Test
    void operatorCanPostWithAnExplicitKey() throws Exception {
        when(service.postShipment(eq("request-1"), any(), eq("operator"))).thenReturn(new OraclePostingService.Result("document-1", true));
        mvc.perform(post("/api/oracle/shipments").header("Authorization", "Bearer " + OPERATOR)
                .header("Idempotency-Key", "request-1").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documentId").value("document-1"));
        verify(service).postShipment(eq("request-1"), any(), eq("operator"));
    }

    @Test
    void operatorCanSubmitReturnWithItsOwnRequestKey() throws Exception {
        when(service.postReturn(eq("return-key"), any(), eq("operator"))).thenReturn(new OraclePostingService.Result("return-1", true));
        mvc.perform(post("/api/oracle/returns").header("Authorization", "Bearer " + OPERATOR)
                .header("Idempotency-Key", "return-key").contentType(MediaType.APPLICATION_JSON)
                .content("{\"shipmentId\":\"shipment-1\",\"items\":[{\"shipmentLineId\":\"line-1\",\"quantity\":1}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documentId").value("return-1"));
        verify(service).postReturn(eq("return-key"), any(), eq("operator"));
    }

    @Test
    void rejectsUnauthenticatedViewerWritesAndMissingKey() throws Exception {
        mvc.perform(post("/api/oracle/shipments").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/oracle/shipments").header("Authorization", "Bearer invalid")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/oracle/shipments").header("Authorization", "Bearer " + VIEWER)
                .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/oracle/shipments").header("Authorization", "Bearer " + OPERATOR)
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMultipleCredentialsAndDoesNotTrustRoleHeaders() throws Exception {
        mvc.perform(post("/api/oracle/shipments").header("X-Role", "operator")
                .header("Authorization", "Bearer " + VIEWER, "Bearer " + OPERATOR)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/oracle/shipments").header("X-Role", "operator")
                .header("Authorization", "Bearer " + VIEWER).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void malformedInputAndBusinessConflictsHaveConsistentCodes() throws Exception {
        mvc.perform(post("/api/oracle/shipments").header("Authorization", "Bearer " + OPERATOR)
                .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        when(service.postShipment(eq("key"), any(), eq("operator"))).thenThrow(new OraclePostingService.RequestConflict());
        mvc.perform(post("/api/oracle/shipments").header("Authorization", "Bearer " + OPERATOR)
                .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void bothRolesMayReadInTheGuardButOnlyOperatorMayWrite() throws Exception {
        OracleTokenGuard guard = new OracleTokenGuard(VIEWER, OPERATOR);
        assertTrue(allowed(guard, "GET", VIEWER));
        assertTrue(allowed(guard, "GET", OPERATOR));
        assertFalse(allowed(guard, "POST", VIEWER));
        assertTrue(allowed(guard, "POST", OPERATOR));
    }

    private boolean allowed(OracleTokenGuard guard, String method, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/oracle/some-future-report");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = guard.preHandle(request, response, new Object());
        if (!allowed) assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        return allowed;
    }

    @Test
    void configuredApiFailsClosedForMissingEqualOrWeakCredentials() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("oracle"))
                .withUserConfiguration(OracleApiConfiguration.class)
                .withPropertyValues("inventory.api.enabled=true");
        runner.run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("inventory.api.viewer-token=" + VIEWER, "inventory.api.operator-token=" + VIEWER)
                .run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("inventory.api.viewer-token=weak", "inventory.api.operator-token=" + OPERATOR)
                .run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("inventory.api.viewer-token=" + VIEWER, "inventory.api.operator-token=" + OPERATOR)
                .run(context -> assertNull(context.getStartupFailure()));
    }

    @Test
    void configurationIsDisabledByDefaultAndOutsideOracleProfile() {
        ApplicationContextRunner disabled = new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("oracle"))
                .withUserConfiguration(OracleApiConfiguration.class);
        disabled.run(context -> assertFalse(context.containsBean("oracleApiConfiguration")));
        new ApplicationContextRunner().withUserConfiguration(OracleApiConfiguration.class)
                .withPropertyValues("inventory.api.enabled=true")
                .run(context -> assertFalse(context.containsBean("oracleApiConfiguration")));
    }
}
