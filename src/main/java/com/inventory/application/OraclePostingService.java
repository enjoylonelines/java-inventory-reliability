package com.inventory.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.domain.Money;
import com.inventory.domain.Quantity;
import com.inventory.domain.ReturnDocument;
import com.inventory.domain.ReturnLine;
import com.inventory.domain.ReturnProgress;
import com.inventory.domain.Shipment;
import com.inventory.domain.ShipmentLine;
import com.inventory.oracle.OracleInventoryMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Posting boundary for the isolated Oracle workflow. No HTTP endpoint is exposed here. */
@Service
@Profile("oracle")
public class OraclePostingService {
    private static final long MAX_UNITS = 999_999_999_999_999_999L;
    private final OracleInventoryMapper db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public record ShipmentItem(String warehouse, String sku, long quantity, BigDecimal unitPrice) {}
    public record ShipmentCommand(List<ShipmentItem> items) {}
    public record ReturnItem(String shipmentLineId, long quantity) {}
    public record ReturnCommand(String shipmentId, List<ReturnItem> items) {}
    public record Result(String documentId, boolean created) {}

    public static class RequestConflict extends RuntimeException {
        public RequestConflict() { super("request key already used for a different command"); }
    }
    public static class StockUnavailable extends RuntimeException {
        public StockUnavailable() { super("insufficient stock or missing balance row"); }
    }

    public OraclePostingService(OracleInventoryMapper db, TransactionTemplate tx, ObjectMapper json) {
        this.db = db;
        this.tx = tx;
        this.json = json;
    }

    public Result postShipment(String requestKey, ShipmentCommand command) {
        return postShipment(requestKey, command, "system");
    }

    public Result postShipment(String requestKey, ShipmentCommand command, String actor) {
        requireKey(requestKey);
        requireActor(actor);
        List<ShipmentItem> items = validateShipment(command);
        String fingerprint = fingerprint("shipment", command);
        return execute(requestKey, fingerprint, "SHIPMENT", () -> {
            String shipmentId = id();
            if (db.insertRequest(requestKey, fingerprint, "SHIPMENT", shipmentId) != 1) throw new IllegalStateException("request reservation failed");
            String orderId = id();
            db.insertOrder(orderId);
            db.insertShipment(shipmentId, orderId);
            List<ShipmentLine> lines = new ArrayList<>();
            for (ShipmentItem item : items) {
                String orderLine = id();
                String shipmentLine = id();
                BigDecimal units = BigDecimal.valueOf(item.quantity());
                Money price = new Money(item.unitPrice());
                Money amount = new Money(price.value().multiply(units));
                db.insertOrderLine(orderLine, orderId, item.warehouse(), item.sku(), units, price.value());
                db.insertShipmentLine(shipmentLine, shipmentId, orderId, orderLine, item.warehouse(), item.sku(), units, price.value(), amount.value());
                lines.add(new ShipmentLine(shipmentLine, item.warehouse(), item.sku(), new Quantity(item.quantity()), amount));
            }
            Shipment posted = Shipment.draft(shipmentId, lines).confirm().post();
            requireOne(db.confirmShipment(shipmentId));
            // All balance locks are acquired in deterministic order, before updating any balance.
            for (ShipmentLine line : posted.lines().stream().sorted(stockOrder()).toList()) {
                if (db.lockStock(line.warehouseId(), line.sku()) == null) throw new StockUnavailable();
            }
            for (ShipmentLine line : posted.lines()) {
                BigDecimal units = BigDecimal.valueOf(line.quantity().units());
                if (db.decrementStock(line.warehouseId(), line.sku(), units) != 1) throw new StockUnavailable();
                db.insertMovement(id(), "SHIPMENT", shipmentId, line.id(), line.warehouseId(), line.sku(), units.negate());
                journal("SHIPMENT", shipmentId, line.id(), line.amount().value());
            }
            requireOne(db.postShipment(shipmentId));
            String eventId = id();
            var eventLines = posted.lines().stream().map(line -> Map.of(
                    "lineId", line.id(), "warehouse", line.warehouseId(), "sku", line.sku(),
                    "quantityDelta", -line.quantity().units(), "receivableDelta", line.amount().value())).toList();
            db.insertOutbox(eventId, "SHIPMENT", shipmentId, event(eventId, "SHIPMENT", shipmentId, eventLines));
            requireOne(db.insertActionAudit(id(), actor, "POST", "SHIPMENT", shipmentId));
            return new Result(shipmentId, true);
        });
    }

