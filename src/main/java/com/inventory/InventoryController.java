package com.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!oracle")
public class InventoryController {
    private final InventoryService service;
    public InventoryController(InventoryService service) { this.service = service; }

    @PostMapping("/api/transactions")
    public ResponseEntity<?> create(@RequestBody JsonNode body,
        @RequestHeader(value="X-Request-Id", required=false) String requestKey,
        @RequestHeader(value="X-Inventory-Mode", defaultValue="outbox") String mode,
        @RequestHeader(value="X-Inventory-Fault", defaultValue="none") String fault,
        @RequestHeader(value="X-Inventory-Canonical-Writer", defaultValue="false") boolean canonicalWriter,
        @RequestHeader(value="X-Inventory-Require-Mapping", defaultValue="false") boolean requireMapping) {
        if (!body.has("items") || !body.get("items").isArray() || body.get("items").isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "제품 항목이 필요합니다.", "code", "BAD_REQUEST", "statusCode", 400));
        if (!mode.equals("baseline") && !mode.equals("outbox")) return ResponseEntity.badRequest().body(Map.of("error", "invalid demo mode"));
        if (!ListOfFaults.valid(fault)) return ResponseEntity.badRequest().body(Map.of("error", "invalid demo fault"));
        String key = requestKey == null || requestKey.isBlank() ? UUID.randomUUID().toString() : requestKey;
        try {
            var created = service.create(body, key, mode, fault, canonicalWriter, requireMapping);
            return ResponseEntity.ok(Map.of("success", true, "transaction_id", created.id().toString()));
        } catch (InventoryService.KeyConflict e) {
            return ResponseEntity.status(409).body(Map.of("error", "request key already used for a different body"));
        } catch (InventoryService.ModeConflict e) {
            return ResponseEntity.status(409).body(Map.of("error", "request key already belongs to another mode; reconcile before switching"));
        } catch (InventoryService.InsufficientStock e) {
            return ResponseEntity.status(409).body(Map.of("error", "insufficient stock"));
        } catch (InventoryService.InvalidStockInput e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid stock input"));
        } catch (InventoryService.InvalidMapping e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "missing, inactive, or unknown mapping"));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "mock ERP delivery failed"));
        }
    }

    @GetMapping("/internal/state")
    public Map<String,Object> state() { return service.state(); }

    @GetMapping("/internal/reconcile")
    public Map<String,Object> reconcile() throws Exception { return service.reconcile(); }

    @PostMapping("/internal/retry-dead")
    public Map<String,Object> retryDead(@RequestBody Map<String,String> body) {
        return Map.of("requeued", service.retryDead(body.get("key")));
    }

    private static class ListOfFaults {
        static boolean valid(String value) { return java.util.Set.of("none", "before-once", "lost-once", "delay-once", "slow-success", "always-fail", "db-rollback", "db-after-writer").contains(value); }
    }
}
