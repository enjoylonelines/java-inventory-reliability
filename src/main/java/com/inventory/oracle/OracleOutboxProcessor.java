package com.inventory.oracle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.cursor.Cursor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Outbox dispatch is at-least-once; the test double must deduplicate event IDs. */
@Component
@Profile("oracle")
@ConditionalOnProperty(name = "inventory.oracle.outbox.enabled", havingValue = "true")
public final class OracleOutboxProcessor {
    private final OracleOutboxMapper mapper;
    private final TransactionTemplate tx;
    private final OracleMockDelivery delivery;
    private final long leaseSeconds;
    private final int maxAttempts;

    public record Claim(String eventId, String token, String payload, int attempt) {}
    public enum Outcome { EMPTY, SENT, RETRY, DEAD, STALE }

    public OracleOutboxProcessor(OracleOutboxMapper mapper, TransactionTemplate tx, OracleMockDelivery delivery,
        @Value("${inventory.oracle.outbox.lease-seconds:30}") long leaseSeconds,
        @Value("${inventory.oracle.outbox.max-attempts:5}") int maxAttempts) {
        if (leaseSeconds < 1 || leaseSeconds > 86400 || maxAttempts < 1 || maxAttempts > 20)
            throw new IllegalArgumentException("invalid outbox lease or attempt limit");
        this.mapper = mapper;
        this.tx = tx;
        this.delivery = delivery;
        this.leaseSeconds = leaseSeconds;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${inventory.oracle.outbox.poll-ms:1000}")
    public void poll() { processOne(); }

    public Outcome processOne() {
        Claim claim = tx.execute(status -> claimOne());
        if (claim == null) return Outcome.EMPTY;
        if (claim.attempt() == 0) return Outcome.DEAD;
        try {
            delivery.deliver(claim.eventId(), claim.payload());
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            // Do not persist arbitrary exception text, credentials, response bodies, or URLs.
            String detail = "mock delivery failure: " + error.getClass().getSimpleName();
            Boolean changed = tx.execute(status -> {
                int count = mapper.fail(claim.eventId(), claim.token(), maxAttempts, backoffSeconds(claim.attempt()), detail);
                if (count != 1) return false;
                addHistory(claim.eventId(), claim.attempt() >= maxAttempts ? "DEAD" : "RETRY", "worker", claim.attempt(), detail);
                return true;
            });
            if (!Boolean.TRUE.equals(changed)) return Outcome.STALE;
            return claim.attempt() >= maxAttempts ? Outcome.DEAD : Outcome.RETRY;
        }
        Boolean changed = tx.execute(status -> {
            int count = mapper.sent(claim.eventId(), claim.token());
            if (count != 1) return false;
            addHistory(claim.eventId(), "SENT", "worker", claim.attempt(), null);
            return true;
        });
        return Boolean.TRUE.equals(changed) ? Outcome.SENT : Outcome.STALE;
    }

    /** Caller must enforce authorization; this method records the actor but is not an access-control boundary. */
    public boolean requeueDead(String eventId, String actor) {
        if (eventId == null || eventId.isBlank() || actor == null || actor.isBlank() || actor.length() > 100)
            throw new IllegalArgumentException("event and actor required");
        return Boolean.TRUE.equals(tx.execute(status -> {
            if (mapper.requeue(eventId) != 1) return false;
            addHistory(eventId, "REQUEUED", actor, 0, null);
            return true;
        }));
    }

    private Claim claimOne() {
        try (Cursor<Map<String, Object>> cursor = mapper.lockReady()) {
            Iterator<Map<String, Object>> iterator = cursor.iterator();
            if (!iterator.hasNext()) return null;
            Map<String, Object> row = iterator.next();
            String eventId = String.valueOf(value(row, "id"));
            String payload = payload(value(row, "payload"));
            int previousAttempts = ((Number) value(row, "attempts")).intValue();
            if (previousAttempts >= maxAttempts) {
                if (mapper.expireAtLimit(eventId, maxAttempts) != 1) throw new IllegalStateException("outbox claim lost");
                addHistory(eventId, "DEAD", "worker", previousAttempts, "attempt limit reached after lease expiry");
                return new Claim(eventId, "", "", 0);
            }
            int attempt = previousAttempts + 1;
            String token = UUID.randomUUID().toString();
            if (mapper.claim(eventId, token, leaseSeconds) != 1) throw new IllegalStateException("outbox claim lost");
            addHistory(eventId, "CLAIMED", "worker", attempt, null);
            return new Claim(eventId, token, payload, attempt);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void addHistory(String eventId, String transition, String actor, int attempt, String detail) {
        if (mapper.history(UUID.randomUUID().toString(), eventId, transition, actor, attempt, detail) != 1)
            throw new IllegalStateException("outbox history write failed");
    }

    private static Object value(Map<String, Object> row, String key) {
        Object value = row.containsKey(key) ? row.get(key) : row.get(key.toUpperCase(Locale.ROOT));
        if (value == null) throw new IllegalStateException("missing outbox column: " + key);
        return value;
    }

    private static String payload(Object value) {
        if (value instanceof String text) return text;
        if (value instanceof Clob clob) {
            try {
                long length = clob.length();
                if (length > Integer.MAX_VALUE) throw new IllegalStateException("outbox payload too large");
                return clob.getSubString(1, (int) length);
            } catch (SQLException e) { throw new IllegalStateException("cannot read outbox payload", e); }
        }
        throw new IllegalStateException("unsupported outbox payload type");
    }

    private static long backoffSeconds(int attempt) { return Math.min(300L, 1L << Math.min(attempt - 1, 8)); }
}
