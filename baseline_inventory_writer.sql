-- create_transaction RPC: p_items를 jsonb[] 대신 jsonb로 받기
-- JSON 배열을 단일 jsonb 인자로 전달하는 계약을 사용한다.

CREATE OR REPLACE FUNCTION public.create_transaction(p_header jsonb, p_items jsonb)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_transaction_id uuid;
  v_type text;
  v_warehouse_id uuid;
  v_user_id uuid;
  v_supplier_id uuid;
  v_memo text;
  v_created_at timestamptz;
  v_item jsonb;
  v_option_id uuid;
  v_qty numeric;
  v_product_type text;
  v_signed_qty numeric;
  v_inv_id uuid;
  v_inv_quantity numeric;
  v_inv_weight_grams integer;
  v_new_qty numeric;
  v_new_weight integer;
BEGIN
  v_type := lower((p_header->>'type')::text);
  v_user_id := (p_header->>'user_id')::uuid;
  v_supplier_id := (p_header->>'supplier_id')::uuid;
  v_memo := (p_header->>'memo')::text;
  v_created_at := COALESCE((p_header->>'created_at')::timestamptz, now());

  IF v_type = 'in' THEN
    v_warehouse_id := (p_header->>'warehouse_id')::uuid;
  ELSE
    v_warehouse_id := (p_header->>'warehouse_id')::uuid;
  END IF;

  -- INSERT transactions
  INSERT INTO public.transactions (type, user_id, supplier_id, memo, created_at, to_warehouse_id, from_warehouse_id)
  VALUES (
    v_type,
    v_user_id,
    v_supplier_id,
    v_memo,
    v_created_at,
    CASE WHEN v_type = 'in' THEN v_warehouse_id ELSE NULL END,
    CASE WHEN v_type = 'out' THEN v_warehouse_id ELSE NULL END
  )
  RETURNING id INTO v_transaction_id;

  -- LOOP items (p_items는 jsonb 배열 하나로 전달됨)
  FOR v_item IN SELECT * FROM jsonb_array_elements(p_items)
  LOOP
    v_option_id := (v_item->>'option_id')::uuid;
    v_qty := (v_item->>'quantity')::numeric;
    v_product_type := COALESCE(v_item->>'product_type', 'company');
    v_signed_qty := CASE WHEN v_type = 'out' THEN -v_qty ELSE v_qty END;

    SELECT id, quantity, weight_in_grams
    INTO v_inv_id, v_inv_quantity, v_inv_weight_grams
    FROM public.inventory
    WHERE product_option_id = v_option_id AND warehouse_id = v_warehouse_id
    LIMIT 1;

    IF v_product_type = 'material' THEN
      v_new_weight := COALESCE(v_inv_weight_grams, 0) + v_signed_qty::integer;
      IF v_inv_id IS NOT NULL THEN
        UPDATE public.inventory SET weight_in_grams = v_new_weight, updated_at = now() WHERE id = v_inv_id;
      ELSE
        INSERT INTO public.inventory (product_option_id, warehouse_id, weight_in_grams, quantity)
        VALUES (v_option_id, v_warehouse_id, v_new_weight, NULL);
      END IF;
    ELSE
      v_new_qty := COALESCE(v_inv_quantity, 0) + v_signed_qty;
      IF v_inv_id IS NOT NULL THEN
        UPDATE public.inventory SET quantity = v_new_qty, updated_at = now() WHERE id = v_inv_id;
      ELSE
        INSERT INTO public.inventory (product_option_id, warehouse_id, quantity)
        VALUES (v_option_id, v_warehouse_id, v_new_qty);
      END IF;
    END IF;

    INSERT INTO public.transaction_items (transaction_id, product_option_id, quantity)
    VALUES (v_transaction_id, v_option_id, v_qty);
  END LOOP;

  RETURN jsonb_build_object('transaction_id', v_transaction_id, 'success', true);
END;
$$;

COMMENT ON FUNCTION public.create_transaction(jsonb, jsonb) IS '입출고 거래 1건 처리. p_header: type, user_id, warehouse_id, supplier_id?, memo?, created_at?. p_items: jsonb 배열 [{ option_id, quantity, product_type? }]';
