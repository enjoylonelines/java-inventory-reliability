-- Oracle schema for a new, dedicated application user. Run once on an empty schema.
-- DDL commits implicitly in Oracle. Never run this against an existing application's schema.
-- All business writes must use one transaction and the common locking protocol.

CREATE TABLE ir_orders (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    status VARCHAR2(16 CHAR) NOT NULL CHECK (status IN ('DRAFT', 'CONFIRMED')),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE TABLE ir_order_lines (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    order_id VARCHAR2(36 CHAR) NOT NULL REFERENCES ir_orders(id),
    warehouse_id VARCHAR2(80 CHAR) NOT NULL,
    sku VARCHAR2(80 CHAR) NOT NULL,
    quantity NUMBER(18, 0) NOT NULL CHECK (quantity > 0),
    unit_price NUMBER(18, 2) NOT NULL CHECK (unit_price >= 0),
    UNIQUE (order_id, id)
);

CREATE TABLE ir_shipments (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    order_id VARCHAR2(36 CHAR) NOT NULL REFERENCES ir_orders(id),
    status VARCHAR2(16 CHAR) NOT NULL CHECK (status IN ('DRAFT', 'CONFIRMED', 'POSTED')),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    posted_at TIMESTAMP WITH TIME ZONE,
    CHECK ((status = 'POSTED' AND posted_at IS NOT NULL) OR (status <> 'POSTED' AND posted_at IS NULL)),
    UNIQUE (id, order_id)
);

CREATE TABLE ir_shipment_lines (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    shipment_id VARCHAR2(36 CHAR) NOT NULL,
    order_id VARCHAR2(36 CHAR) NOT NULL,
    order_line_id VARCHAR2(36 CHAR) NOT NULL,
    warehouse_id VARCHAR2(80 CHAR) NOT NULL,
    sku VARCHAR2(80 CHAR) NOT NULL,
    quantity NUMBER(18, 0) NOT NULL CHECK (quantity > 0),
    unit_price NUMBER(18, 2) NOT NULL CHECK (unit_price >= 0),
    -- The original rounded amount is fixed at posting; do not recompute it on each return.
    amount NUMBER(18, 2) NOT NULL CHECK (amount >= 0),
    returned_quantity NUMBER(18, 0) DEFAULT 0 NOT NULL,
    returned_amount NUMBER(18, 2) DEFAULT 0 NOT NULL,
    CHECK (returned_quantity >= 0 AND returned_quantity <= quantity),
    CHECK (returned_amount >= 0 AND returned_amount <= amount),
    FOREIGN KEY (shipment_id, order_id) REFERENCES ir_shipments(id, order_id),
    FOREIGN KEY (order_id, order_line_id) REFERENCES ir_order_lines(order_id, id),
    UNIQUE (shipment_id, id),
    UNIQUE (shipment_id, order_line_id)
);

CREATE TABLE ir_returns (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    shipment_id VARCHAR2(36 CHAR) NOT NULL REFERENCES ir_shipments(id),
    status VARCHAR2(16 CHAR) NOT NULL CHECK (status IN ('DRAFT', 'CONFIRMED', 'POSTED')),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    posted_at TIMESTAMP WITH TIME ZONE,
    CHECK ((status = 'POSTED' AND posted_at IS NOT NULL) OR (status <> 'POSTED' AND posted_at IS NULL)),
    UNIQUE (id, shipment_id)
);

CREATE TABLE ir_return_lines (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    return_id VARCHAR2(36 CHAR) NOT NULL,
    shipment_id VARCHAR2(36 CHAR) NOT NULL,
    shipment_line_id VARCHAR2(36 CHAR) NOT NULL,
    quantity NUMBER(18, 0) NOT NULL CHECK (quantity > 0),
    amount NUMBER(18, 2) NOT NULL CHECK (amount >= 0),
    FOREIGN KEY (return_id, shipment_id) REFERENCES ir_returns(id, shipment_id),
    FOREIGN KEY (shipment_id, shipment_line_id) REFERENCES ir_shipment_lines(shipment_id, id),
    UNIQUE (return_id, shipment_line_id)
);

-- A zero balance row must exist before any stock operation; absence cannot be row-locked.
CREATE TABLE ir_stock (
    warehouse_id VARCHAR2(80 CHAR) NOT NULL,
    sku VARCHAR2(80 CHAR) NOT NULL,
    quantity NUMBER(18, 0) NOT NULL CHECK (quantity >= 0),
    PRIMARY KEY (warehouse_id, sku)
);

CREATE TABLE ir_stock_movements (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    document_type VARCHAR2(16 CHAR) NOT NULL CHECK (document_type IN ('OPENING', 'SHIPMENT', 'RETURN')),
    document_id VARCHAR2(36 CHAR) NOT NULL,
    document_line_id VARCHAR2(36 CHAR) NOT NULL,
    warehouse_id VARCHAR2(80 CHAR) NOT NULL,
    sku VARCHAR2(80 CHAR) NOT NULL,
    quantity_delta NUMBER(18, 0) NOT NULL CHECK (quantity_delta <> 0),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    FOREIGN KEY (warehouse_id, sku) REFERENCES ir_stock(warehouse_id, sku),
    UNIQUE (document_type, document_line_id),
    CHECK ((document_type IN ('OPENING', 'RETURN') AND quantity_delta > 0)
        OR (document_type = 'SHIPMENT' AND quantity_delta < 0))
);

-- Signed debit-positive entries: shipment RECEIVABLE positive / REVENUE negative;
-- a return uses the inverse. Balanced totals and origin links require transaction checks.
CREATE TABLE ir_journal_entries (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    document_type VARCHAR2(16 CHAR) NOT NULL CHECK (document_type IN ('SHIPMENT', 'RETURN')),
    document_id VARCHAR2(36 CHAR) NOT NULL,
    document_line_id VARCHAR2(36 CHAR) NOT NULL,
    account VARCHAR2(16 CHAR) NOT NULL CHECK (account IN ('RECEIVABLE', 'REVENUE')),
    amount NUMBER(18, 2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    UNIQUE (document_type, document_line_id, account),
    CHECK ((document_type = 'SHIPMENT' AND account = 'RECEIVABLE' AND amount >= 0)
        OR (document_type = 'SHIPMENT' AND account = 'REVENUE' AND amount <= 0)
        OR (document_type = 'RETURN' AND account = 'RECEIVABLE' AND amount <= 0)
        OR (document_type = 'RETURN' AND account = 'REVENUE' AND amount >= 0))
);

-- Keys are global within this application and currently retained indefinitely.
-- The application compares the SHA-256 of canonical, versioned request bytes on replay.
CREATE TABLE ir_request_keys (
    request_key VARCHAR2(200 CHAR) PRIMARY KEY,
    fingerprint VARCHAR2(64 CHAR) NOT NULL,
    document_type VARCHAR2(16 CHAR) NOT NULL CHECK (document_type IN ('SHIPMENT', 'RETURN')),
    document_id VARCHAR2(36 CHAR) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    UNIQUE (document_type, document_id)
);

CREATE TABLE ir_outbox (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    document_type VARCHAR2(16 CHAR) NOT NULL CHECK (document_type IN ('SHIPMENT', 'RETURN')),
    document_id VARCHAR2(36 CHAR) NOT NULL,
    status VARCHAR2(16 CHAR) DEFAULT 'PENDING' NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'DEAD')),
    attempts NUMBER(10) DEFAULT 0 NOT NULL CHECK (attempts >= 0),
    next_attempt_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    lease_token VARCHAR2(36 CHAR),
    last_error VARCHAR2(2000 CHAR),
    payload CLOB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (document_type, document_id),
    CHECK ((status = 'PROCESSING' AND lease_until IS NOT NULL AND lease_token IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_until IS NULL AND lease_token IS NULL))
);

CREATE INDEX ir_movement_sku_date_idx ON ir_stock_movements (warehouse_id, sku, created_at);
CREATE INDEX ir_journal_date_account_idx ON ir_journal_entries (created_at, account);
CREATE INDEX ir_outbox_ready_idx ON ir_outbox (status, next_attempt_at);
CREATE INDEX ir_shipments_order_idx ON ir_shipments (order_id);
CREATE INDEX ir_returns_shipment_idx ON ir_returns (shipment_id);

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

CREATE TABLE ir_action_audit (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    actor VARCHAR2(100 CHAR) NOT NULL CHECK (TRIM(actor) IS NOT NULL),
    action VARCHAR2(100 CHAR) NOT NULL CHECK (TRIM(action) IS NOT NULL),
    document_type VARCHAR2(16 CHAR) NOT NULL CHECK (TRIM(document_type) IS NOT NULL),
    document_id VARCHAR2(36 CHAR) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);
CREATE INDEX ir_action_audit_document_idx ON ir_action_audit (document_type, document_id, occurred_at);
