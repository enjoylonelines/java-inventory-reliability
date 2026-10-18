-- Inventory-only additive migration: an older insert can continue omitting the new column.
BEGIN;
ALTER TABLE inv_transactions ADD COLUMN IF NOT EXISTS source_version TEXT NOT NULL DEFAULT 'legacy';
CREATE INDEX IF NOT EXISTS inv_transactions_source_version ON inv_transactions(source_version);
COMMIT;
