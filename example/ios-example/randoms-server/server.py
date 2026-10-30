#!/usr/bin/env python3
"""Send random numbers to an ActivityKit widget through notification.dev's server API."""

import argparse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
from pathlib import Path
import secrets
import signal
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class APIError(Exception):
    def __init__(self, code, retryable=False, retry_after=0):
        super().__init__(code)
        self.retryable = retryable
        self.retry_after = retry_after


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class NotificationAPI:
    def __init__(self, base_url, project_id, api_key, stop):
        self.base_url = base_url.rstrip('/')
        self.project_id = project_id
        self.api_key = api_key
        self.stop = stop
        self.http = urllib.request.build_opener(NoRedirect())

    def send(self, activity_id, suffix, payload):
        # Retries use identical bytes and the same idempotency key.
        body = json.dumps(payload, separators=(',', ':')).encode()
        path = '/v1/projects/{}/live-activities/{}/{}'.format(
            urllib.parse.quote(self.project_id, safe=''),
            urllib.parse.quote(activity_id, safe=''), suffix)
        request = urllib.request.Request(self.base_url + path, data=body, headers={
            'Authorization': 'Bearer ' + self.api_key,
            'Content-Type': 'application/json',
            'Idempotency-Key': str(uuid.uuid4()),
        }, method='POST')
        for attempt in range(4):
            if self.stop.is_set():
                raise APIError('STOPPED')
            try:
                with self.http.open(request, timeout=10) as response:
                    return json.load(response)
            except urllib.error.HTTPError as error:
                try:
                    code = json.load(error).get('code', 'HTTP_' + str(error.code))
                except (ValueError, AttributeError):
                    code = 'HTTP_' + str(error.code)
                try:
                    retry_after = min(30, max(0, float(error.headers.get('Retry-After', 0))))
                except ValueError:
                    retry_after = 0
                failure = APIError(code, error.code in (408, 429) or error.code >= 500, retry_after)
                error.close()
            except (urllib.error.URLError, TimeoutError, OSError):
                failure = APIError('NETWORK', retryable=True)
            if not failure.retryable or attempt == 3:
                raise failure
            self.stop.wait(max(failure.retry_after, 2 ** attempt))
        raise APIError('STOPPED')


class Randoms:
    def __init__(self, api, provider_id, subscriber_id, interval=2, activity_id=None):
        self.api = api
        self.provider_id = provider_id
        self.subscriber_id = subscriber_id
        self.interval = interval
        self.activity_id = activity_id or 'randoms-' + uuid.uuid4().hex[:12]
        self.resume = activity_id is not None
        self.lock = threading.Lock()
        self.state = {'name': 'randoms', 'activityId': self.activity_id, 'intervalSeconds': interval,
                      'number': None, 'sequence': 0, 'acceptedCount': 0, 'lastAcceptedAt': None,
                      'lastError': None, 'running': False}

    def snapshot(self):
        with self.lock:
            return dict(self.state)

    def publish(self, first=False):
        number = secrets.randbelow(1_000_000)
        sequence = self.snapshot()['sequence'] + 1
        content = {'data': {'number': number, 'sequence': sequence}}
        if first:
            suffix = 'start'
            payload = {'activityType': 'DefaultLiveActivityAttributes',
                       'providerConfigurationId': self.provider_id,
                       'audience': {'type': 'subscribers', 'subscriberIds': [self.subscriber_id]},
                       'eventAttributes': {'data': {'name': 'randoms'}},
                       'eventUpdates': content,
                       'alert': {'title': 'randoms', 'body': 'Random numbers from notification.dev'},
                       'priority': 10}
        else:
            suffix = 'notifications'
            payload = {'event': 'update', 'eventUpdates': content, 'priority': 10}
        self.api.send(self.activity_id, suffix, payload)
        accepted_at = datetime.now(timezone.utc).isoformat()
        with self.lock:
            self.state.update(number=number, sequence=sequence, lastAcceptedAt=accepted_at,
                              acceptedCount=self.state['acceptedCount'] + 1, lastError=None)
        print(f'{accepted_at} randoms #{sequence}: {number}', flush=True)

    def run(self):
        with self.lock:
            self.state['running'] = True
        first = not self.resume
        try:
            while not self.api.stop.is_set():
                deadline = time.monotonic() + self.interval
                try:
                    self.publish(first=first)
                    first = False
                except APIError as error:
                    with self.lock:
                        self.state['lastError'] = str(error)
                    print('notification.dev rejected request: ' + str(error), flush=True)
                    # A failed start is ambiguous: never start a second instance automatically.
                    if first or not error.retryable:
                        break
                # Skip missed ticks rather than accumulating stale random values.
                self.api.stop.wait(max(0, deadline - time.monotonic()))
        finally:
            with self.lock:
                self.state['running'] = False


def handler_for(randoms):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path not in ('/', '/status', '/health'):
                self.send_error(404)
                return
            payload = json.dumps(randoms.snapshot()).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *_args):
            pass
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, help='JSON configuration file; keep API keys outside Git')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--interval', type=float, default=2)
    parser.add_argument('--activity-id', help='Resume updates to an existing activity instead of starting a new one')
    args = parser.parse_args()
    config = json.loads(args.config.read_text()) if args.config else {}
    for key in ('base_url', 'project_id', 'provider_id', 'subscriber_id', 'api_key'):
        config[key] = os.environ.get('NOTIFICATION_DEV_' + key.upper(), config.get(key))
        if not config[key]:
            parser.error('Missing NOTIFICATION_DEV_' + key.upper())
    url = urllib.parse.urlsplit(config['base_url'])
    if url.username or url.password or url.query or url.fragment or not url.hostname:
        parser.error('Invalid API base URL')
    if url.scheme != 'https' and not (url.scheme == 'http' and url.hostname in ('localhost', '127.0.0.1', '::1')):
        parser.error('Use HTTPS, or HTTP on localhost for development')
    if not math.isfinite(args.interval) or args.interval < 0.1:
        parser.error('Interval must be at least 0.1 seconds')
    stop = threading.Event()
    api = NotificationAPI(config['base_url'], config['project_id'], config['api_key'], stop)
    randoms = Randoms(api, config['provider_id'], config['subscriber_id'], args.interval, args.activity_id)
    server = ThreadingHTTPServer(('127.0.0.1', args.port), handler_for(randoms))
    server.timeout = 0.5
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    worker = threading.Thread(target=randoms.run, daemon=True)
    worker.start()
    print(f'Status: http://127.0.0.1:{args.port}/status; activity: {randoms.activity_id}', flush=True)
    try:
        while not stop.is_set():
            server.handle_request()
    finally:
        stop.set()
        server.server_close()
        worker.join(timeout=12)


if __name__ == '__main__':
    main()
