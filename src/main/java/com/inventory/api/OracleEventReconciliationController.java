package com.inventory.api;

import com.inventory.reconciliation.OracleEventReconciliation;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Protected by the same opt-in /api/oracle/** development token guard as reports. */
@RestController
@Profile("oracle")
@ConditionalOnProperty(name = {"inventory.api.enabled", "inventory.oracle.reconciliation.enabled"}, havingValue = "true")
public class OracleEventReconciliationController {
    private final OracleEventReconciliation service;
    public OracleEventReconciliationController(OracleEventReconciliation service) { this.service = service; }

    @GetMapping("/api/oracle/reconciliation/outbox/{eventId}")
    public OracleEventReconciliation.Observation compare(@PathVariable String eventId) { return service.compare(eventId); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest() { return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST")); }
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound() { return ResponseEntity.status(404).body(Map.of("code", "NOT_FOUND")); }
    @ExceptionHandler(OracleEventReconciliation.Unavailable.class)
    ResponseEntity<Map<String, String>> unavailable() { return ResponseEntity.status(502).body(Map.of("code", "RECONCILIATION_UNAVAILABLE")); }
}
