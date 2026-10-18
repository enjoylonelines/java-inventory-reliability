#!/usr/bin/env python3
"""Reproducible isolated experiment. Never targets a real ERP service."""
import argparse
import collections
import concurrent.futures
import datetime
import http.client
import hashlib
import json
import os
import platform
import statistics
import subprocess
import time
import urllib.error
import urllib.request
import uuid

APP = 'http://127.0.0.1:18184'
ERP = 'http://127.0.0.1:18185'
BODY = {'type': 'out', 'warehouse_id': 'demo-warehouse', 'user_id': 'demo-user',
        'items': [{'option_id': 'demo-sku', 'quantity': 1}]}

def request(url, value=None, headers=None, timeout=8):
    data = None if value is None else json.dumps(value).encode()
    req = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json', **(headers or {})})
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            result, status = json.load(response), response.status
    except urllib.error.HTTPError as error:
        raw = error.read().decode('utf-8', errors='replace')
        try:
            result = json.loads(raw)
        except json.JSONDecodeError:
            result = {'non_json_response': raw[:500]}
        status = error.code
    return status, result, round((time.monotonic() - start) * 1000, 3)

def state():
    return request(APP + '/internal/state')[1], request(ERP + '/state')[1]['applied']

def wait_for(predicate, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            local, external = state()
            if predicate(local, external):
                return local, external
        except (urllib.error.URLError, http.client.RemoteDisconnected, ConnectionError, TimeoutError):
            pass
        time.sleep(.15)
    raise AssertionError('timed out waiting for expected experiment state')

def percentile(values, p):
    ordered = sorted(values)
    return ordered[max(0, int((len(ordered) - 1) * p))]

def compose(*args):
    return subprocess.run(['docker-compose', '-p', 'inventory-reliability', *args], check=True, capture_output=True, text=True).stdout

def benchmark(prefix, mode, count):
    samples, keys = [], []
    started = time.monotonic()
    for index in range(count):
        key = f'{prefix}-{index:03}'
        keys.append(key)
        fault = ['none', 'before-once', 'lost-once'][index % 3]
        # Two deliveries for every logical input in both arms, including successful responses.
        for attempt in range(2):
            status, result, elapsed = request(APP + '/api/transactions', BODY,
                {'X-Request-Id': key, 'X-Inventory-Mode': mode, 'X-Inventory-Fault': fault})
            samples.append({'key': key, 'fault': fault, 'attempt': attempt + 1,
                            'status': status, 'response': result, 'latency_ms': elapsed})
    ack_ms = round((time.monotonic() - started) * 1000, 3)
    if mode == 'outbox':
        local, external = wait_for(lambda l, e: all(any(o['request_key'] == k and o['status'] == 'DONE' for o in l['outbox']) for k in keys))
    else:
        local, external = state()
    complete_ms = round((time.monotonic() - started) * 1000, 3)
    local_counts = collections.Counter(t['request_key'] for t in local['transactions'] if t['request_key'] in keys and t['mode'] == mode)
    external_counts = {k: external.get(k, 0) for k in keys}
    events = [o for o in local['outbox'] if o['request_key'] in keys]
    delivery_ms = []
    for event in events:
        if event['completed_at']:
            start = datetime.datetime.fromisoformat(event['created_at'].replace('Z', '+00:00'))
            end = datetime.datetime.fromisoformat(event['completed_at'].replace('Z', '+00:00'))
            delivery_ms.append(round((end - start).total_seconds() * 1000, 3))
    latencies = [s['latency_ms'] for s in samples]
    summary = {'logical_inputs': count, 'client_requests': len(samples),
        'http_statuses': dict(collections.Counter(str(s['status']) for s in samples)),
        'local_rows': sum(local_counts.values()),
        'duplicate_local_rows': sum(max(0, local_counts[k]-1) for k in keys),
        'external_applications': sum(external_counts.values()),
        'duplicate_external_applications': sum(max(0, external_counts[k]-1) for k in keys),
        'missing_external_keys': sum(external_counts[k] == 0 for k in keys),
        'local_external_row_count_mismatches': sum(local_counts[k] != external_counts[k] for k in keys),
        'request_latency_ms_p50': percentile(latencies, .5), 'request_latency_ms_p95': percentile(latencies, .95),
        'all_request_wall_ms': ack_ms, 'observed_all_done_wall_ms': complete_ms,
        'delivery_ms_p50': percentile(delivery_ms, .5) if delivery_ms else None,
        'delivery_ms_p95': percentile(delivery_ms, .95) if delivery_ms else None,
        'outbox_attempts': sum(o['attempts'] for o in events)}
    event_ids = {o['id'] for o in events}
    return {'summary': summary, 'requests': samples, 'local_counts': dict(local_counts),
            'external_counts': external_counts, 'outbox': events,
            'audit': [entry for entry in local['audit'] if entry['event_id'] in event_ids]}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--restart-tests', action='store_true')
    parser.add_argument('--count', type=int, default=30)
    parser.add_argument('--rounds', type=int, default=3)
    args = parser.parse_args()
    run = 'inventory-' + uuid.uuid4().hex[:12]
    output = {'run_id': run, 'timestamp_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'environment': {'host': platform.platform(), 'python': platform.python_version(),
                              'app': 'Java 21/Spring Boot/MyBatis/PostgreSQL 17', 'erp': 'independent Python/SQLite mock'},
              'scenario': {'repeats': args.rounds, 'logical_inputs_per_arm': args.count,
                           'fault_cycle': ['none', 'before-once', 'lost-once'],
                           'client_deliveries_per_input': 2, 'baseline': 'commit then synchronous mock call',
                           'after': 'atomic local transaction plus outbox and idempotent mock'},
              'checks': {}, 'rounds': []}
    source_files = ['pom.xml', 'Dockerfile', 'src/main/java/com/inventory/InventoryApplication.java',
                    'src/main/java/com/inventory/InventoryService.java', 'src/main/java/com/inventory/InventoryController.java',
                    'src/main/java/com/inventory/OutboxMapper.java', 'src/main/resources/application.properties',
                    'src/main/resources/schema.sql', 'mock_erp.py', 'compose.yaml', 'experiment.py']
    output['source_sha256'] = {name: hashlib.sha256(open(name, 'rb').read()).hexdigest() for name in source_files}
    output['image_ids'] = {name: subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', name], text=True).strip()
        for name in ['inventory-reliability-app:latest', 'postgres:17', 'python:3.12-slim']}
    os.makedirs('evidence', exist_ok=True)
    path = os.path.join('evidence', run + '.json')
    def save():
        with open(path, 'w') as handle:
            json.dump(output, handle, indent=2, ensure_ascii=False)
            handle.write('\n')
    save()
    status, error, _ = request(APP + '/api/transactions', {'type': 'out', 'items': []})
    assert status == 400 and error == {'error': '제품 항목이 필요합니다.', 'code': 'BAD_REQUEST', 'statusCode': 400}
    output['checks']['empty_items_contract'] = {'status': status, 'response': error}
    key = run + '-contract'
    headers = {'X-Request-Id': key}
    status, first, _ = request(APP + '/api/transactions', BODY, headers)
    status2, second, _ = request(APP + '/api/transactions', BODY, headers)
    altered = {**BODY, 'items': [{'option_id': 'demo-sku', 'quantity': 2}]}
    conflict, conflict_body, _ = request(APP + '/api/transactions', altered, headers)
    assert status == status2 == 200 and set(first) == {'success', 'transaction_id'} and first == second and conflict == 409
    output['checks']['success_shape_replay_and_key_conflict'] = {'first': first, 'replay': second, 'conflict_status': conflict, 'conflict': conflict_body}
    cross_status, cross_body, _ = request(APP + '/api/transactions', BODY, {'X-Request-Id': key, 'X-Inventory-Mode': 'baseline'})
    assert cross_status == 409
    output['checks']['cross_mode_replay_is_blocked'] = {'status': cross_status, 'response': cross_body}
    rollback_key = run + '-db-rollback'
    failed, _, _ = request(APP + '/api/transactions', BODY, {'X-Request-Id': rollback_key, 'X-Inventory-Fault': 'db-rollback'})
    local, external = state()
    tx_count = sum(t['request_key'] == rollback_key for t in local['transactions'])
    event_count = sum(o['request_key'] == rollback_key for o in local['outbox'])
    assert failed == 502 and tx_count == event_count == 0 and external.get(rollback_key, 0) == 0
    recovered, _, _ = request(APP + '/api/transactions', BODY, {'X-Request-Id': rollback_key})
    assert recovered == 200
    wait_for(lambda l, e: e.get(rollback_key) == 1 and any(o['request_key'] == rollback_key and o['status'] == 'DONE' for o in l['outbox']))
    output['checks']['business_and_outbox_atomic_rollback'] = {'injected_status': failed, 'local_rows_after_failure': tx_count,
        'outbox_rows_after_failure': event_count, 'retry_status': recovered, 'eventually_applied': 1}
    concurrent_key = run + '-concurrent'
    def concurrent_submit(_index):
        return request(APP + '/api/transactions', BODY, {'X-Request-Id': concurrent_key})
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
        parallel = list(pool.map(concurrent_submit, range(40)))
    ids = {result['transaction_id'] for status, result, _ in parallel if status == 200}
    assert len(ids) == 1 and all(status == 200 for status, _, _ in parallel)
    local, external = wait_for(lambda l, e: e.get(concurrent_key) == 1 and any(o['request_key'] == concurrent_key and o['status'] == 'DONE' for o in l['outbox']))
    assert sum(t['request_key'] == concurrent_key for t in local['transactions']) == 1
    erp_snapshot = request(ERP + '/state')[1]
    assert erp_snapshot['payloads'][concurrent_key] == BODY
    output['checks']['concurrent_same_key'] = {'requests': len(parallel), 'status_200': sum(s == 200 for s, _, _ in parallel),
        'distinct_transaction_ids': len(ids), 'local_rows': 1, 'external_applications': external[concurrent_key],
        'payload_preserved': erp_snapshot['payloads'][concurrent_key] == BODY,
        'latency_ms_p95': percentile([elapsed for _, _, elapsed in parallel], .95)}
    save()
    for n in range(args.rounds):
        baseline = benchmark(f'{run}-r{n}-baseline', 'baseline', args.count)
        after = benchmark(f'{run}-r{n}-outbox', 'outbox', args.count)
        output['rounds'].append({'baseline': baseline, 'outbox': after})
        save()
        print(json.dumps({'round': n + 1, 'baseline': baseline['summary'], 'outbox': after['summary']}, ensure_ascii=False), flush=True)
    dead_key = run + '-dead'
    status, _, _ = request(APP + '/api/transactions', BODY, {'X-Request-Id': dead_key, 'X-Inventory-Fault': 'always-fail'})
    assert status == 200
    local, _ = wait_for(lambda l, e: any(o['request_key'] == dead_key and o['status'] == 'DEAD' for o in l['outbox']))
    dead = next(o for o in local['outbox'] if o['request_key'] == dead_key)
    assert dead['attempts'] == 5
    retry_start = time.monotonic()
    _, requeued, _ = request(APP + '/internal/retry-dead', {'key': dead_key})
    assert requeued['requeued'] == 1
    local, external = wait_for(lambda l, e: any(o['request_key'] == dead_key and o['status'] == 'DONE' for o in l['outbox']))
    assert external.get(dead_key) == 1
    output['checks']['dead_letter_manual_recovery'] = {'failed_attempts': dead['attempts'], 'requeued': requeued['requeued'],
        'requeue_to_observed_done_ms': round((time.monotonic() - retry_start) * 1000, 3), 'final_external_count': external[dead_key]}
    status, reconciliation, _ = request(APP + '/internal/reconcile')
    mismatch_keys = {m['request_key'] for m in reconciliation['mismatches']}
    outbox_keys = {key for round_result in output['rounds'] for key in round_result['outbox']['external_counts']}
    baseline_keys = {key for round_result in output['rounds'] for key in round_result['baseline']['external_counts']}
    assert status == 200 and not (mismatch_keys & outbox_keys) and baseline_keys <= mismatch_keys
    output['checks']['reconciliation'] = {'mismatches_total': reconciliation['mismatch_count'],
        'baseline_keys_flagged': len(mismatch_keys & baseline_keys), 'outbox_keys_flagged': len(mismatch_keys & outbox_keys)}
    save()
    if args.restart_tests:
        crash_key = run + '-crash'
        status, _, _ = request(APP + '/api/transactions', BODY, {'X-Request-Id': crash_key, 'X-Inventory-Fault': 'delay-once'})
        assert status == 200
        wait_for(lambda l, e: e.get(crash_key) == 1 and any(o['request_key'] == crash_key and o['status'] == 'PROCESSING' for o in l['outbox']))
        crash_start = time.monotonic()
        compose('kill', '-s', 'SIGKILL', 'app')
        compose('start', 'app')
        deadline = time.monotonic() + 45
        while True:
            try:
                local, external = wait_for(lambda l, e: any(o['request_key'] == crash_key and o['status'] == 'DONE' for o in l['outbox']), timeout=30)
                break
            except urllib.error.URLError:
                if time.monotonic() > deadline: raise
                time.sleep(.25)
        recovered = next(o for o in local['outbox'] if o['request_key'] == crash_key)
        assert external.get(crash_key) == 1 and recovered['attempts'] >= 2
        output['checks']['killed_worker_after_external_commit'] = {'attempts': recovered['attempts'], 'external_count': external[crash_key],
            'kill_to_observed_done_ms': round((time.monotonic() - crash_start) * 1000, 3), 'final_status': recovered['status']}
        graceful_key = run + '-graceful-inflight'
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
                future = pool.submit(request, APP + '/api/transactions', BODY,
                    {'X-Request-Id': graceful_key, 'X-Inventory-Mode': 'baseline', 'X-Inventory-Fault': 'slow-success'})
                wait_for(lambda l,e: e.get(graceful_key) == 1)
                stop_start = time.monotonic()
                compose('stop', '-t', '10', 'app')
                inflight_status, inflight_response, _ = future.result(timeout=8)
                stop_ms = round((time.monotonic() - stop_start) * 1000, 3)
        finally:
            compose('start', 'app')
        assert inflight_status == 200
        wait_for(lambda l,e: e.get(graceful_key) == 1)
        output['checks']['graceful_shutdown_inflight'] = {'response_status': inflight_status, 'response': inflight_response,
            'stop_observed_ms': stop_ms, 'external_count': 1}
        before, _ = state()
        ids = sorted(t['id'] for t in before['transactions'])
        restart_start = time.monotonic()
        compose('restart', 'app')
        deadline = time.monotonic() + 45
        while True:
            try:
                after, _ = state()
                break
            except Exception:
                if time.monotonic() > deadline: raise
                time.sleep(.25)
        assert sorted(t['id'] for t in after['transactions']) == ids
        output['checks']['graceful_restart_data_preserved'] = {'rows_before_and_after': len(ids), 'restart_observed_ms': round((time.monotonic()-restart_start)*1000, 3)}
    save()
    print('EVIDENCE:', path, flush=True)

if __name__ == '__main__':
    main()