    public Result postReturn(String requestKey, ReturnCommand command) {
        return postReturn(requestKey, command, "system");
    }

    public Result postReturn(String requestKey, ReturnCommand command, String actor) {
        requireKey(requestKey);
        requireActor(actor);
        validateReturn(command);
        String fingerprint = fingerprint("return", command);
        return execute(requestKey, fingerprint, "RETURN", () -> {
            String returnId = id();
            if (db.insertRequest(requestKey, fingerprint, "RETURN", returnId) != 1) throw new IllegalStateException("request reservation failed");
            Map<String, Object> header = db.lockShipment(command.shipmentId());
            if (header == null || !"POSTED".equals(value(header, "status"))) throw new IllegalArgumentException("posted shipment required");
            List<ShipmentLine> sources = new ArrayList<>();
            Map<String, ReturnProgress> progress = new HashMap<>();
            for (ReturnItem item : command.items().stream().sorted(Comparator.comparing(ReturnItem::shipmentLineId)).toList()) {
                Map<String, Object> row = db.lockShipmentLine(item.shipmentLineId());
                if (row == null || !command.shipmentId().equals(value(row, "shipment_id"))) throw new IllegalArgumentException("unknown shipment line");
                ShipmentLine source = new ShipmentLine(item.shipmentLineId(), string(row, "warehouse_id"), string(row, "sku"),
                        new Quantity(number(row, "quantity").longValueExact()), new Money(number(row, "amount")));
                sources.add(source);
                progress.put(source.id(), new ReturnProgress(number(row, "returned_quantity").longValueExact(), new Money(number(row, "returned_amount"))));
            }
            Shipment original = Shipment.restore(command.shipmentId(), sources, Shipment.Status.POSTED);
            List<ReturnLine> lines = command.items().stream().map(item -> new ReturnLine(item.shipmentLineId(), new Quantity(item.quantity()))).toList();
            ReturnDocument posted = ReturnDocument.draft(returnId, original, lines).confirm().post(progress);
            for (ShipmentLine source : sources.stream().sorted(stockOrder()).toList()) {
                if (db.lockStock(source.warehouseId(), source.sku()) == null) throw new StockUnavailable();
            }
            db.insertReturn(returnId, command.shipmentId());
            Map<String, ShipmentLine> byId = new HashMap<>();
            sources.forEach(source -> byId.put(source.id(), source));
            List<Map<String, Object>> eventLines = new ArrayList<>();
            for (ReturnDocument.Allocation allocation : posted.allocations()) {
                ShipmentLine source = byId.get(allocation.shipmentLineId());
                String returnLine = id();
                BigDecimal units = BigDecimal.valueOf(allocation.quantity().units());
                requireOne(db.reserveReturn(source.id(), units, allocation.amount().value()));
                db.insertReturnLine(returnLine, returnId, command.shipmentId(), source.id(), units, allocation.amount().value());
                requireOne(db.incrementStock(source.warehouseId(), source.sku(), units));
                db.insertMovement(id(), "RETURN", returnId, returnLine, source.warehouseId(), source.sku(), units);
                journal("RETURN", returnId, returnLine, allocation.amount().value().negate());
                eventLines.add(Map.of("lineId", returnLine, "sourceLineId", source.id(),
                        "warehouse", source.warehouseId(), "sku", source.sku(),
                        "quantityDelta", allocation.quantity().units(), "receivableDelta", allocation.amount().value().negate()));
            }
            requireOne(db.confirmReturn(returnId));
            requireOne(db.postReturn(returnId));
            String eventId = id();
            db.insertOutbox(eventId, "RETURN", returnId, event(eventId, "RETURN", returnId, eventLines));
            requireOne(db.insertActionAudit(id(), actor, "POST", "RETURN", returnId));
            return new Result(returnId, true);
        });
    }

