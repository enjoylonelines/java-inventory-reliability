package com.inventory.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable document; persistence and transaction coordination belong to the application layer. */
public final class Shipment {
    public enum Status { DRAFT, CONFIRMED, POSTED }

    private final String id;
    private final List<ShipmentLine> lines;
    private final Status status;

    private Shipment(String id, List<ShipmentLine> lines, Status status) {
        ShipmentLine.requireText(id, "shipment id");
        this.id = id;
        this.lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (this.lines.isEmpty()) throw new IllegalArgumentException("shipment must have lines");
        Set<String> ids = new HashSet<>();
        for (ShipmentLine line : this.lines) {
            if (!ids.add(line.id())) throw new IllegalArgumentException("duplicate shipment line id");
        }
        this.status = Objects.requireNonNull(status, "status");
    }

    public static Shipment draft(String id, List<ShipmentLine> lines) {
        return new Shipment(id, lines, Status.DRAFT);
    }

    /** Reconstructs a document from trusted persisted state, not from a client command. */
    public static Shipment restore(String id, List<ShipmentLine> lines, Status persistedStatus) {
        return new Shipment(id, lines, persistedStatus);
    }

    public Shipment confirm() { return transition(Status.DRAFT, Status.CONFIRMED); }
    public Shipment post() { return transition(Status.CONFIRMED, Status.POSTED); }

    private Shipment transition(Status expected, Status next) {
        if (status != expected) throw new IllegalStateException("cannot transition " + status + " to " + next);
        return new Shipment(id, lines, next);
    }

    public String id() { return id; }
    public List<ShipmentLine> lines() { return lines; }
    public Status status() { return status; }
}
