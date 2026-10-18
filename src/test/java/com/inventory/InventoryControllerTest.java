package com.inventory;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InventoryControllerTest {
    private final InventoryService service = mock(InventoryService.class);
    private MockMvc mvc;
    private static final String VALID = "{\"type\":\"out\",\"items\":[{\"option_id\":\"sku\",\"quantity\":1}]}";

    @BeforeEach void setup() { mvc = MockMvcBuilders.standaloneSetup(new InventoryController(service)).build(); }

    @Test void emptyItemsMatchLegacyErrorShape() throws Exception {
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.error").value("제품 항목이 필요합니다."));
        verifyNoInteractions(service);
    }

    @Test void successKeepsLegacyResponseShape() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any(), eq("request-1"), eq("outbox"), eq("none"), eq(false), eq(false))).thenReturn(new InventoryService.Created(id, true));
        mvc.perform(post("/api/transactions").header("X-Request-Id", "request-1").contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.transaction_id").value(id.toString()));
    }

    @Test void sameKeyDifferentPayloadFailsClosed() throws Exception {
        when(service.create(any(), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean())).thenThrow(new InventoryService.KeyConflict());
        mvc.perform(post("/api/transactions").header("X-Request-Id", "request-1").contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andExpect(status().isConflict());
    }

    @Test void crossModeReplayFailsClosed() throws Exception {
        when(service.create(any(), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean())).thenThrow(new InventoryService.ModeConflict());
        mvc.perform(post("/api/transactions").header("X-Request-Id", "request-1").header("X-Inventory-Mode", "baseline")
                .contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isConflict());
    }

    @Test void unknownFaultIsRejected() throws Exception {
        mvc.perform(post("/api/transactions").header("X-Inventory-Fault", "unexpected")
                .contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void invalidMappingFailsBeforeDelivery() throws Exception {
        when(service.create(any(), anyString(), anyString(), anyString(), anyBoolean(), eq(true))).thenThrow(new InventoryService.InvalidMapping());
        mvc.perform(post("/api/transactions").header("X-Request-Id", "request-1").header("X-Inventory-Require-Mapping", "true")
                .contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("missing, inactive, or unknown mapping"));
    }
}
