package com.inventory.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pure comparison of caller-supplied, fixed-cutoff snapshots. This class does not
 * acquire snapshots or claim that independent systems actually share a consistent
 * view; the caller must establish that property. No database or API is accessed.
 *
 * <p>Inventory movements are signed: receipts and returns are positive, outbound
 * movements negative. Opening inventory must be an explicit movement. Revenue and
 * receivable amounts are positive for sales and negative for returns/reversals;
 * amounts are added with their signs, not absolute values. Event line quantities
 * and amounts must use the same signed convention on both sides. Decimal scale is
 * ignored, but amounts are never rounded here. The caller owns currency, precision,
 * and cutoff policy. A missing explicit record is not silently treated as zero.
 */
public final class SnapshotReconciliation {
    private SnapshotReconciliation() {}

    public enum Status { MATCH, MISSING, DUPLICATE, CONFLICT, PENDING, UNEXPECTED }
    public enum Account { SALES, RECEIVABLE }
    public enum Delivery { PENDING, PROCESSING, DONE, DEAD }

    public record Snapshot<T>(Instant cutoff, List<T> rows) {
        public Snapshot {
            Objects.requireNonNull(cutoff, "cutoff");
            rows = List.copyOf(rows);
        }
    }

    public record Outcome(String key, Status status, String expected, String actual) {}
    public record StockKey(String warehouse, String sku) {
        public StockKey { requireText(warehouse); requireText(sku); }
    }
    public record Movement(String id, StockKey key, BigDecimal quantity) {
        public Movement { requireText(id); Objects.requireNonNull(key); Objects.requireNonNull(quantity); }
    }
    public record Balance(StockKey key, BigDecimal quantity) {
        public Balance { Objects.requireNonNull(key); Objects.requireNonNull(quantity); }
    }
    /** The caller supplies only posted documents and their signed expected totals. */
    public record PostedDocument(String id, BigDecimal sales, BigDecimal receivable) {
        public PostedDocument { requireText(id); Objects.requireNonNull(sales); Objects.requireNonNull(receivable); }
    }
    public record Posting(String id, String documentId, Account account, BigDecimal amount) {
        public Posting { requireText(id); requireText(documentId); Objects.requireNonNull(account); Objects.requireNonNull(amount); }
    }
    public record EventLine(String warehouse, String sku, BigDecimal quantity, BigDecimal amount) {
        public EventLine { requireText(warehouse); requireText(sku); Objects.requireNonNull(quantity); Objects.requireNonNull(amount); }
        public StockKey stockKey() { return new StockKey(warehouse, sku); }
    }
    public record ExpectedEvent(String id, Delivery delivery, List<EventLine> lines) {
        public ExpectedEvent { requireText(id); Objects.requireNonNull(delivery); lines = List.copyOf(lines); requireLines(lines); }
    }
    public record ObservedEvent(String id, List<EventLine> lines) {
        public ObservedEvent { requireText(id); lines = List.copyOf(lines); requireLines(lines); }
    }

    public static List<Outcome> inventory(Snapshot<Movement> movements, Snapshot<Balance> balances) {
        sameCutoff(movements, balances);
        Map<StockKey, List<Movement>> byKey = group(movements.rows(), Movement::key);
        Map<StockKey, List<Balance>> balancesByKey = group(balances.rows(), Balance::key);
        Set<String> duplicateIds = duplicates(movements.rows(), Movement::id);
        List<Outcome> result = new ArrayList<>();
        Set<StockKey> keys = new HashSet<>(byKey.keySet());
        keys.addAll(balancesByKey.keySet());
        keys.stream().sorted((a, b) -> {
            int warehouse = a.warehouse().compareTo(b.warehouse());
            return warehouse != 0 ? warehouse : a.sku().compareTo(b.sku());
        }).forEach(key -> {
            List<Movement> entries = byKey.getOrDefault(key, List.of());
            List<Balance> current = balancesByKey.getOrDefault(key, List.of());
            String label = key.toString();
            if (entries.stream().anyMatch(m -> duplicateIds.contains(m.id())) || current.size() > 1) {
                result.add(new Outcome(label, Status.DUPLICATE, "unique movement IDs", "duplicate movement or balance"));
            } else if (entries.isEmpty() || current.isEmpty()) {
                result.add(new Outcome(label, Status.MISSING, entries.isEmpty() ? "movement missing" : sum(entries, Movement::quantity).toPlainString(),
                    current.isEmpty() ? "balance missing" : current.get(0).quantity().toPlainString()));
            } else {
                BigDecimal expected = sum(entries, Movement::quantity);
                BigDecimal actual = current.get(0).quantity();
                result.add(amountOutcome(label, expected, actual));
            }
        });
        return List.copyOf(result);
    }

