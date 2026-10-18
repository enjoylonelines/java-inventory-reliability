-- Additive migration for a schema initialized before ir_action_audit existed.
-- Do not apply after the current full schema.sql: it already contains this table.
-- Oracle DDL commits implicitly; review the target schema before execution.
CREATE TABLE ir_action_audit (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    actor VARCHAR2(100 CHAR) NOT NULL CHECK (TRIM(actor) IS NOT NULL),
    action VARCHAR2(100 CHAR) NOT NULL CHECK (TRIM(action) IS NOT NULL),
    document_type VARCHAR2(16 CHAR) NOT NULL CHECK (TRIM(document_type) IS NOT NULL),
    document_id VARCHAR2(36 CHAR) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX ir_action_audit_document_idx ON ir_action_audit (document_type, document_id, occurred_at);
