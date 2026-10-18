-- Optional measured candidate for the stock report. Run only on a dedicated
-- Oracle schema after confirming the index does not already exist.
-- Oracle DDL commits implicitly. This adds write/storage cost; it is not part
-- of automatic startup and is not a validated production recommendation.
CREATE INDEX ir_movement_stock_cover_idx
    ON ir_stock_movements (warehouse_id, sku, quantity_delta);
