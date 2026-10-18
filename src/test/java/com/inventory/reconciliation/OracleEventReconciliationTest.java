package com.inventory.reconciliation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OracleEventReconciliationTest {
    private static final String ID = UUID.randomUUID().toString();
    private static final String OTHER = UUID.randomUUID().toString();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String payload(String id, String document, String quantity, String amount) {
        return """
            {"eventId":"%s","documentId":"%s","type":"SHIPMENT","lines":[
              {"warehouse":"w","sku":"s","quantityDelta":%s,"receivableDelta":%s}]}
            """.formatted(id, document, quantity, amount);
    }
    private static String state(long count, String remotePayload) {
        if (count == 0) return "{\"applied\":{},\"payloads\":{}}";
        return "{\"applied\":{\"" + ID + "\":" + count + "},\"payloads\":{\"" + ID + "\":" + remotePayload + "}}";
    }
    private static OracleEventReconciliation service(String status, String expected, String remote) {
        return new OracleEventReconciliation(id -> List.of(new OracleEventReconciliation.OutboxRow(status, expected)),
            () -> remote.getBytes(StandardCharsets.UTF_8), JSON);
    }
    private static String normal() { return payload(ID, "document", "-2", "90071992547409.95"); }

    @Test void sentEventWithExactLargeDecimalMatchesAndReportsNonAtomicReads() {
        var result = service("SENT", normal(), state(1, normal())).compare(ID);
        assertEquals(SnapshotReconciliation.Status.MATCH, result.result().status());
        assertEquals(1, result.mockApplicationCount());
        assertFalse(result.mockObservedAt().isBefore(result.oracleObservedAt()));
        assertTrue(result.limitation().contains("not a shared atomic snapshot"));
    }

    @Test void missingEventsDifferByStateAndDoNotClaimFinality() {
        for (String status : List.of("PENDING", "PROCESSING")) {
            var result = service(status, normal(), state(0, "")).compare(ID);
            assertEquals(SnapshotReconciliation.Status.PENDING, result.result().status());
            assertEquals(0, result.mockApplicationCount());
        }
        assertEquals(SnapshotReconciliation.Status.MISSING, service("SENT", normal(), state(0, "")).compare(ID).result().status());
        assertEquals(SnapshotReconciliation.Status.MISSING, service("DEAD", normal(), state(0, "")).compare(ID).result().status());
    }

    @Test void changedAmountsAndMetadataAreConflictOrPendingAsAppropriate() {
        assertEquals(SnapshotReconciliation.Status.CONFLICT,
            service("SENT", normal(), state(1, payload(ID, "document", "-2", "90071992547409.94"))).compare(ID).result().status());
        assertEquals(SnapshotReconciliation.Status.PENDING,
            service("PROCESSING", normal(), state(1, payload(ID, "other-document", "-2", "90071992547409.95"))).compare(ID).result().status());
        assertEquals(SnapshotReconciliation.Status.CONFLICT,
            service("SENT", normal(), state(1, payload(OTHER, "document", "-2", "90071992547409.95"))).compare(ID).result().status());
    }

    @Test void duplicateCountIsPreservedWithoutAssumingAllPayloadsAreEqual() {
        var sent = service("SENT", normal(), state(3, normal())).compare(ID);
        assertEquals(3, sent.mockApplicationCount());
        assertEquals(SnapshotReconciliation.Status.DUPLICATE, sent.result().status());
        assertEquals(SnapshotReconciliation.Status.PENDING, service("PENDING", normal(), state(3, normal())).compare(ID).result().status());
    }

    @Test void missingOrMalformedPayloadDoesNotBecomeAFalseMatch() {
        String missing = "{\"applied\":{\"" + ID + "\":1},\"payloads\":{}}";
        assertThrows(OracleEventReconciliation.Unavailable.class, () -> service("SENT", normal(), missing).compare(ID));
        assertThrows(OracleEventReconciliation.Unavailable.class, () -> service("SENT", normal(), state(1, "{}" )).compare(ID));
        assertThrows(OracleEventReconciliation.Unavailable.class, () -> service("SENT", normal(), "not json").compare(ID));
        assertThrows(OracleEventReconciliation.Unavailable.class, () -> service("UNKNOWN", normal(), state(0, "")).compare(ID));
    }

    @Test void unrelatedMockKeysAreOutsideTheRequestedEventScope() {
        String remote = "{\"applied\":{\"" + OTHER + "\":1},\"payloads\":{\"" + OTHER + "\":" + payload(OTHER, "old", "1", "1") + "}}";
        var result = service("SENT", normal(), remote).compare(ID);
        assertEquals(SnapshotReconciliation.Status.MISSING, result.result().status());
        assertEquals(0, result.mockApplicationCount());
    }

    @Test void oversizedStateAndMissingOracleEventFailClosed() {
        var oversized = new OracleEventReconciliation(id -> List.of(new OracleEventReconciliation.OutboxRow("SENT", normal())),
            () -> new byte[2 * 1024 * 1024 + 1], JSON);
        assertThrows(OracleEventReconciliation.Unavailable.class, () -> oversized.compare(ID));
        var missing = new OracleEventReconciliation(id -> List.of(), () -> { fail("must not request mock"); return new byte[0]; }, JSON);
        assertThrows(NoSuchElementException.class, () -> missing.compare(ID));
        assertThrows(IllegalArgumentException.class, () -> missing.compare("1-1-1-1-1"));
    }

    @Test void onlyLiteralLoopbackStateUrlIsAllowed() {
        assertNotNull(OracleEventReconciliation.validateUrl("http://127.0.0.1:18185/state"));
        assertNotNull(OracleEventReconciliation.validateUrl("http://[::1]:18185/state"));
        for (String url : List.of("http://localhost:18185/state", "http://example.com/state", "https://127.0.0.1/state",
                "http://127.0.0.1:18185/apply", "http://127.0.0.1:18185/state?x=1", "http://127.0.0.1:18185/state#x",
                "http://user@127.0.0.1:18185/state", "http://127.0.0.1/state")) {
            assertThrows(IllegalArgumentException.class, () -> OracleEventReconciliation.validateUrl(url));
        }
    }
}
