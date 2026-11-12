"""Bounded HTTP readiness wait for an application process restart."""
from http.client import HTTPException
import json
import time
from urllib.error import HTTPError, URLError

def wait_for_ready(opener, url, timeout=120, interval=1):
    deadline = time.monotonic() + timeout
    while True:
        try:
            with opener.open(url, timeout=min(10, max(0.1, deadline-time.monotonic()))) as response:
                if response.status == 200 and json.load(response).get("status") == "UP":
                    return
        except (URLError, TimeoutError, ConnectionError, HTTPException) as failure:
            # TCP can connect before a restarted container is ready to serve HTTP.
            if isinstance(failure, HTTPError):
                failure.close()
        remaining = deadline-time.monotonic()
        if remaining <= 0:
            raise SystemExit("Application did not recover after restart")
        time.sleep(min(interval, remaining))
