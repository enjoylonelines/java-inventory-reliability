-- Run only in a dedicated, initialized test schema; all test rows are rolled back.
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET SERVEROUTPUT ON
DECLARE
  v_count NUMBER;
  v_quantity NUMBER;
BEGIN
  INSERT INTO ir_stock (warehouse_id, sku, quantity) VALUES ('smoke-warehouse', 'smoke-sku', 5);
  INSERT INTO ir_orders (id, status) VALUES ('smoke-order', 'CONFIRMED');
  INSERT INTO ir_order_lines (id, order_id, warehouse_id, sku, quantity, unit_price)
    VALUES ('smoke-order-line', 'smoke-order', 'smoke-warehouse', 'smoke-sku', 2, 3.50);
  INSERT INTO ir_shipments (id, order_id, status) VALUES ('smoke-shipment', 'smoke-order', 'DRAFT');
  INSERT INTO ir_shipment_lines (id, shipment_id, order_id, order_line_id, warehouse_id, sku,
      quantity, unit_price, amount)
    VALUES ('smoke-line', 'smoke-shipment', 'smoke-order', 'smoke-order-line',
      'smoke-warehouse', 'smoke-sku', 2, 3.50, 7.00);

  SELECT quantity INTO v_quantity FROM ir_stock
    WHERE warehouse_id = 'smoke-warehouse' AND sku = 'smoke-sku' FOR UPDATE;
  UPDATE ir_stock SET quantity = quantity - 6
    WHERE warehouse_id = 'smoke-warehouse' AND sku = 'smoke-sku' AND quantity >= 6;
  IF SQL%ROWCOUNT <> 0 THEN RAISE_APPLICATION_ERROR(-20001, 'overdraft was not rejected'); END IF;
  UPDATE ir_stock SET quantity = quantity - 2
    WHERE warehouse_id = 'smoke-warehouse' AND sku = 'smoke-sku' AND quantity >= 2;
  IF SQL%ROWCOUNT <> 1 THEN RAISE_APPLICATION_ERROR(-20002, 'valid debit was rejected'); END IF;
  INSERT INTO ir_stock_movements (id, document_type, document_id, document_line_id, warehouse_id, sku, quantity_delta)
    VALUES ('smoke-opening', 'OPENING', 'smoke-opening', 'smoke-opening', 'smoke-warehouse', 'smoke-sku', 5);
  INSERT INTO ir_stock_movements (id, document_type, document_id, document_line_id, warehouse_id, sku, quantity_delta)
    VALUES ('smoke-movement', 'SHIPMENT', 'smoke-shipment', 'smoke-line', 'smoke-warehouse', 'smoke-sku', -2);
  INSERT INTO ir_journal_entries (id, document_type, document_id, document_line_id, account, amount)
    VALUES ('smoke-debit', 'SHIPMENT', 'smoke-shipment', 'smoke-line', 'RECEIVABLE', 7);
  INSERT INTO ir_journal_entries (id, document_type, document_id, document_line_id, account, amount)
    VALUES ('smoke-credit', 'SHIPMENT', 'smoke-shipment', 'smoke-line', 'REVENUE', -7);
  UPDATE ir_shipments SET status = 'CONFIRMED' WHERE id = 'smoke-shipment' AND status = 'DRAFT';
  UPDATE ir_shipments SET status = 'POSTED', posted_at = SYSTIMESTAMP
    WHERE id = 'smoke-shipment' AND status = 'CONFIRMED';
  INSERT INTO ir_request_keys (request_key, fingerprint, document_type, document_id)
    VALUES ('smoke-key', 'smoke-fingerprint', 'SHIPMENT', 'smoke-shipment');
  INSERT INTO ir_outbox (id, document_type, document_id, payload)
    VALUES ('smoke-event', 'SHIPMENT', 'smoke-shipment', '{}');

  SELECT returned_quantity INTO v_quantity FROM ir_shipment_lines WHERE id = 'smoke-line' FOR UPDATE;
  UPDATE ir_shipment_lines SET returned_quantity = returned_quantity + 3, returned_amount = returned_amount + 7
    WHERE id = 'smoke-line' AND returned_quantity + 3 <= quantity AND returned_amount + 7 <= amount;
  IF SQL%ROWCOUNT <> 0 THEN RAISE_APPLICATION_ERROR(-20007, 'excess return was not rejected'); END IF;
  UPDATE ir_shipment_lines SET returned_quantity = returned_quantity + 1, returned_amount = returned_amount + 3.50
    WHERE id = 'smoke-line' AND returned_quantity + 1 <= quantity AND returned_amount + 3.50 <= amount;
  IF SQL%ROWCOUNT <> 1 THEN RAISE_APPLICATION_ERROR(-20008, 'valid return was rejected'); END IF;
  INSERT INTO ir_returns (id, shipment_id, status) VALUES ('smoke-return', 'smoke-shipment', 'DRAFT');
  INSERT INTO ir_return_lines (id, return_id, shipment_id, shipment_line_id, quantity, amount)
    VALUES ('smoke-return-line', 'smoke-return', 'smoke-shipment', 'smoke-line', 1, 3.50);
  UPDATE ir_stock SET quantity = quantity + 1 WHERE warehouse_id = 'smoke-warehouse' AND sku = 'smoke-sku';
  INSERT INTO ir_stock_movements (id, document_type, document_id, document_line_id, warehouse_id, sku, quantity_delta)
    VALUES ('smoke-return-move', 'RETURN', 'smoke-return', 'smoke-return-line', 'smoke-warehouse', 'smoke-sku', 1);
  INSERT INTO ir_journal_entries (id, document_type, document_id, document_line_id, account, amount)
    VALUES ('smoke-return-debit', 'RETURN', 'smoke-return', 'smoke-return-line', 'RECEIVABLE', -3.50);
  INSERT INTO ir_journal_entries (id, document_type, document_id, document_line_id, account, amount)
    VALUES ('smoke-return-credit', 'RETURN', 'smoke-return', 'smoke-return-line', 'REVENUE', 3.50);
  UPDATE ir_returns SET status = 'CONFIRMED' WHERE id = 'smoke-return' AND status = 'DRAFT';
  UPDATE ir_returns SET status = 'POSTED', posted_at = SYSTIMESTAMP
    WHERE id = 'smoke-return' AND status = 'CONFIRMED';
  SELECT SUM(amount) INTO v_quantity FROM ir_journal_entries
    WHERE document_id IN ('smoke-shipment', 'smoke-return') AND account = 'RECEIVABLE';
  IF v_quantity <> 3.50 THEN RAISE_APPLICATION_ERROR(-20009, 'return did not reverse receivable'); END IF;

  SELECT COUNT(*) INTO v_count FROM ir_stock s
    WHERE s.warehouse_id = 'smoke-warehouse' AND s.sku = 'smoke-sku'
      AND s.quantity = (SELECT SUM(m.quantity_delta) FROM ir_stock_movements m
        WHERE m.warehouse_id = s.warehouse_id AND m.sku = s.sku);
  IF v_count <> 1 THEN RAISE_APPLICATION_ERROR(-20003, 'balance does not match ledger'); END IF;
  SELECT SUM(amount) INTO v_quantity FROM ir_journal_entries WHERE document_id = 'smoke-shipment';
  IF v_quantity <> 0 THEN RAISE_APPLICATION_ERROR(-20004, 'journal is not balanced'); END IF;
  DBMS_OUTPUT.PUT_LINE('SHIPMENT_SMOKE_OK');
  DBMS_OUTPUT.PUT_LINE('RETURN_SMOKE_OK');
  ROLLBACK;
  SELECT COUNT(*) INTO v_count FROM ir_stock WHERE warehouse_id = 'smoke-warehouse' AND sku = 'smoke-sku';
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20005, 'rollback left stock'); END IF;
  SELECT COUNT(*) INTO v_count FROM ir_outbox WHERE id = 'smoke-event';
  IF v_count <> 0 THEN RAISE_APPLICATION_ERROR(-20006, 'rollback left outbox'); END IF;
  DBMS_OUTPUT.PUT_LINE('ROLLBACK_SMOKE_OK');
END;
/
EXIT
