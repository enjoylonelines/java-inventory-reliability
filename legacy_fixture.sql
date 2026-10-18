-- Minimal synthetic schema for the unchanged historical RPC. Not a production schema copy.
CREATE TABLE public.transactions (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), type TEXT, user_id UUID,
 supplier_id UUID, memo TEXT, created_at TIMESTAMPTZ,
 to_warehouse_id UUID, from_warehouse_id UUID
);
CREATE TABLE public.inventory (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), product_option_id UUID,
 warehouse_id UUID, quantity NUMERIC, weight_in_grams INTEGER, updated_at TIMESTAMPTZ
);
CREATE TABLE public.transaction_items (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), transaction_id UUID,
 product_option_id UUID, quantity NUMERIC
);
