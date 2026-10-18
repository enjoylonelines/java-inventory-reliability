package com.inventory.oracle;

/** A test double only; implementations must deduplicate by eventId and reject payload conflicts. */
public interface OracleMockDelivery {
    void deliver(String eventId, String payload) throws Exception;
}
