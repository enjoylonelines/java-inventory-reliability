#!/usr/bin/env python3
"""Synthetic mapping preflight; catalogue is an isolated local snapshot, not a real ERP."""
import collections
import datetime
import json
import os
import subprocess
import uuid
import experiment as exp
from inv_db import sql

run = 'mapping-' + uuid.uuid4().hex[:12]
output = {'run_id': run, 'timestamp_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'scope': 'local synthetic mapping and catalogue, not an actual ERP integration',
          'workload': {'rounds': 3, 'inputs_per_arm': 60, 'valid': 30,
                       'invalid': {'missing_sku': 10, 'inactive_partner': 10, 'unknown_external_sku': 10},
                       'before': 'mapping preflight disabled', 'after': 'mapping preflight enabled'}, 'rounds': []}
output['source_sha256'] = {name: exp.hashlib.sha256(open(name, 'rb').read()).hexdigest() for name in
    ['src/main/java/com/inventory/InventoryService.java', 'src/main/java/com/inventory/InventoryController.java',
     'src/main/resources/schema.sql', 'mapping_experiment.py']}
output['image_ids'] = {name: subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', name], text=True).strip()
    for name in ['inventory-reliability-app:latest', 'postgres:17', 'python:3.12-slim']}
path = 'evidence/' + run + '.json'
os.makedirs('evidence', exist_ok=True)
def save():
    with open(path, 'w') as handle:
        json.dump(output, handle, indent=2, ensure_ascii=False)
        handle.write('\n')

for n in range(3):
    sku_target, partner_target = f'{run}-r{n}-sku', f'{run}-r{n}-partner'
    statements = [f"INSERT INTO inv_erp_catalog(kind,external_id) VALUES ('sku','{sku_target}'),('partner','{partner_target}');"]
    fixtures = []
    for i in range(60):
        sku, partner = f'{run}-r{n}-sku-{i}', f'{run}-r{n}-partner-{i}'
        problem = 'valid' if i < 30 else ('missing_sku' if i < 40 else ('inactive_partner' if i < 50 else 'unknown_external_sku'))
        if problem != 'missing_sku':
            target = f'{run}-unknown-{i}' if problem == 'unknown_external_sku' else sku_target
            statements.append(f"INSERT INTO inv_mappings(kind,local_id,external_id) VALUES ('sku','{sku}','{target}');")
        active = 'false' if problem == 'inactive_partner' else 'true'
        statements.append(f"INSERT INTO inv_mappings(kind,local_id,external_id,active) VALUES ('partner','{partner}','{partner_target}',{active});")
        fixtures.append({'type': problem, 'body': {'type': 'in', 'warehouse_id': 'mapping-demo-warehouse',
            'user_id': 'mapping-demo-user', 'supplier_id': partner, 'items': [{'option_id': sku, 'quantity': 1}]}})
    sql('\n'.join(statements))
    arms = {}
    for mode in ('baseline', 'outbox'):
        samples = []
        for i, fixture in enumerate(fixtures):
            key = f'{run}-r{n}-{mode}-{i}'
            headers = {'X-Request-Id': key, 'X-Inventory-Mode': mode}
            if mode == 'outbox': headers['X-Inventory-Require-Mapping'] = 'true'
            status, result, latency = exp.request(exp.APP + '/api/transactions', fixture['body'], headers)
            samples.append({'key': key, 'fixture': fixture['type'], 'status': status, 'response': result, 'latency_ms': latency})
        valid_keys = [s['key'] for s in samples if s['fixture'] == 'valid']
        if mode == 'outbox':
            local, external = exp.wait_for(lambda l,e: all(e.get(k) == 1 and any(o['request_key'] == k and o['status'] == 'DONE' for o in l['outbox']) for k in valid_keys))
        else:
            local, external = exp.state()
        invalid = [s for s in samples if s['fixture'] != 'valid']
        invalid_keys = {s['key'] for s in invalid}
        summary = {'valid_inputs': 30, 'invalid_inputs': 30,
            'valid_accepted': sum(s['status'] == 200 for s in samples if s['fixture'] == 'valid'),
            'invalid_blocked_422': sum(s['status'] == 422 for s in invalid),
            'invalid_local_rows': sum(t['request_key'] in invalid_keys for t in local['transactions']),
            'invalid_outbox_rows': sum(o['request_key'] in invalid_keys for o in local['outbox']),
            'invalid_external_applications': sum(external.get(k, 0) for k in invalid_keys),
            'blocked_by_fixture': dict(collections.Counter(s['fixture'] for s in invalid if s['status'] == 422)),
            'statuses': dict(collections.Counter(str(s['status']) for s in samples))}
        arms[mode] = {'summary': summary, 'requests': samples}
    output['rounds'].append(arms)
    save()
    before, after = arms['baseline']['summary'], arms['outbox']['summary']
    print(json.dumps({'round': n+1, 'baseline': before, 'outbox': after}), flush=True)
    assert before['valid_accepted'] == 30 and before['invalid_local_rows'] == 30 and before['invalid_external_applications'] == 30
    assert after['valid_accepted'] == 30 and after['invalid_blocked_422'] == 30
    assert after['invalid_local_rows'] == after['invalid_outbox_rows'] == after['invalid_external_applications'] == 0
print('EVIDENCE:', path, flush=True)
