#!/usr/bin/env python3
"""Local proxy comparison. Both arms keep the same two background workers running."""
import concurrent.futures
import datetime
import json
import os
import platform
import subprocess
import time
import uuid
import experiment as exp

run = 'scale-' + uuid.uuid4().hex[:12]
output = {'run_id': run, 'timestamp_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'host': platform.platform(), 'workload': {'rounds': 5, 'requests_per_arm': 100, 'warmup_requests_per_arm': 100, 'concurrency': 20,
          'fault': 'none', 'client_retries': 0, 'proxy': 'nginx in both arms',
          'workers': 'two background workers active in both arms',
          'one_arm': 'one HTTP target', 'two_arm': 'two HTTP targets',
          'semantics': 'measures HTTP acceptance, not end-to-end ERP completion'}, 'rounds': []}
output['image_ids'] = {name: subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', name], text=True).strip()
    for name in ['inventory-reliability-app:latest', 'postgres:17', 'python:3.12-slim', 'nginx:1.27-alpine']}
output['source_sha256'] = {name: exp.hashlib.sha256(open(name, 'rb').read()).hexdigest()
    for name in ['scale_experiment.py', 'experiment.py', 'nginx-one.conf', 'nginx-two.conf',
                 'src/main/java/com/inventory/InventoryService.java', 'src/main/java/com/inventory/InventoryController.java']}
os.makedirs('evidence', exist_ok=True)
path = 'evidence/' + run + '.json'
def save():
    with open(path, 'w') as handle:
        json.dump(output, handle, indent=2, ensure_ascii=False)
        handle.write('\n')
save()

def arm(label, port, round_no):
    keys = [f'{run}-{round_no}-{label}-{i}' for i in range(100)]
    def submit(key):
        status, response, elapsed = exp.request(f'http://127.0.0.1:{port}/api/transactions', exp.BODY, {'X-Request-Id': key})
        return {'key': key, 'status': status, 'response': response, 'latency_ms': elapsed}
    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
        samples = list(pool.map(submit, keys))
    elapsed = time.monotonic() - started
    assert all(s['status'] == 200 for s in samples)
    local, remote = exp.wait_for(lambda l,e: all(e.get(k) == 1 and any(o['request_key'] == k and o['status'] == 'DONE' for o in l['outbox']) for k in keys), timeout=60)
    assert all(sum(t['request_key'] == k for t in local['transactions']) == 1 for k in keys)
    latencies = [s['latency_ms'] for s in samples]
    return {'summary': {'requests': len(samples), 'errors': sum(s['status'] != 200 for s in samples),
        'wall_ms': round(elapsed * 1000, 3), 'requests_per_second': round(len(samples) / elapsed, 2),
        'p50_ms': exp.percentile(latencies, .5), 'p95_ms': exp.percentile(latencies, .95),
        'local_duplicate_rows': 0, 'missing_external_keys': 0}, 'requests': samples}

for label, port in [('one', 18187), ('two', 18188)]:
    warmup = arm(label, port, 'warmup')
    print(json.dumps({'warmup': label, 'summary': warmup['summary']}), flush=True)

for round_no in range(5):
    order = [('one', 18187), ('two', 18188)] if round_no % 2 == 0 else [('two', 18188), ('one', 18187)]
    results = {label: arm(label, port, round_no) for label, port in order}
    output['rounds'].append(results)
    save()
    print(json.dumps({'round': round_no + 1, **{label: results[label]['summary'] for label in results}}), flush=True)

shared = run + '-shared-key'
def replay(index):
    return exp.request('http://127.0.0.1:18188/api/transactions', exp.BODY, {'X-Request-Id': shared})
with concurrent.futures.ThreadPoolExecutor(max_workers=25) as pool:
    results = list(pool.map(replay, range(100)))
assert all(status == 200 for status, _, _ in results)
ids = {body['transaction_id'] for _, body, _ in results}
assert len(ids) == 1
local, remote = exp.wait_for(lambda l,e: e.get(shared) == 1 and any(o['request_key'] == shared and o['status'] == 'DONE' for o in l['outbox']))
assert sum(t['request_key'] == shared for t in local['transactions']) == 1
output['same_key_across_instances'] = {'requests': 100, 'distinct_ids': len(ids), 'local_rows': 1, 'external_applications': 1}
save()
print('EVIDENCE:', path, flush=True)
