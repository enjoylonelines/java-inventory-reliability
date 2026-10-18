-- Run only in a dedicated, initialized test schema; all test rows are rolled back.
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET SERVEROUTPUT ON
DECLARE
  CURSOR ready_events IS
    SELECT id, payload, attempts FROM ir_outbox
    WHERE id = 'outbox-smoke-event' AND
      ((status = 'PENDING' AND next_attempt_at <= SYSTIMESTAMP)
       OR (status = 'PROCESSING' AND lease_until <= SYSTIMESTAMP))
    ORDER BY next_attempt_at, id FOR UPDATE SKIP LOCKED;
  event_row ready_events%ROWTYPE;
BEGIN
  INSERT INTO ir_outbox (id, document_type, document_id, payload)
    VALUES ('outbox-smoke-event', 'SHIPMENT', 'outbox-smoke-document', '{"sample":true}');
  OPEN ready_events;
  FETCH ready_events INTO event_row;
  IF ready_events%NOTFOUND OR event_row.id <> 'outbox-smoke-event' THEN
    RAISE_APPLICATION_ERROR(-20101, 'event was not selected');
  END IF;
  UPDATE ir_outbox SET status = 'PROCESSING', attempts = attempts + 1,
    lease_token = 'valid-token', lease_until = SYSTIMESTAMP + NUMTODSINTERVAL(30, 'SECOND'), last_error = NULL
    WHERE id = event_row.id AND ((status = 'PENDING' AND next_attempt_at <= SYSTIMESTAMP)
      OR (status = 'PROCESSING' AND lease_until <= SYSTIMESTAMP));
  IF SQL%ROWCOUNT <> 1 THEN RAISE_APPLICATION_ERROR(-20102, 'claim failed'); END IF;
  CLOSE ready_events;
  UPDATE ir_outbox SET status = 'SENT', lease_token = NULL, lease_until = NULL, completed_at = SYSTIMESTAMP
    WHERE id = 'outbox-smoke-event' AND status = 'PROCESSING' AND lease_token = 'old-token'
      AND lease_until > SYSTIMESTAMP;
  IF SQL%ROWCOUNT <> 0 THEN RAISE_APPLICATION_ERROR(-20103, 'stale token was accepted'); END IF;
  UPDATE ir_outbox SET lease_until = SYSTIMESTAMP - NUMTODSINTERVAL(1, 'SECOND'), attempts = 5
    WHERE id = 'outbox-smoke-event';
  UPDATE ir_outbox SET status = 'SENT', lease_token = NULL, lease_until = NULL, completed_at = SYSTIMESTAMP
    WHERE id = 'outbox-smoke-event' AND status = 'PROCESSING' AND lease_token = 'valid-token'
      AND lease_until > SYSTIMESTAMP;
  IF SQL%ROWCOUNT <> 0 THEN RAISE_APPLICATION_ERROR(-20104, 'expired token was accepted'); END IF;
  UPDATE ir_outbox SET status = 'DEAD', lease_token = NULL, lease_until = NULL,
    last_error = 'attempt limit reached after lease expiry'
    WHERE id = 'outbox-smoke-event' AND attempts >= 5
      AND ((status = 'PROCESSING' AND lease_until <= SYSTIMESTAMP)
        OR (status = 'PENDING' AND next_attempt_at <= SYSTIMESTAMP));
  IF SQL%ROWCOUNT <> 1 THEN RAISE_APPLICATION_ERROR(-20105, 'expired attempt limit was not applied'); END IF;
  INSERT INTO ir_outbox_history (id, outbox_id, transition, actor, attempt, detail)
    VALUES ('outbox-smoke-history', 'outbox-smoke-event', 'DEAD', 'worker', 5, 'lease expired');
  UPDATE ir_outbox SET status = 'PENDING', attempts = 0, next_attempt_at = SYSTIMESTAMP,
    completed_at = NULL, last_error = NULL WHERE id = 'outbox-smoke-event' AND status = 'DEAD';
  IF SQL%ROWCOUNT <> 1 THEN RAISE_APPLICATION_ERROR(-20106, 'manual requeue failed'); END IF;
  ROLLBACK;
  DBMS_OUTPUT.PUT_LINE('OUTBOX_SMOKE_OK');
END;
/
EXIT
