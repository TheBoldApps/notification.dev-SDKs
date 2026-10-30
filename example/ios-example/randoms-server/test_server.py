import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import urlopen

from server import APIError, NotificationAPI, Randoms, handler_for


class FakeAPIHandler(BaseHTTPRequestHandler):
    requests = []
    responses = []

    def do_POST(self):
        body = self.rfile.read(int(self.headers['Content-Length']))
        self.requests.append((self.path, dict(self.headers), body))
        status = self.responses.pop(0) if self.responses else 202
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps({'operationId': 'operation'} if status == 202 else {'code': 'TEST_ERROR'}).encode())

    def log_message(self, *_args):
        pass


class ImmediateRetry(threading.Event):
    def wait(self, timeout=None):
        return self.is_set()


class ServerTests(unittest.TestCase):
    def setUp(self):
        FakeAPIHandler.requests = []
        FakeAPIHandler.responses = []
        self.http = ThreadingHTTPServer(('127.0.0.1', 0), FakeAPIHandler)
        self.thread = threading.Thread(target=self.http.serve_forever, daemon=True)
        self.thread.start()
        self.api = NotificationAPI(f'http://127.0.0.1:{self.http.server_port}', 'project', 'test-secret', ImmediateRetry())

    def tearDown(self):
        self.http.shutdown()
        self.http.server_close()
        self.thread.join()

    def test_start_and_update_use_server_key_and_complete_widget_state(self):
        randoms = Randoms(self.api, 'provider', 'subscriber')
        randoms.publish(first=True)
        randoms.publish()
        first, second = FakeAPIHandler.requests
        self.assertTrue(first[0].endswith('/start'))
        self.assertTrue(second[0].endswith('/notifications'))
        self.assertEqual(first[1]['Authorization'], 'Bearer test-secret')
        self.assertNotEqual(first[1]['Idempotency-Key'], second[1]['Idempotency-Key'])
        start = json.loads(first[2])
        update = json.loads(second[2])
        self.assertEqual(start['activityType'], 'DefaultLiveActivityAttributes')
        self.assertEqual(start['eventAttributes'], {'data': {'name': 'randoms'}})
        self.assertEqual(start['audience']['subscriberIds'], ['subscriber'])
        self.assertEqual(update['event'], 'update')
        self.assertEqual(update['eventUpdates']['data']['sequence'], 2)
        self.assertIn(update['eventUpdates']['data']['number'], range(1_000_000))
        self.assertEqual(randoms.snapshot()['acceptedCount'], 2)

    def test_transient_retry_preserves_idempotency_key_and_body(self):
        FakeAPIHandler.responses = [503, 202]
        self.api.send('activity', 'notifications', {'event': 'update'})
        first, second = FakeAPIHandler.requests
        self.assertEqual(first[1]['Idempotency-Key'], second[1]['Idempotency-Key'])
        self.assertEqual(first[2], second[2])

    def test_terminal_failure_does_not_retry(self):
        FakeAPIHandler.responses = [403]
        with self.assertRaises(APIError) as caught:
            self.api.send('activity', 'notifications', {})
        self.assertFalse(caught.exception.retryable)
        self.assertEqual(len(FakeAPIHandler.requests), 1)

    def test_status_endpoint_excludes_credentials(self):
        randoms = Randoms(self.api, 'provider', 'subscriber')
        http = ThreadingHTTPServer(('127.0.0.1', 0), handler_for(randoms))
        thread = threading.Thread(target=http.serve_forever, daemon=True)
        thread.start()
        try:
            with urlopen(f'http://127.0.0.1:{http.server_port}/status') as response:
                text = response.read().decode()
            self.assertNotIn('test-secret', text)
            self.assertNotIn('api_key', text)
            self.assertEqual(json.loads(text)['intervalSeconds'], 2)
        finally:
            http.shutdown()
            http.server_close()
            thread.join()


if __name__ == '__main__':
    unittest.main()
