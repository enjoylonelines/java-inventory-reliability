CREATE TABLE IF NOT EXISTS inv_transactions (
 id UUID PRIMARY KEY, request_key TEXT NOT NULL, mode TEXT NOT NULL,
 fingerprint TEXT NOT NULL, body TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS inv_outbox_request_key ON inv_transactions (request_key) WHERE mode = 'outbox';
CREATE TABLE IF NOT EXISTS inv_outbox (
 id UUID PRIMARY KEY, transaction_id UUID NOT NULL UNIQUE REFERENCES inv_transactions(id),
 request_key TEXT NOT NULL, fault TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING',
 attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 lease_until TIMESTAMPTZ, lease_token UUID, last_error TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), completed_at TIMESTAMPTZ
);
CREATE TABLE IF NOT EXISTS inv_audit (
 id BIGSERIAL PRIMARY KEY, event_id UUID NOT NULL, transition TEXT NOT NULL,
 detail TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS inv_erp_catalog (
 kind TEXT NOT NULL, external_id TEXT NOT NULL, active BOOLEAN NOT NULL DEFAULT true,
 PRIMARY KEY(kind, external_id)
);
CREATE TABLE IF NOT EXISTS inv_mappings (
 kind TEXT NOT NULL, local_id TEXT NOT NULL, external_id TEXT NOT NULL, active BOOLEAN NOT NULL DEFAULT true,
 PRIMARY KEY(kind, local_id)
);
