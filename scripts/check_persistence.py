"""Record/compare public-synthetic workflow state across an application restart."""
import argparse
import http.cookiejar
import json
from pathlib import Path
from readiness import wait_for_ready
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, build_opener, HTTPCookieProcessor

parser = argparse.ArgumentParser()
parser.add_argument("--base-url", default="http://127.0.0.1:8080")
parser.add_argument("--record", action="store_true")
args = parser.parse_args()
origin = urlsplit(args.base_url)
if (origin.scheme not in {"http", "https"} or origin.hostname not in {"127.0.0.1", "localhost", "::1"}
        or origin.username or origin.password or origin.path not in {"", "/"} or origin.query or origin.fragment):
    parser.error("Use a loopback origin for this generated-credential demo check")
args.base_url = args.base_url.rstrip("/")
root = Path(__file__).resolve().parents[1]
opener = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
def get(path):
    with opener.open(args.base_url + path, timeout=10) as response:
        return json.load(response)
wait_for_ready(opener, args.base_url + "/actuator/health/readiness")
token = get("/api/session")
password = json.loads((root/".secrets/demo-users.json").read_text())["admin"]
request = Request(args.base_url + "/api/login",
    data=urlencode({"username": "admin", "password": password}).encode(),
    headers={token["csrfHeader"]: token["csrfToken"], "Content-Type": "application/x-www-form-urlencoded"})
with opener.open(request, timeout=10) as response:
    assert response.status == 204
state = {"policy": get("/api/admin/policy"),
         "imports": get("/api/admin/imports"), "audit": get("/api/admin/audit"),
         "resources": get("/api/resources")}
assert any(batch["applied"] and batch["inserted"] == 2 for batch in state["imports"])
assert any(row["action"] == "REJECTED" for row in state["audit"])
assert any(row["action"] == "RESOURCES_IMPORTED" for row in state["audit"])
assert sum(row["code"] == "ROOM-CORE07" for row in state["resources"]) == 1
path = root/"target/workflow-before-restart.json"
if args.record:
    path.write_text(json.dumps(state, indent=2), encoding="utf-8")
    print("Synthetic persisted workflow state recorded before restart")
else:
    assert state == json.loads(path.read_text(encoding="utf-8")), "Workflow state changed across restart"
    (root/"target/workflow-restart.json").write_text(json.dumps({
        "integration": "Actual authenticated HTTP reads before/after application process restart; same database",
        "checks": ["policy unchanged", "import batches/replay state unchanged", "audit IDs/details unchanged", "catalogue unchanged"],
        "resource_count": len(state["resources"]), "applied_imports": sum(b["applied"] for b in state["imports"]), "status": "passed"
    }, indent=2), encoding="utf-8")
    print("Policy/import/audit/catalogue unchanged after application restart")
