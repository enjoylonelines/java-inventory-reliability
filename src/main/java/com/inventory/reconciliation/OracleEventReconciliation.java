package com.inventory.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * One-event comparison against a local mock only. Oracle and the mock are read
 * sequentially, not atomically; every result is an observation, not final proof.
 */
@Service
@Profile("oracle")
@ConditionalOnProperty(name = {"inventory.api.enabled", "inventory.oracle.reconciliation.enabled"}, havingValue = "true")
public class OracleEventReconciliation {
    private static final int MAX_STATE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_EVENT_CHARS = 128 * 1024;
    private static final int MAX_LINES = 1000;
    private final OutboxReader outbox;
    private final StateReader state;
    private final ObjectMapper json;

    record OutboxRow(String status, String payload) {}
    @FunctionalInterface interface OutboxReader { List<OutboxRow> read(String eventId); }
    @FunctionalInterface interface StateReader { byte[] read() throws Exception; }
    record Payload(String id, String documentId, String type, List<SnapshotReconciliation.EventLine> lines) {}
    public record Observation(String eventId, String oracleStatus, long mockApplicationCount,
        Instant oracleObservedAt, Instant mockObservedAt, SnapshotReconciliation.Outcome result, String limitation) {}
    public static class Unavailable extends RuntimeException {}

    @Autowired
    public OracleEventReconciliation(JdbcTemplate jdbc,
        @Value("${inventory.oracle.reconciliation.mock-state-url:}") String stateUrl, ObjectMapper json) {
        URI uri = validateUrl(stateUrl);
        this.outbox = eventId -> jdbc.query("SELECT status, payload FROM ir_outbox WHERE id = ?",
            (rs, row) -> new OutboxRow(rs.getString(1), rs.getString(2)), eventId);
        this.state = () -> fetchState(uri);
        this.json = json;
    }

    OracleEventReconciliation(OutboxReader outbox, StateReader state, ObjectMapper json) {
        this.outbox = outbox; this.state = state; this.json = json;
    }

    public Observation compare(String rawId) {
        String id;
        try {
            id = UUID.fromString(rawId).toString();
            if (!id.equalsIgnoreCase(rawId)) throw new IllegalArgumentException("invalid event ID");
        } catch (RuntimeException e) { throw new IllegalArgumentException("invalid event ID"); }

        List<OutboxRow> rows = outbox.read(id);
        Instant oracleObservedAt = Instant.now();
        if (rows.isEmpty()) throw new NoSuchElementException("event not found");
        if (rows.size() != 1) throw new Unavailable();
        try {
            OutboxRow row = rows.get(0);
            SnapshotReconciliation.Delivery delivery = delivery(row.status());
            Payload expectedPayload = payload(json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(bounded(row.payload())));
            if (!id.equals(expectedPayload.id())) throw new Unavailable();
            byte[] bytes = state.read();
            Instant mockObservedAt = Instant.now();
            if (bytes == null || bytes.length > MAX_STATE_BYTES) throw new Unavailable();
            JsonNode root = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(bytes);
            if (root == null || !root.path("applied").isObject() || !root.path("payloads").isObject()) throw new Unavailable();
            JsonNode countNode = root.path("applied").get(id);
            if (countNode != null && (!countNode.isIntegralNumber() || !countNode.canConvertToLong() || countNode.longValue() < 1)) throw new Unavailable();
            long count = countNode == null ? 0 : countNode.longValue();
            JsonNode remoteNode = root.path("payloads").get(id);
            if ((count == 0 && remoteNode != null) || (count > 0 && (remoteNode == null || !remoteNode.isObject()))) throw new Unavailable();

            List<SnapshotReconciliation.ObservedEvent> observed = new ArrayList<>();
            Payload remote = count > 0 ? payload(remoteNode) : null;
            if (remote != null) {
                // The mock returns one arbitrary payload for duplicate keys. Two
                // observations express only known duplication, not payload equality.
                observed.add(new SnapshotReconciliation.ObservedEvent(remote.id(), remote.lines()));
                if (count > 1) observed.add(new SnapshotReconciliation.ObservedEvent(remote.id(), remote.lines()));
            }
            // This identical marker is a comparison batch label, not a shared source cutoff.
            Instant batch = Instant.now();
            var left = new SnapshotReconciliation.Snapshot<>(batch,
                List.of(new SnapshotReconciliation.ExpectedEvent(id, delivery, expectedPayload.lines())));
            var right = new SnapshotReconciliation.Snapshot<>(batch, observed);
            List<SnapshotReconciliation.Outcome> outcomes = SnapshotReconciliation.external(left, right);
            SnapshotReconciliation.Outcome result = outcomes.stream().filter(o -> o.key().equals(id)).findFirst().orElseThrow(Unavailable::new);
            if (remote != null && (!id.equals(remote.id()) || !expectedPayload.documentId().equals(remote.documentId())
                    || !expectedPayload.type().equals(remote.type()))) {
                boolean inFlight = delivery == SnapshotReconciliation.Delivery.PENDING || delivery == SnapshotReconciliation.Delivery.PROCESSING;
                result = new SnapshotReconciliation.Outcome(id, inFlight ? SnapshotReconciliation.Status.PENDING : SnapshotReconciliation.Status.CONFLICT,
                    "matching event, document and type", "event metadata differs");
            }
            return new Observation(id, row.status(), count, oracleObservedAt, mockObservedAt, result,
                "Oracle and the local mock were read at different times; all outcomes are observations, not a shared atomic snapshot or final reconciliation.");
        } catch (Unavailable e) { throw e; }
        catch (Exception e) { throw new Unavailable(); }
    }

