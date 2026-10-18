#!/usr/bin/env python3
"""Additive PostgreSQL schema compatibility test on the isolated demo database."""
import datetime
import json
import os
import uuid
import experiment as exp
from inv_db import sql

def snapshot():
    return json.loads(sql("SELECT json_build_object('rows',count(*),'content_hash',md5(coalesce(string_agg(id::text || ':' || fingerprint,',' ORDER BY id::text),''))) FROM inv_transactions;"))

run = 'migration-' + uuid.uuid4().hex[:12]
before = snapshot()
sql('BEGIN; ALTER TABLE inv_transactions ADD COLUMN migration_rollback_probe TEXT; ROLLBACK;')
probe = sql("SELECT count(*) FROM information_schema.columns WHERE table_name='inv_transactions' AND column_name='migration_rollback_probe';")
assert probe == '0' and snapshot() == before
with open('migrations/001_add_source_version.sql') as handle:
    sql(handle.read())
after = snapshot()
assert before == after
key = run + '-old-insert'
status, body, _ = exp.request(exp.APP + '/api/transactions', exp.BODY, {'X-Request-Id': key})
assert status == 200
version = sql("SELECT source_version FROM inv_transactions WHERE request_key='" + key + "';")
assert version == 'legacy'
result = {'run_id': run, 'timestamp_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'scope': 'isolated demo PostgreSQL table, not a production database migration',
    'before': before, 'after': after, 'old_rows_preserved': before == after,
    'transactional_ddl_rollback_probe_absent': probe == '0', 'old_insert_status': status,
    'old_insert_response': body, 'new_column_default': version}
os.makedirs('evidence', exist_ok=True)
path = 'evidence/' + run + '.json'
with open(path, 'w') as handle:
    json.dump(result, handle, indent=2, ensure_ascii=False)
    handle.write('\n')
print(json.dumps(result, ensure_ascii=False), flush=True)
print('EVIDENCE:', path, flush=True)
