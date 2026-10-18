package com.inventory.report;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OracleReportControllerTest {
    @Test
    void stockAndReturnEndpointsDelegateAndBadPeriodsAreRejected() throws Exception {
        OracleReportService service = mock(OracleReportService.class);
        when(service.stock("W", "S")).thenReturn(new OracleReportService.Stock("W", "S",
                new BigDecimal("8"), new BigDecimal("9"), new BigDecimal("-1")));
        when(service.returns("shipment-1")).thenReturn(List.of());
        OffsetDateTime start = OffsetDateTime.parse("2026-10-01T00:00:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-11-01T00:00:00Z");
        when(service.financial(start, end)).thenReturn(new OracleReportService.Financial(start, end,
                new BigDecimal("75.00"), new BigDecimal("75.00"), new BigDecimal("75.00"), 0, 0));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OracleReportController(service))
                .setControllerAdvice(new OracleReportErrors()).build();
        mvc.perform(get("/api/oracle/reports/stock").param("warehouse", "W").param("sku", "S"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.difference").value(-1));
        mvc.perform(get("/api/oracle/reports/shipments/shipment-1/returns"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/oracle/reports/financial").param("from", start.toString()).param("to", end.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documentNet").value(75.00));
        mvc.perform(get("/api/oracle/reports/financial").param("from", "not-a-date").param("to", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/api/oracle/reports/stock").param("warehouse", "W"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
