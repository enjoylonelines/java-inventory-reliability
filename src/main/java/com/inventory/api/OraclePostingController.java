package com.inventory.api;

import com.inventory.application.OraclePostingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/oracle")
@Profile("oracle")
@ConditionalOnProperty(name = "inventory.api.enabled", havingValue = "true")
public class OraclePostingController {
    private final OraclePostingService service;
    public OraclePostingController(OraclePostingService service) { this.service = service; }

    @PostMapping("/shipments")
    public ResponseEntity<OraclePostingService.Result> shipment(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody OraclePostingService.ShipmentCommand command) {
        requireKey(key);
        return ResponseEntity.ok(service.postShipment(key, command, "operator"));
    }

    @PostMapping("/returns")
    public ResponseEntity<OraclePostingService.Result> returnShipment(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody OraclePostingService.ReturnCommand command) {
        requireKey(key);
        return ResponseEntity.ok(service.postReturn(key, command, "operator"));
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 200) throw new IllegalArgumentException("invalid request key");
    }
}
