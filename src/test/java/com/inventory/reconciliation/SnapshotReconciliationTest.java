package com.inventory.reconciliation;

import static com.inventory.reconciliation.SnapshotReconciliation.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotReconciliationTest {
    private static final Instant CUTOFF = Instant.parse("2026-10-18T00:00:00Z");
    private static final StockKey KEY = new StockKey("warehouse", "sku");

    private static BigDecimal amount(String value) { return new BigDecimal(value); }
    private static <T> Snapshot<T> snapshot(List<T> rows) { return new Snapshot<>(CUTOFF, rows); }
    private static Movement movement(String id, String quantity) { return new Movement(id, KEY, amount(quantity)); }
    private static EventLine line(String sku, String quantity, String money) { return new EventLine("warehouse", sku, amount(quantity), amount(money)); }

    @Test void inventoryAddsSignedOpeningOutboundAndReturn() {
        var result = inventory(snapshot(List.of(movement("opening", "10"), movement("out", "-6"), movement("return", "2"))),
            snapshot(List.of(new Balance(KEY, amount("6.00")))));
        assertEquals(1, result.size());
        assertEquals(Status.MATCH, result.get(0).status());
        assertEquals(0, amount(result.get(0).expected()).compareTo(amount("6")));
    }

    @Test void inventoryDistinguishesMissingDuplicateAndMismatch() {
        assertEquals(Status.MISSING, inventory(snapshot(List.of(movement("opening", "10"))), snapshot(List.<Balance>of())).get(0).status());
        assertEquals(Status.MISSING, inventory(snapshot(List.<Movement>of()), snapshot(List.of(new Balance(KEY, amount("0"))))).get(0).status());
        assertEquals(Status.DUPLICATE, inventory(snapshot(List.of(movement("same", "10"), movement("same", "-1"))),
            snapshot(List.of(new Balance(KEY, amount("9"))))).get(0).status());
        assertEquals(Status.DUPLICATE, inventory(snapshot(List.of(movement("opening", "10"))),
            snapshot(List.of(new Balance(KEY, amount("10")), new Balance(KEY, amount("10"))))).get(0).status());
        assertEquals(Status.CONFLICT, inventory(snapshot(List.of(movement("opening", "10"))),
            snapshot(List.of(new Balance(KEY, amount("9"))))).get(0).status());
    }

    @Test void accountingAggregatesSignedEntriesAndChecksBothAccounts() {
        var docs = snapshot(List.of(new PostedDocument("sale", amount("100"), amount("100")),
            new PostedDocument("return", amount("-30"), amount("-30"))));
        var entries = snapshot(List.of(new Posting("s1", "sale", Account.SALES, amount("60")),
            new Posting("s2", "sale", Account.SALES, amount("40")),
            new Posting("r1", "sale", Account.RECEIVABLE, amount("100.00")),
            new Posting("s3", "return", Account.SALES, amount("-30")),
            new Posting("r2", "return", Account.RECEIVABLE, amount("-30"))));
        var result = accounting(docs, entries);
        assertEquals(4, result.size());
        assertTrue(result.stream().allMatch(row -> row.status() == Status.MATCH));
    }

    @Test void accountingDoesNotTreatMissingZeroPostingAsMatch() {
        var docs = snapshot(List.of(new PostedDocument("sale", amount("10"), amount("0"))));
        var entries = snapshot(List.of(new Posting("s", "sale", Account.SALES, amount("10"))));
        var result = accounting(docs, entries);
        assertEquals(Status.MATCH, result.get(0).status());
        assertEquals(Status.MISSING, result.get(1).status());
    }

    @Test void accountingFindsWrongSignDuplicateAndUnexpectedDocument() {
        var docs = snapshot(List.of(new PostedDocument("return", amount("-5"), amount("-5"))));
        var wrongSign = snapshot(List.of(new Posting("a", "return", Account.SALES, amount("5")),
            new Posting("b", "return", Account.RECEIVABLE, amount("-5"))));
        assertEquals(Status.CONFLICT, accounting(docs, wrongSign).get(0).status());
        var duplicated = snapshot(List.of(new Posting("same", "return", Account.SALES, amount("-5")),
            new Posting("same", "return", Account.RECEIVABLE, amount("-5"))));
        assertTrue(accounting(docs, duplicated).stream().allMatch(row -> row.status() == Status.DUPLICATE));
        var orphan = snapshot(List.of(new Posting("orphan", "missing", Account.SALES, amount("1"))));
        assertEquals(Status.UNEXPECTED, accounting(snapshot(List.<PostedDocument>of()), orphan).get(0).status());
    }

    @Test void externalMatchesByIdSkuAndSignedValuesIgnoringOrderAndScale() {
        var expected = snapshot(List.of(new ExpectedEvent("evt", Delivery.DONE,
            List.of(line("A", "-2", "100.00"), line("B", "1", "-40")))));
        var observed = snapshot(List.of(new ObservedEvent("evt",
            List.of(line("B", "1.0", "-40.0"), line("A", "-2.0", "100")))));
        assertEquals(Status.MATCH, external(expected, observed).get(0).status());
    }

    @Test void externalDistinguishesTheSameSkuInDifferentWarehouses() {
        var one = new EventLine("w1", "A", amount("-1"), amount("10"));
        var two = new EventLine("w2", "A", amount("-2"), amount("20"));
        var expected = snapshot(List.of(new ExpectedEvent("evt", Delivery.DONE, List.of(one, two))));
        assertEquals(Status.MATCH, external(expected, snapshot(List.of(new ObservedEvent("evt", List.of(two, one))))).get(0).status());
        var wrongWarehouse = new EventLine("w3", "A", amount("-2"), amount("20"));
        assertEquals(Status.CONFLICT, external(expected, snapshot(List.of(new ObservedEvent("evt", List.of(one, wrongWarehouse))))).get(0).status());
    }

    @Test void externalSeparatesPendingFromFinalMissingAndConflict() {
        var expected = snapshot(List.of(new ExpectedEvent("waiting", Delivery.PENDING, List.of(line("A", "-2", "10"))),
            new ExpectedEvent("working", Delivery.PROCESSING, List.of(line("A", "-2", "10"))),
            new ExpectedEvent("finished", Delivery.DONE, List.of(line("A", "-2", "10"))),
            new ExpectedEvent("failed", Delivery.DEAD, List.of(line("A", "-2", "10")))));
        var observed = snapshot(List.of(new ObservedEvent("working", List.of(line("A", "-1", "10"))),
            new ObservedEvent("finished", List.of(line("B", "-2", "10")))));
        var byId = external(expected, observed).stream().collect(java.util.stream.Collectors.toMap(Outcome::key, Outcome::status));
        assertEquals(Status.PENDING, byId.get("waiting"));
        assertEquals(Status.PENDING, byId.get("working"));
        assertEquals(Status.CONFLICT, byId.get("finished"));
        assertEquals(Status.MISSING, byId.get("failed"));
    }

    @Test void externalFindsDuplicateEventsAndLinesAndUnexpectedEvents() {
        var event = new ExpectedEvent("evt", Delivery.DONE, List.of(line("A", "-2", "10")));
        var duplicate = snapshot(List.of(new ObservedEvent("evt", List.of(line("A", "-2", "10"))),
            new ObservedEvent("evt", List.of(line("A", "-2", "10")))));
        assertEquals(Status.DUPLICATE, external(snapshot(List.of(event)), duplicate).get(0).status());
        var repeatedLine = snapshot(List.of(new ObservedEvent("evt", List.of(line("A", "-1", "5"), line("A", "-1", "5")))));
        assertEquals(Status.DUPLICATE, external(snapshot(List.of(event)), repeatedLine).get(0).status());
        assertEquals(Status.UNEXPECTED, external(snapshot(List.<ExpectedEvent>of()),
            snapshot(List.of(new ObservedEvent("unknown", List.of(line("A", "-2", "10")))))).get(0).status());
    }

    @Test void snapshotsMustHaveTheSameExplicitCutoff() {
        var a = snapshot(List.of(movement("opening", "10")));
        var b = new Snapshot<>(CUTOFF.plusSeconds(1), List.of(new Balance(KEY, amount("10"))));
        assertThrows(IllegalArgumentException.class, () -> inventory(a, b));
        assertThrows(IllegalArgumentException.class, () -> new ExpectedEvent("empty", Delivery.DONE, List.of()));
    }

    @Test void snapshotsCopyListsAndCannotChangeAfterCapture() {
        var source = new java.util.ArrayList<>(List.of(movement("opening", "10")));
        var captured = snapshot(source);
        source.clear();
        assertEquals(1, captured.rows().size());
        assertThrows(UnsupportedOperationException.class, () -> captured.rows().clear());
    }
}