    public static List<Outcome> accounting(Snapshot<PostedDocument> documents, Snapshot<Posting> postings) {
        sameCutoff(documents, postings);
        Map<String, List<PostedDocument>> docs = group(documents.rows(), PostedDocument::id);
        Map<String, List<Posting>> entries = group(postings.rows(), Posting::documentId);
        Set<String> duplicateIds = duplicates(postings.rows(), Posting::id);
        Set<String> keys = new HashSet<>(docs.keySet());
        keys.addAll(entries.keySet());
        List<Outcome> result = new ArrayList<>();
        keys.stream().sorted().forEach(id -> {
            List<PostedDocument> documentRows = docs.getOrDefault(id, List.of());
            List<Posting> postingRows = entries.getOrDefault(id, List.of());
            for (Account account : Account.values()) {
                String label = id + ":" + account;
                List<Posting> accountRows = postingRows.stream().filter(p -> p.account() == account).toList();
                if (documentRows.size() > 1 || postingRows.stream().anyMatch(p -> duplicateIds.contains(p.id()))) {
                    result.add(new Outcome(label, Status.DUPLICATE, "unique document and posting IDs", "duplicate ID"));
                } else if (documentRows.isEmpty()) {
                    if (!accountRows.isEmpty()) result.add(new Outcome(label, Status.UNEXPECTED, "no posted document", sum(accountRows, Posting::amount).toPlainString()));
                } else {
                    PostedDocument doc = documentRows.get(0);
                    BigDecimal expected = account == Account.SALES ? doc.sales() : doc.receivable();
                    if (accountRows.isEmpty()) result.add(new Outcome(label, Status.MISSING, expected.toPlainString(), "posting missing"));
                    else result.add(amountOutcome(label, expected, sum(accountRows, Posting::amount)));
                }
            }
        });
        return List.copyOf(result);
    }

    /** A non-matching in-flight event is PENDING, not a final discrepancy. */
    public static List<Outcome> external(Snapshot<ExpectedEvent> expected, Snapshot<ObservedEvent> observed) {
        sameCutoff(expected, observed);
        Map<String, List<ExpectedEvent>> expectedById = group(expected.rows(), ExpectedEvent::id);
        Map<String, List<ObservedEvent>> observedById = group(observed.rows(), ObservedEvent::id);
        Set<String> ids = new HashSet<>(expectedById.keySet());
        ids.addAll(observedById.keySet());
        List<Outcome> result = new ArrayList<>();
        ids.stream().sorted().forEach(id -> {
            List<ExpectedEvent> left = expectedById.getOrDefault(id, List.of());
            List<ObservedEvent> right = observedById.getOrDefault(id, List.of());
            if (left.size() > 1) {
                result.add(new Outcome(id, Status.DUPLICATE, "one expected event", "multiple expected events"));
                return;
            }
            if (left.isEmpty()) {
                result.add(new Outcome(id, right.size() > 1 ? Status.DUPLICATE : Status.UNEXPECTED, "no expected event", right.size() + " observed events"));
                return;
            }
            ExpectedEvent event = left.get(0);
            boolean inFlight = event.delivery() == Delivery.PENDING || event.delivery() == Delivery.PROCESSING;
            Status issue;
            String detail;
            if (right.size() > 1) {
                issue = Status.DUPLICATE; detail = "multiple observed events";
            } else if (right.isEmpty()) {
                issue = Status.MISSING; detail = "observed event missing";
            } else if (!duplicates(event.lines(), EventLine::stockKey).isEmpty() || !duplicates(right.get(0).lines(), EventLine::stockKey).isEmpty()) {
                issue = Status.DUPLICATE; detail = "duplicate warehouse and SKU line";
            } else if (!sameLines(event.lines(), right.get(0).lines())) {
                issue = Status.CONFLICT; detail = "warehouse, SKU, signed quantity, or amount differs";
            } else {
                result.add(new Outcome(id, Status.MATCH, "event and lines", "event and lines"));
                return;
            }
            result.add(new Outcome(id, inFlight ? Status.PENDING : issue, "event and lines", detail));
        });
        return List.copyOf(result);
    }

    private static boolean sameLines(List<EventLine> expected, List<EventLine> observed) {
        Map<StockKey, EventLine> actual = observed.stream().collect(Collectors.toMap(EventLine::stockKey, Function.identity()));
        if (expected.size() != actual.size()) return false;
        for (EventLine line : expected) {
            EventLine other = actual.get(line.stockKey());
            if (other == null || line.quantity().compareTo(other.quantity()) != 0 || line.amount().compareTo(other.amount()) != 0) return false;
        }
        return true;
    }

    private static Outcome amountOutcome(String key, BigDecimal expected, BigDecimal actual) {
        return new Outcome(key, expected.compareTo(actual) == 0 ? Status.MATCH : Status.CONFLICT, expected.toPlainString(), actual.toPlainString());
    }
    private static <T> BigDecimal sum(List<T> rows, Function<T, BigDecimal> value) {
        return rows.stream().map(value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private static <T, K> Map<K, List<T>> group(List<T> rows, Function<T, K> key) {
        Map<K, List<T>> groups = new LinkedHashMap<>();
        for (T row : rows) groups.computeIfAbsent(key.apply(row), ignored -> new ArrayList<>()).add(row);
        return groups;
    }
    private static <T, K> Set<K> duplicates(List<T> rows, Function<T, K> key) {
        Set<K> seen = new HashSet<>(), duplicates = new HashSet<>();
        for (T row : rows) if (!seen.add(key.apply(row))) duplicates.add(key.apply(row));
        return duplicates;
    }
    private static void sameCutoff(Snapshot<?> left, Snapshot<?> right) {
        Objects.requireNonNull(left); Objects.requireNonNull(right);
        if (!left.cutoff().equals(right.cutoff())) throw new IllegalArgumentException("snapshot cutoffs differ");
    }
    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("identifier must not be blank");
    }
    private static void requireLines(List<EventLine> lines) {
        if (lines.isEmpty()) throw new IllegalArgumentException("event lines must not be empty");
    }
}
