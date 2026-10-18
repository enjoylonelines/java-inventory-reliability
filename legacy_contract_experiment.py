#!/usr/bin/env python3
"""Execute the baseline inventory writer against a minimal isolated fixture."""
import datetime
import hashlib
import json
import os
import uuid
import experiment as exp
from inv_db import sql

run = 'legacy-' + uuid.uuid4().hex[:12]
existing = sql("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ('transactions','inventory','transaction_items');")
if existing == '0':
    with open('legacy_fixture.sql') as handle:
        sql(handle.read())
elif existing != '3':
    raise RuntimeError('unexpected partial fixture: refusing to alter tables')
function_exists = sql("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname='create_transaction';")
rpc_path = 'baseline_inventory_writer.sql'
if function_exists == '0':
    with open(rpc_path) as handle:
        sql(handle.read())
elif function_exists != '1':
    raise RuntimeError('unexpected function overload: refusing to replace it')
with open(rpc_path) as handle:
    source_text = handle.read()
expected_body = source_text.split('AS $$', 1)[1].split('$$;', 1)[0].strip()
actual_body = sql("SELECT p.prosrc FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname='create_transaction';").strip()
if actual_body != expected_body:
    raise RuntimeError('existing RPC body differs from repository source: refusing to test')

warehouse, option, user = (str(uuid.uuid4()) for _ in range(3))
header = {'type': 'in', 'warehouse_id': warehouse, 'user_id': user, 'memo': 'fixture'}
items = [{'option_id': option, 'quantity': 8}]
def json_sql(value):
    return "'" + json.dumps(value).replace("'", "''") + "'::jsonb"
legacy_result = json.loads(sql('SELECT public.create_transaction(' + json_sql(header) + ',' + json_sql(items) + ')::text;'))
def stock():
    return float(sql("SELECT quantity FROM public.inventory WHERE product_option_id='" + option + "'::uuid AND warehouse_id='" + warehouse + "'::uuid;"))
def transaction_count():
    return int(sql("SELECT count(*) FROM public.transaction_items WHERE product_option_id='" + option + "'::uuid;"))
assert stock() == 8 and transaction_count() == 1
body = {'type': 'out', 'warehouse_id': warehouse, 'user_id': user, 'memo': 'fixture',
        'items': [{'option_id': option, 'quantity': 3}]}
key = run + '-out'
headers = {'X-Request-Id': key, 'X-Inventory-Canonical-Writer': 'true'}
status, first, _ = exp.request(exp.APP + '/api/transactions', body, headers)
status2, replay, _ = exp.request(exp.APP + '/api/transactions', body, headers)
assert status == status2 == 200 and first == replay and stock() == 5 and transaction_count() == 2
local, remote = exp.wait_for(lambda l,e: e.get(key) == 1 and any(o['request_key'] == key and o['status'] == 'DONE' for o in l['outbox']))
assert sum(t['request_key'] == key for t in local['transactions']) == 1
failed_key = run + '-fail-after-writer'
failed_body = {**body, 'type': 'in', 'items': [{'option_id': option, 'quantity': 7}]}
failed_status, _, _ = exp.request(exp.APP + '/api/transactions', failed_body,
    {'X-Request-Id': failed_key, 'X-Inventory-Canonical-Writer': 'true', 'X-Inventory-Fault': 'db-after-writer'})
local, remote = exp.state()
assert failed_status == 502 and stock() == 5 and transaction_count() == 2
assert not any(t['request_key'] == failed_key for t in local['transactions'])
assert not any(o['request_key'] == failed_key for o in local['outbox'])
assert remote.get(failed_key, 0) == 0
retry_status, _, _ = exp.request(exp.APP + '/api/transactions', failed_body,
    {'X-Request-Id': failed_key, 'X-Inventory-Canonical-Writer': 'true'})
assert retry_status == 200 and stock() == 12 and transaction_count() == 3
exp.wait_for(lambda l,e: e.get(failed_key) == 1 and any(o['request_key'] == failed_key and o['status'] == 'DONE' for o in l['outbox']))
with open(rpc_path, 'rb') as handle:
    source_hash = hashlib.sha256(handle.read()).hexdigest()
result = {'run_id': run, 'timestamp_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'scope': 'baseline inventory writer on minimal synthetic PostgreSQL schema; not production schema or authentication',
    'rpc_source': rpc_path, 'rpc_sha256': source_hash, 'legacy_receipt': legacy_result,
    'stock_after_legacy_in': 8, 'java_out_status': status, 'java_out_receipt': first,
    'replay_receipt': replay, 'stock_after_java_out_and_replay': 5,
    'rows_after_java_out_and_replay': 2, 'remote_applications_after_replay': 1,
    'injected_after_canonical_writer_status': failed_status, 'stock_after_injected_rollback': 5,
    'rows_after_injected_rollback': 2, 'inv_and_outbox_rows_after_rollback': 0,
    'retry_status': retry_status, 'stock_after_retry': 12, 'rows_after_retry': 3}
os.makedirs('evidence', exist_ok=True)
path = 'evidence/' + run + '.json'
with open(path, 'w') as handle:
    json.dump(result, handle, indent=2, ensure_ascii=False)
    handle.write('\n')
print(json.dumps(result, ensure_ascii=False), flush=True)
print('EVIDENCE:', path, flush=True)
