#!/usr/bin/env python3
"""One vs two outbox workers with the same async completion guarantee and shared HTTP endpoint."""
import datetime
import json
import os
import subprocess
import time
import uuid
import experiment as exp

run = 'workers-' + uuid.uuid4().hex[:12]
output = {'run_id': run, 'timestamp_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'scope': 'local shared-host experiment; one or two outbox workers, not production capacity',
    'workload': {'rounds': 3, 'measured_jobs_per_arm': 100, 'warmup_jobs_per_arm': 20,
        'client': 'single app1 endpoint, sequential submissions', 'faults': 'none',
        'completion': 'all outbox rows DONE and all mock applications observed once',
        'worker_poll_fixed_delay_ms': 20, 'order': 'alternates between rounds',
        'shared': 'same PostgreSQL, mock ERP and Docker host in both arms'}, 'rounds': []}
output['source_sha256'] = {name: exp.hashlib.sha256(open(name, 'rb').read()).hexdigest() for name in
    ['src/main/java/com/inventory/InventoryService.java', 'src/main/resources/application.properties', 'worker_experiment.py']}
output['image_ids'] = {name: subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', name], text=True).strip()
    for name in ['inventory-reliability-app:latest', 'postgres:17', 'python:3.12-slim']}
os.makedirs('evidence', exist_ok=True)
path = 'evidence/' + run + '.json'
def save():
    with open(path, 'w') as handle:
        json.dump(output, handle, indent=2, ensure_ascii=False)
        handle.write('\n')

def set_workers(count):
    exp.compose('start' if count == 2 else 'stop', 'app2')
    if count == 2:
        deadline = time.monotonic() + 45
        while True:
            try:
                if exp.request('http://127.0.0.1:18186/actuator/health')[1]['status'] == 'UP': break
            except Exception:
                if time.monotonic() > deadline: raise
                time.sleep(.25)

def batch(count, label):
    keys = [f'{run}-{label}-{i}' for i in range(count)]
    requests = []
    start = time.monotonic()
    for key in keys:
        status, response, latency = exp.request(exp.APP + '/api/transactions', exp.BODY, {'X-Request-Id': key})
        requests.append({'key': key, 'status': status, 'response': response, 'latency_ms': latency})
    ack_ms = (time.monotonic()-start)*1000
    assert all(s['status'] == 200 for s in requests)
    local, remote = exp.wait_for(lambda l,e: all(e.get(k) == 1 and any(o['request_key'] == k and o['status'] == 'DONE' for o in l['outbox']) for k in keys), timeout=75)
    completed_ms = (time.monotonic()-start)*1000
    events = [o for o in local['outbox'] if o['request_key'] in keys]
    assert len(events) == count and all(o['attempts'] == 1 for o in events)
    assert all(sum(t['request_key'] == k for t in local['transactions']) == 1 for k in keys)
    durations = []
    for event in events:
        created = datetime.datetime.fromisoformat(event['created_at'].replace('Z', '+00:00'))
        completed = datetime.datetime.fromisoformat(event['completed_at'].replace('Z', '+00:00'))
        durations.append(round((completed-created).total_seconds()*1000, 3))
    return {'summary': {'jobs': count, 'ack_wall_ms': round(ack_ms, 3),
        'all_done_observed_wall_ms': round(completed_ms, 3),
        'observed_completed_per_second': round(count / (completed_ms/1000), 2),
        'delivery_p50_ms': exp.percentile(durations, .5), 'delivery_p95_ms': exp.percentile(durations, .95),
        'duplicate_local_rows': 0, 'duplicate_external_applications': 0, 'missing_external_keys': 0},
        'requests': requests, 'outbox': events}

try:
    for n in range(3):
        order = [1,2] if n % 2 == 0 else [2,1]
        result = {}
        output['rounds'].append(result)
        for count in order:
            set_workers(count)
            batch(20, f'r{n}-{count}-warmup')
            result[str(count)] = batch(100, f'r{n}-{count}-measured')
            save()
        print(json.dumps({'round': n+1, **{count: result[str(count)]['summary'] for count in order}}), flush=True)
finally:
    exp.compose('stop', 'app2')
save()
print('EVIDENCE:', path, flush=True)
