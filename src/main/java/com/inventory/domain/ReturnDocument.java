package com.inventory.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A return is a separate document and never alters its original shipment. */
public final class ReturnDocument {
    public enum Status { DRAFT, CONFIRMED, POSTED }
    public record Allocation(String shipmentLineId, Quantity quantity, Money amount, ReturnProgress after) {}

    private final String id;
    private final Shipment shipment;
    private final List<ReturnLine> lines;
    private final Status status;
    private final List<Allocation> allocations;

    private ReturnDocument(String id, Shipment shipment, List<ReturnLine> lines, Status status,
                           List<Allocation> allocations) {
        ShipmentLine.requireText(id, "return id");
        this.id = id;
        this.shipment = Objects.requireNonNull(shipment, "shipment");
        if (shipment.status() != Shipment.Status.POSTED) {
            throw new IllegalArgumentException("return requires a posted shipment");
        }
        this.lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (this.lines.isEmpty()) throw new IllegalArgumentException("return must have lines");
        Set<String> sourceIds = new HashSet<>();
        for (ShipmentLine source : shipment.lines()) sourceIds.add(source.id());
        Set<String> returnedIds = new HashSet<>();
        for (ReturnLine line : this.lines) {
            if (!sourceIds.contains(line.shipmentLineId())) throw new IllegalArgumentException("unknown shipment line");
            if (!returnedIds.add(line.shipmentLineId())) throw new IllegalArgumentException("duplicate return line");
        }
        this.status = status;
        this.allocations = List.copyOf(allocations);
    }

    public static ReturnDocument draft(String id, Shipment shipment, List<ReturnLine> lines) {
        return new ReturnDocument(id, shipment, lines, Status.DRAFT, List.of());
    }

    public ReturnDocument confirm() {
        if (status != Status.DRAFT) throw new IllegalStateException("return must be draft");
        return new ReturnDocument(id, shipment, lines, Status.CONFIRMED, List.of());
    }

    /** Each line's progress must be freshly read and protected through the caller's transaction. */
    public ReturnDocument post(Map<String, ReturnProgress> progressByLine) {
        if (status != Status.CONFIRMED) throw new IllegalStateException("return must be confirmed");
        Objects.requireNonNull(progressByLine, "progressByLine");
        Map<String, ShipmentLine> sources = new HashMap<>();
        for (ShipmentLine source : shipment.lines()) sources.put(source.id(), source);
        List<Allocation> result = new ArrayList<>();
        for (ReturnLine line : lines) {
            ShipmentLine source = sources.get(line.shipmentLineId());
            ReturnProgress previous = Objects.requireNonNull(progressByLine.get(line.shipmentLineId()),
                    "progress required for each return line");
            long original = source.quantity().units();
            long alreadyReturned = previous.returnedUnits();
            if (alreadyReturned > original || line.quantity().units() > original - alreadyReturned) {
                throw new IllegalArgumentException("return exceeds shipment quantity");
            }
            Money expectedPrevious = source.amount().allocated(alreadyReturned, original);
            if (!expectedPrevious.equals(previous.reversedAmount())) {
                throw new IllegalArgumentException("return progress amount is inconsistent");
            }
            long totalReturned = alreadyReturned + line.quantity().units();
            Money totalReversed = source.amount().allocated(totalReturned, original);
            Money thisReturn = totalReversed.minus(previous.reversedAmount());
            result.add(new Allocation(line.shipmentLineId(), line.quantity(), thisReturn,
                    new ReturnProgress(totalReturned, totalReversed)));
        }
        return new ReturnDocument(id, shipment, lines, Status.POSTED, result);
    }

    public String id() { return id; }
    public Shipment shipment() { return shipment; }
    public List<ReturnLine> lines() { return lines; }
    public Status status() { return status; }
    public List<Allocation> allocations() { return allocations; }
}
