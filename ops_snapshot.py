#!/usr/bin/env python3
"""One local actuator snapshot; not a stability or capacity benchmark."""
import datetime
import json
import os
import subprocess
import uuid
import experiment as exp

run = 'ops-' + uuid.uuid4().hex[:12]
result = {'run_id': run, 'timestamp_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'scope': 'single point in time, local demo only', 'instances': {}}
for name, port in [('app1', 18184), ('app2', 18186)]:
    result['instances'][name] = {}
    for metric in ['health', 'metrics/jvm.memory.used', 'metrics/hikaricp.connections.active', 'metrics/hikaricp.connections.max']:
        status, data, _ = exp.request(f'http://127.0.0.1:{port}/actuator/{metric}')
        assert status == 200
        result['instances'][name][metric] = data
logs = subprocess.check_output(['docker-compose', '-p', 'inventory-reliability', 'logs', '--no-color', '--tail=40', 'app'], text=True)
events = []
for line in logs.splitlines():
    try:
        item = json.loads(line.split('|', 1)[-1].strip())
        if item.get('message') == 'outbox transition' and 'event_id' in item and 'state' in item:
            events.append(item)
    except json.JSONDecodeError:
        pass
assert events
result['structured_outbox_log_examples'] = events[-3:]
os.makedirs('evidence', exist_ok=True)
path = 'evidence/' + run + '.json'
with open(path, 'w') as handle:
    json.dump(result, handle, indent=2, ensure_ascii=False)
    handle.write('\n')
print('EVIDENCE:', path, flush=True)
