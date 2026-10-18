-- Migration for a dedicated schema initialized before ir_outbox_history existed.
-- Oracle DDL commits implicitly; review the target schema before execution.
CREATE TABLE ir_outbox_history (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    outbox_id VARCHAR2(36 CHAR) NOT NULL REFERENCES ir_outbox(id),
    transition VARCHAR2(16 CHAR) NOT NULL CHECK (transition IN ('CLAIMED', 'SENT', 'RETRY', 'DEAD', 'REQUEUED')),
    actor VARCHAR2(100 CHAR) NOT NULL,
    attempt NUMBER(10) NOT NULL CHECK (attempt >= 0),
    detail VARCHAR2(2000 CHAR),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX ir_outbox_history_event_idx ON ir_outbox_history (outbox_id, created_at);