    private Result execute(String key, String fingerprint, String type, Supplier<Result> posting) {
        try {
            return Objects.requireNonNull(tx.execute(status -> {
                Result prior = prior(key, fingerprint, type);
                return prior != null ? prior : posting.get();
            }));
        } catch (DuplicateKeyException duplicate) {
            // Another transaction may win the unique request key after the first lookup.
            Result prior = prior(key, fingerprint, type);
            if (prior != null) return prior;
            throw duplicate;
        }
    }

    private Result prior(String key, String fingerprint, String type) {
        Map<String, Object> existing = db.findRequest(key);
        if (existing == null) return null;
        if (!fingerprint.equals(value(existing, "fingerprint")) || !type.equals(value(existing, "document_type"))) throw new RequestConflict();
        return new Result(string(existing, "document_id"), false);
    }

    private void journal(String type, String document, String line, BigDecimal receivable) {
        db.insertJournal(id(), type, document, line, "RECEIVABLE", receivable);
        db.insertJournal(id(), type, document, line, "REVENUE", receivable.negate());
    }

    private String event(String eventId, String type, String documentId, Object lines) {
        return serialize(Map.of("eventId", eventId, "type", type, "documentId", documentId, "lines", lines));
    }
    private String fingerprint(String scope, Object command) { return RequestFingerprint.sha256(scope, serialize(command)); }
    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("cannot serialize command", e); }
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 200) throw new IllegalArgumentException("request key required, up to 200 characters");
    }
    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank() || actor.length() > 100) throw new IllegalArgumentException("actor required, up to 100 characters");
    }
    private static void requireOne(int count) { if (count != 1) throw new IllegalStateException("unexpected persisted state"); }
    private static Comparator<ShipmentLine> stockOrder() {
        return Comparator.comparing(ShipmentLine::warehouseId).thenComparing(ShipmentLine::sku);
    }
    private static List<ShipmentItem> validateShipment(ShipmentCommand command) {
        if (command == null || command.items() == null || command.items().isEmpty() || command.items().size() > 1000) throw new IllegalArgumentException("1 to 1000 items required");
        Set<List<String>> keys = new HashSet<>();
        for (ShipmentItem item : command.items()) {
            if (item == null || item.warehouse() == null || item.warehouse().isBlank() || item.warehouse().length() > 80 || item.sku() == null || item.sku().isBlank() || item.sku().length() > 80 || item.quantity() <= 0 || item.quantity() > MAX_UNITS || item.unitPrice() == null) throw new IllegalArgumentException("invalid shipment item");
            if (!keys.add(List.of(item.warehouse(), item.sku()))) throw new IllegalArgumentException("duplicate warehouse and sku");
            Money price = new Money(item.unitPrice());
            if (price.value().precision() > 18 || price.value().multiply(BigDecimal.valueOf(item.quantity())).precision() > 18) throw new IllegalArgumentException("amount exceeds database precision");
        }
        return List.copyOf(command.items());
    }
    private static void validateReturn(ReturnCommand command) {
        if (command == null || command.shipmentId() == null || command.shipmentId().isBlank() || command.shipmentId().length() > 36 || command.items() == null || command.items().isEmpty() || command.items().size() > 1000) throw new IllegalArgumentException("return shipment and 1 to 1000 items required");
        Set<String> lines = new HashSet<>();
        for (ReturnItem item : command.items()) {
            if (item == null || item.shipmentLineId() == null || item.shipmentLineId().isBlank() || item.shipmentLineId().length() > 36 || item.quantity() <= 0 || item.quantity() > MAX_UNITS || !lines.add(item.shipmentLineId())) throw new IllegalArgumentException("invalid return item");
        }
    }
    private static Object value(Map<String, Object> row, String key) {
        return row.containsKey(key) ? row.get(key) : row.get(key.toUpperCase(java.util.Locale.ROOT));
    }
    private static String string(Map<String, Object> row, String key) { return Objects.requireNonNull(value(row, key), key).toString(); }
    private static BigDecimal number(Map<String, Object> row, String key) { return new BigDecimal(string(row, key)); }
}
