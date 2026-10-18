package com.inventory.report;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/oracle/reports")
@Profile("oracle")
@ConditionalOnProperty(name = "inventory.api.enabled", havingValue = "true")
public class OracleReportController {
    private final OracleReportService service;
    public OracleReportController(OracleReportService service) { this.service = service; }

    @GetMapping("/stock")
    public OracleReportService.Stock stock(@RequestParam String warehouse, @RequestParam String sku) {
        return service.stock(warehouse, sku);
    }

    @GetMapping("/financial")
    public OracleReportService.Financial financial(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return service.financial(from, to);
    }

    @GetMapping("/shipments/{shipmentId}/returns")
    public List<OracleReportService.ReturnedLine> returns(@PathVariable String shipmentId) {
        return service.returns(shipmentId);
    }
}
