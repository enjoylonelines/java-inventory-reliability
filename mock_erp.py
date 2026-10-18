"""Isolated fault-injectable mock, not an actual ERP API."""
import json
import socket
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

db = sqlite3.connect('/data/mock.sqlite', check_same_thread=False)
db.executescript('''
CREATE TABLE IF NOT EXISTS applied (id INTEGER PRIMARY KEY, request_key TEXT NOT NULL, idempotent INTEGER NOT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS exactly_once_mock ON applied(request_key) WHERE idempotent=1;
CREATE TABLE IF NOT EXISTS faults (request_key TEXT PRIMARY KEY);
''')
if 'payload' not in {column[1] for column in db.execute('PRAGMA table_info(applied)')}:
    db.execute('ALTER TABLE applied ADD COLUMN payload TEXT')
db.commit()
lock = threading.Lock()

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def reply(self, code, value):
        data = json.dumps(value).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path != '/state':
            self.reply(404, {})
            return
        with lock:
            rows = db.execute('SELECT request_key, count(*) FROM applied GROUP BY request_key').fetchall()
            payloads = db.execute('SELECT request_key, payload FROM applied WHERE payload IS NOT NULL GROUP BY request_key').fetchall()
        self.reply(200, {'applied': dict(rows), 'payloads': {key: json.loads(payload) for key, payload in payloads}})

    def do_POST(self):
        if self.path != '/apply':
            self.reply(404, {})
            return
        value = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        key, fault = value['key'], value['fault']
        with lock:
            first = db.execute('SELECT 1 FROM faults WHERE request_key=?', (key,)).fetchone() is None
            if fault in ('before-once', 'lost-once', 'delay-once') and first:
                db.execute('INSERT INTO faults(request_key) VALUES (?)', (key,))
            if fault == 'always-fail' or (fault == 'before-once' and first):
                db.commit()
                self.reply(503, {'error': 'injected failure'})
                return
            payload = json.dumps(value['payload'], sort_keys=True, separators=(',', ':'))
            if value['idempotent']:
                prior = db.execute('SELECT payload FROM applied WHERE request_key=? AND idempotent=1', (key,)).fetchone()
                if prior and prior[0] != payload:
                    self.reply(409, {'error': 'idempotency key payload conflict'})
                    return
            db.execute('INSERT OR IGNORE INTO applied(request_key,idempotent,payload) VALUES (?,?,?)', (key, int(value['idempotent']), payload))
            db.commit()
        if fault == 'lost-once' and first:
            self.connection.shutdown(socket.SHUT_RDWR)
            self.connection.close()
            return
        if fault == 'delay-once' and first:
            time.sleep(3)
        if fault == 'slow-success':
            time.sleep(1.5)
        self.reply(200, {'success': True})

ThreadingHTTPServer(('0.0.0.0', 8080), Handler).serve_forever()