    static URI validateUrl(String raw) {
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost();
            if (!"http".equals(uri.getScheme()) || host == null
                || !(host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"))
                || uri.getPort() < 1 || uri.getPort() > 65535 || !"/state".equals(uri.getPath())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException e) { throw new IllegalArgumentException("mock state URL must be a literal loopback HTTP /state endpoint"); }
    }

    private static byte[] fetchState(URI uri) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        try {
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > MAX_STATE_BYTES) throw new Unavailable();
            try (InputStream in = connection.getInputStream()) {
                byte[] bytes = in.readNBytes(MAX_STATE_BYTES + 1);
                if (bytes.length > MAX_STATE_BYTES) throw new Unavailable();
                return bytes;
            }
        } finally { connection.disconnect(); }
    }
    private Payload payload(JsonNode node) {
        if (node == null || !node.isObject()) throw new Unavailable();
        String id = text(node, "eventId"), documentId = text(node, "documentId"), type = text(node, "type");
        JsonNode lines = node.get("lines");
        if (lines == null || !lines.isArray() || lines.isEmpty() || lines.size() > MAX_LINES) throw new Unavailable();
        List<SnapshotReconciliation.EventLine> parsed = new ArrayList<>();
        for (JsonNode line : lines) {
            JsonNode quantity = line.get("quantityDelta"), amount = line.get("receivableDelta");
            if (quantity == null || !quantity.isNumber() || amount == null || !amount.isNumber()) throw new Unavailable();
            parsed.add(new SnapshotReconciliation.EventLine(text(line, "warehouse"), text(line, "sku"), quantity.decimalValue(), amount.decimalValue()));
        }
        return new Payload(id, documentId, type, List.copyOf(parsed));
    }
    private static String text(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual() || node.textValue().isBlank() || node.textValue().length() > 200) throw new Unavailable();
        return node.textValue();
    }
    private static String bounded(String payload) {
        if (payload == null || payload.length() > MAX_EVENT_CHARS) throw new Unavailable();
        return payload;
    }
    private static SnapshotReconciliation.Delivery delivery(String status) {
        if (status == null) throw new Unavailable();
        return switch (status) {
            case "PENDING" -> SnapshotReconciliation.Delivery.PENDING;
            case "PROCESSING" -> SnapshotReconciliation.Delivery.PROCESSING;
            case "SENT" -> SnapshotReconciliation.Delivery.DONE;
            case "DEAD" -> SnapshotReconciliation.Delivery.DEAD;
            default -> throw new Unavailable();
        };
    }
}
