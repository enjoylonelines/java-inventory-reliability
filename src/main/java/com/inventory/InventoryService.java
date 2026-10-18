package com.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InventoryService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(InventoryService.class);
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final OutboxMapper mapper;
    private final ObjectMapper json;
    private final String erpUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public InventoryService(JdbcTemplate db, TransactionTemplate tx, OutboxMapper mapper, ObjectMapper json,
                      @Value("${demo.erp.url}") String erpUrl) {
        this.db = db; this.tx = tx; this.mapper = mapper; this.json = json; this.erpUrl = erpUrl;
    }

    record Created(UUID id, boolean created) {}

    public Created create(JsonNode body, String key, String mode, String fault, boolean canonicalWriter, boolean requireMapping) throws Exception {
        String serialized = json.writeValueAsString(body);
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((canonicalWriter + "|" + requireMapping + "|" + serialized).getBytes(StandardCharsets.UTF_8)));
        UUID id = UUID.randomUUID();
        Created result = tx.execute(status -> {
            db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, key);
            Integer crossMode = db.queryForObject("SELECT count(*) FROM inv_transactions WHERE request_key=? AND mode<>?", Integer.class, key, mode);
            if (crossMode != null && crossMode > 0) throw new ModeConflict();
            int count = db.update("INSERT INTO inv_transactions(id,request_key,mode,fingerprint,body) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING", id, key, mode, fingerprint, serialized);
            if (count == 0) {
                Map<String,Object> existing = db.queryForMap("SELECT id,fingerprint FROM inv_transactions WHERE request_key=? AND mode='outbox'", key);
                if (!fingerprint.equals(existing.get("fingerprint"))) throw new KeyConflict();
                return new Created((UUID) existing.get("id"), false);
            }
            if (requireMapping) validateMappings(body);
            if (fault.equals("db-rollback")) throw new IllegalStateException("injected transaction failure before outbox insert");
            UUID transactionId = id;
            if (canonicalWriter) {
                if (mode.equals("outbox")) guardStock(body);
                transactionId = callCanonicalWriter(body);
                db.update("UPDATE inv_transactions SET id=? WHERE id=?", transactionId, id);
            }
            if (fault.equals("db-after-writer")) throw new IllegalStateException("injected transaction failure after writer before outbox insert");
            if (mode.equals("outbox")) {
                UUID event = UUID.randomUUID();
                mapper.insert(event, transactionId, key, fault);
                db.update("INSERT INTO inv_audit(event_id,transition) VALUES (?,?)", event, "CREATED");
            }
            return new Created(transactionId, true);
        });
        if (mode.equals("baseline")) send(key, fault, false, body);
        return result;
    }

    static class KeyConflict extends RuntimeException {}
    static class ModeConflict extends RuntimeException {}
    static class InsufficientStock extends RuntimeException {}
    static class InvalidStockInput extends RuntimeException {}
    static class InvalidMapping extends RuntimeException {}

    // A synthetic local catalogue snapshot, not a claim about any actual ERP catalogue.
    private void validateMappings(JsonNode body) {
        checkMapping("partner", body.path("supplier_id").asText());
        for (JsonNode item : body.path("items")) checkMapping("sku", item.path("option_id").asText());
    }

    private void checkMapping(String kind, String localId) {
        Integer valid = db.queryForObject("""
            SELECT count(*) FROM inv_mappings m JOIN inv_erp_catalog c
            ON c.kind=m.kind AND c.external_id=m.external_id AND c.active
            WHERE m.kind=? AND m.local_id=? AND m.active
            """, Integer.class, kind, localId);
        if (valid == null || valid != 1) throw new InvalidMapping();
    }

    // Only protects participants that acquire these same keys. The legacy RPC itself is unchanged.
    private void guardStock(JsonNode body) {
        String type = body.path("type").asText().toLowerCase(java.util.Locale.ROOT);
        if (!type.equals("in") && !type.equals("out")) throw new InvalidStockInput();
        UUID warehouse;
        try { warehouse = UUID.fromString(body.path("warehouse_id").asText()); }
        catch (IllegalArgumentException e) { throw new InvalidStockInput(); }
        Map<UUID,java.math.BigDecimal> quantities = new java.util.TreeMap<>();
        Map<UUID,String> types = new java.util.HashMap<>();
        for (JsonNode item : body.path("items")) {
            UUID option;
            try { option = UUID.fromString(item.path("option_id").asText()); }
            catch (IllegalArgumentException e) { throw new InvalidStockInput(); }
            if (!item.path("quantity").isNumber() || item.path("quantity").decimalValue().signum() <= 0) throw new InvalidStockInput();
            String productType = item.hasNonNull("product_type") ? item.get("product_type").asText() : "company";
            if (!productType.equals("company") && !productType.equals("material")) throw new InvalidStockInput();
            if (productType.equals("material") && item.path("quantity").decimalValue().stripTrailingZeros().scale() > 0) throw new InvalidStockInput();
            String previous = types.putIfAbsent(option, productType);
            if (previous != null && !previous.equals(productType)) throw new InvalidStockInput();
            quantities.merge(option, item.path("quantity").decimalValue(), java.math.BigDecimal::add);
        }
        for (UUID option : quantities.keySet())
            db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, "canonical-stock:" + warehouse + ":" + option);
        if (type.equals("out")) {
            for (var entry : quantities.entrySet()) {
                List<Map<String,Object>> inventory = db.queryForList("SELECT quantity,weight_in_grams FROM public.inventory WHERE warehouse_id=? AND product_option_id=? FOR UPDATE", warehouse, entry.getKey());
                if (inventory.size() > 1) throw new InvalidStockInput();
                Object raw = inventory.isEmpty() ? null : inventory.get(0).get(types.get(entry.getKey()).equals("material") ? "weight_in_grams" : "quantity");
                java.math.BigDecimal available = raw == null ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(raw.toString());
                if (available.compareTo(entry.getValue()) < 0) throw new InsufficientStock();
            }
        }
    }

    private UUID callCanonicalWriter(JsonNode body) {
        try {
            var header = json.createObjectNode();
            header.put("type", body.path("type").asText().toLowerCase(java.util.Locale.ROOT));
            for (String field : List.of("user_id", "warehouse_id", "supplier_id")) {
                if (body.hasNonNull(field)) header.set(field, body.get(field)); else header.putNull(field);
            }
            header.put("memo", body.path("memo").asText(""));
            header.put("created_at", body.hasNonNull("created_at") ? body.get("created_at").asText() : java.time.Instant.now().toString());
            var items = json.createArrayNode();
            for (JsonNode item : body.path("items")) {
                var normalized = items.addObject();
                normalized.set("option_id", item.path("option_id"));
                normalized.set("quantity", item.path("quantity"));
                normalized.put("product_type", item.hasNonNull("product_type") ? item.get("product_type").asText() : "company");
            }
            String result = db.queryForObject("SELECT public.create_transaction(CAST(? AS jsonb), CAST(? AS jsonb))::text",
                String.class, json.writeValueAsString(header), json.writeValueAsString(items));
            return UUID.fromString(json.readTree(result).get("transaction_id").asText());
        } catch (Exception e) {
            throw new IllegalStateException("canonical writer failed", e);
        }
    }

    private void send(String key, String fault, boolean idempotent, JsonNode body) throws Exception {
        String payload = json.writeValueAsString(Map.of("key", key, "fault", fault, "idempotent", idempotent, "payload", body));
        HttpRequest request = HttpRequest.newBuilder(URI.create(erpUrl + "/apply"))
            .timeout(Duration.ofSeconds(2)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("ERP status " + response.statusCode());
    }

    @Scheduled(fixedDelayString = "${demo.poll-ms:20}")
    public void drain() {
        UUID token = UUID.randomUUID();
        List<Map<String,Object>> rows = tx.execute(status -> {
            List<Map<String,Object>> claimed = db.queryForList("""
            UPDATE inv_outbox SET status='PROCESSING', lease_token=?, lease_until=now()+interval '5 seconds', attempts=attempts+1
            WHERE id=(SELECT id FROM inv_outbox WHERE (status='PENDING' AND next_attempt_at<=now()) OR
                (status='PROCESSING' AND lease_until<now()) ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1)
            RETURNING id,transaction_id,request_key,fault,attempts
            """, token);
            if (!claimed.isEmpty()) db.update("INSERT INTO inv_audit(event_id,transition,detail) VALUES (?,?,?)", claimed.get(0).get("id"), "PROCESSING", "attempt=" + claimed.get(0).get("attempts"));
            return claimed;
        });
        if (rows == null || rows.isEmpty()) return;
        Map<String,Object> row = rows.get(0);
        UUID id = (UUID) row.get("id");
        LOG.atInfo().addKeyValue("event_id", id).addKeyValue("attempt", row.get("attempts")).log("outbox claimed");
        try {
            String snapshot = db.queryForObject("SELECT body FROM inv_transactions WHERE id=?", String.class, row.get("transaction_id"));
            send((String)row.get("request_key"), (String)row.get("fault"), true, json.readTree(snapshot));
            Integer updated = tx.execute(status -> {
                int changed = db.update("UPDATE inv_outbox SET status='DONE',completed_at=now(),lease_until=NULL WHERE id=? AND lease_token=?", id, token);
                if (changed == 1) db.update("INSERT INTO inv_audit(event_id,transition) VALUES (?,?)", id, "DONE");
                return changed;
            });
            if (updated != null && updated == 1) LOG.atInfo().addKeyValue("event_id", id).addKeyValue("state", "DONE").log("outbox transition");
        } catch (Exception e) {
            String state = ((Number)row.get("attempts")).intValue() >= 5 ? "DEAD" : "PENDING";
            Integer updated = tx.execute(status -> {
                int changed = db.update("UPDATE inv_outbox SET status=?,next_attempt_at=now()+(interval '100 milliseconds' * power(2, LEAST(attempts-1, 4))),lease_until=NULL,last_error=? WHERE id=? AND lease_token=?", state, e.getClass().getSimpleName(), id, token);
                if (changed == 1) db.update("INSERT INTO inv_audit(event_id,transition,detail) VALUES (?,?,?)", id, state, e.getClass().getSimpleName());
                return changed;
            });
            if (updated != null && updated == 1) LOG.atWarn().addKeyValue("event_id", id).addKeyValue("state", state).addKeyValue("error_class", e.getClass().getSimpleName()).log("outbox transition");
        }
    }

    public Map<String,Object> state() {
        return Map.of("transactions", db.queryForList("SELECT id,request_key,mode,created_at FROM inv_transactions ORDER BY created_at,id"),
                      "outbox", db.queryForList("SELECT id,request_key,status,attempts,created_at,completed_at FROM inv_outbox ORDER BY created_at,id"),
                      "audit", db.queryForList("SELECT event_id,transition,detail,created_at FROM inv_audit ORDER BY id"));
    }

    public Map<String,Object> reconcile() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(erpUrl + "/state"))
            .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("mock ERP unavailable");
        JsonNode external = json.readTree(response.body()).get("applied");
        List<Map<String,Object>> local = db.queryForList("SELECT request_key, count(*) AS local_count FROM inv_transactions GROUP BY request_key ORDER BY request_key");
        List<Map<String,Object>> mismatches = new java.util.ArrayList<>();
        for (Map<String,Object> row : local) {
            String key = (String) row.get("request_key");
            int localCount = ((Number)row.get("local_count")).intValue();
            int remoteCount = external.path(key).asInt(0);
            if (localCount != remoteCount || localCount != 1 || remoteCount != 1)
                mismatches.add(Map.of("request_key", key, "local_count", localCount, "external_count", remoteCount));
        }
        java.util.Set<String> known = new java.util.HashSet<>();
        for (Map<String,Object> row : local) known.add((String)row.get("request_key"));
        external.fieldNames().forEachRemaining(key -> { if (!known.contains(key)) mismatches.add(Map.of("request_key", key, "local_count", 0, "external_count", external.path(key).asInt())); });
        return Map.of("compared_local_keys", local.size(), "mismatch_count", mismatches.size(), "mismatches", mismatches);
    }

    public int retryDead(String key) {
        return tx.execute(status -> {
            List<UUID> ids = db.queryForList("UPDATE inv_outbox SET status='PENDING', attempts=0, fault='none', next_attempt_at=now() WHERE request_key=? AND status='DEAD' RETURNING id", UUID.class, key);
            for (UUID id : ids) db.update("INSERT INTO inv_audit(event_id,transition) VALUES (?,?)", id, "MANUAL_RETRY");
            return ids.size();
        });
    }
}
