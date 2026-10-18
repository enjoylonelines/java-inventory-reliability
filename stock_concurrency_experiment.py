#!/usr/bin/env python3
"""Compare the copied historical RPC and a participating Java SKU guard on synthetic inventory."""
import collections
import concurrent.futures
import argparse
import datetime
import json
import os
import subprocess
import time
import uuid
import experiment as exp
from inv_db import sql

parser = argparse.ArgumentParser()
parser.add_argument('--multi', action='store_true', help='route through the two-instance Nginx proxy')
args = parser.parse_args()
target = 'http://127.0.0.1:18188' if args.multi else exp.APP
run = 'stock-' + uuid.uuid4().hex[:12]
warehouse, user = str(uuid.uuid4()), str(uuid.uuid4())
output = {'run_id': run, 'timestamp_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'scope': 'synthetic PostgreSQL fixture with the repository RPC; not production traffic or deployed DB',
    'workload': {'rounds': 3, 'initial_stock': 60, 'orders': 100, 'quantity_each': 1, 'client_concurrency': 30,
                 'before': 'Java baseline invokes copied RPC without the Java stock guard',
                 'after': 'Java outbox path invokes the same RPC after participant-only SKU locking and stock check',
                 'external': 'independent idempotent mock ERP', 'http_target': target,
                 'limit': 'only callers taking the same advisory locks are protected'}, 'rounds': []}
output['source_sha256'] = {name: exp.hashlib.sha256(open(name, 'rb').read()).hexdigest() for name in
    ['src/main/java/com/inventory/InventoryService.java', 'src/main/java/com/inventory/InventoryController.java',
     'stock_concurrency_experiment.py', 'baseline_inventory_writer.sql']}
output['image_ids'] = {name: subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', name], text=True).strip()
    for name in ['inventory-reliability-app:latest', 'postgres:17', 'python:3.12-slim']}

def run_arm(mode, round_no):
    option = str(uuid.uuid4())
    header = {'type': 'in', 'warehouse_id': warehouse, 'user_id': user}
    items = [{'option_id': option, 'quantity': 60}]
    def literal(value):
        return "'" + json.dumps(value).replace("'", "''") + "'::jsonb"
    sql('SELECT public.create_transaction(' + literal(header) + ',' + literal(items) + ')::text;')
    body = {'type': 'out', 'warehouse_id': warehouse, 'user_id': user,
            'items': [{'option_id': option, 'quantity': 1}]}
    keys = [f'{run}-r{round_no}-{mode}-{i}' for i in range(100)]
    def order(key):
        status, response, latency = exp.request(target + '/api/transactions', body,
            {'X-Request-Id': key, 'X-Inventory-Canonical-Writer': 'true', 'X-Inventory-Mode': mode}, timeout=30)
        return {'key': key, 'status': status, 'response': response, 'latency_ms': latency}
    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=30) as pool:
        samples = list(pool.map(order, keys))
    request_wall = round((time.monotonic() - started)*1000, 3)
    statuses = collections.Counter(s['status'] for s in samples)
    accepted_keys = {s['key'] for s in samples if s['status'] == 200}
    local, remote = exp.wait_for(lambda l,e: all(e.get(k) == 1 for k in accepted_keys), timeout=60)
    actual_stock = float(sql("SELECT quantity FROM public.inventory WHERE warehouse_id='" + warehouse + "'::uuid AND product_option_id='" + option + "'::uuid;"))
    rows = int(sql("SELECT count(*) FROM public.transaction_items WHERE product_option_id='" + option + "'::uuid;"))
    success = len(accepted_keys)
    persisted_orders = rows - 1
    expected_stock = 60 - persisted_orders
    summary = {'status_counts': {str(k):v for k,v in statuses.items()}, 'accepted_orders': success,
        'rejected_insufficient': sum(s['status'] == 409 and s['response'].get('error') == 'insufficient stock' for s in samples),
        'accepted_over_available_stock': max(0, success-60), 'persisted_orders': persisted_orders,
        'persisted_orders_over_available_stock': max(0, persisted_orders-60), 'final_stock': actual_stock,
        'expected_stock_from_persisted_orders': expected_stock, 'stock_arithmetic_mismatch': actual_stock != expected_stock,
        'item_rows_including_seed': rows, 'external_applications': sum(remote.get(k,0) for k in accepted_keys),
        'request_wall_ms': request_wall, 'p95_request_ms': exp.percentile([s['latency_ms'] for s in samples], .95)}
    return {'summary': summary, 'requests': samples, 'option_id': option}

os.makedirs('evidence', exist_ok=True)
path = 'evidence/' + run + '.json'
def save():
    with open(path, 'w') as handle:
        json.dump(output, handle, indent=2, ensure_ascii=False)
        handle.write('\n')
for round_no in range(3):
    result = {'baseline': run_arm('baseline', round_no)}
    output['rounds'].append(result)
    save()
    result['outbox'] = run_arm('outbox', round_no)
    save()
    after = result['outbox']['summary']
    print(json.dumps({'round': round_no + 1, **{k:v['summary'] for k,v in result.items()}}, ensure_ascii=False), flush=True)
    assert after['accepted_orders'] == 60 and after['rejected_insufficient'] == 40 and after['final_stock'] == 0
    assert not after['stock_arithmetic_mismatch'] and after['external_applications'] == 60
if args.multi:
    logs = subprocess.check_output(['docker-compose', '-p', 'inventory-reliability', 'logs', '--no-color', 'proxy-two'], text=True)
    upstreams = collections.Counter()
    for line in logs.splitlines():
        if run + '-r' in line:
            fields = line.split('|', 1)[-1].strip().split()
            if len(fields) >= 3 and fields[0].startswith(run + '-r'):
                upstreams[fields[1]] += 1
    output['proxy_upstream_request_counts'] = dict(upstreams)
    save()
    assert len(upstreams) == 2 and sum(upstreams.values()) == 600
print('EVIDENCE:', path, flush=True)
