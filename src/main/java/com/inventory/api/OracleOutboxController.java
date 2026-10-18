package com.inventory.api;

import com.inventory.oracle.OracleOutboxProcessor;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** The recorded actor is a shared development role, not an authenticated person. */
@RestController
@Profile("oracle")
@ConditionalOnProperty(name = {"inventory.api.enabled", "inventory.oracle.outbox.enabled"}, havingValue = "true")
public class OracleOutboxController {
    private final OracleOutboxProcessor processor;
    public OracleOutboxController(OracleOutboxProcessor processor) { this.processor = processor; }

    @PostMapping("/api/oracle/outbox/{eventId}/requeue")
    public ResponseEntity<Map<String, ?>> requeue(@PathVariable String eventId) {
        final String canonical;
        try {
            canonical = UUID.fromString(eventId).toString();
            if (!canonical.equalsIgnoreCase(eventId)) throw new IllegalArgumentException("invalid id");
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST"));
        }
        if (!processor.requeueDead(canonical, "operator")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "CONFLICT"));
        }
        return ResponseEntity.ok(Map.of("requeued", true));
    }
}
