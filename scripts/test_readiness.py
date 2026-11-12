"""Actual loopback sockets reproduce the startup race from the container restart."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import socket
import struct
import threading
import unittest
from urllib.request import build_opener
from readiness import wait_for_ready

class ReadinessTest(unittest.TestCase):
    def server(self, recover):
        class Handler(BaseHTTPRequestHandler):
            calls = 0
            def do_GET(self):
                Handler.calls += 1
                if Handler.calls == 1:
                    self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
                    self.connection.close()
                    return
                ready = recover and Handler.calls >= 3
                self.send_response(200 if ready else 503)
                self.end_headers()
                self.wfile.write(b'{"status":"UP"}' if ready else b'{"status":"DOWN"}')
            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(thread.join, 2)
        self.addCleanup(server.shutdown)
        return "http://127.0.0.1:" + str(server.server_port), Handler

    def test_socket_reset_then_503_recovers_before_deadline(self):
        url, handler = self.server(True)
        wait_for_ready(build_opener(), url, timeout=2, interval=0.01)
        self.assertEqual(handler.calls, 3)

    def test_never_ready_fails_after_bounded_retries(self):
        url, handler = self.server(False)
        with self.assertRaisesRegex(SystemExit, "did not recover"):
            wait_for_ready(build_opener(), url, timeout=0.1, interval=0.01)
        self.assertGreaterEqual(handler.calls, 2)

if __name__ == "__main__":
    unittest.main()
