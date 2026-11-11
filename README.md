# SlotSync

Configurable room and equipment booking with transactional conflict prevention.

**Status: runnable requester booking slice; configuration and approval tools are in progress.**

Java 21 / Spring Boot 3.5.16 serves a React/TypeScript resource screen from the
same origin. Flyway creates a PostgreSQL 17 schema and three synthetic room/kit
fixtures. The database enforces half-open, timezone-aware, non-overlapping active
booking intervals, including concurrent submissions. Requesters can sign in,
create/list/cancel their own bookings and safely retry an unchanged request.
Session cookies, CSRF protection, BCrypt account hashes, role/ownership checks
and transactional audit/idempotency records are implemented.

Seeded requester/approver/admin accounts are available; approver/admin booking
writes are denied. Policy configuration, approval decisions, CSV imports and the
full role-based UAT remain subsequent core slices.

See [the implementation plan](docs/IMPLEMENTATION_PLAN.md) for the architecture,
task sequence and release acceptance. The core demo target is **17 November 2026**, followed by feature delivery and
hardening through **31 December 2026**.
Features become implemented only when their tests and demonstration pass.

## Run the current demo

Prerequisites: Docker Desktop/Engine with Compose v2, and Python 3.10+ for local
secret generation. No host Java/Node installation is needed for the Docker demo.

```sh
python scripts/init_demo.py
docker compose up --build --wait --wait-timeout 180
```

Open **http://localhost:8080**. Filter the three synthetic resources by room or
equipment. Open **My bookings / Sign in**, then read your generated credentials
locally from ignored `.secrets/demo-users.json`. Use `requester` or `requester2`
to book. All form/display times are explicitly UTC. Pending bookings hold the
slot; cancellation frees it. Initial image/dependency downloads may take
several minutes. Only the app's port is bound to localhost; PostgreSQL has no
published port. The generated `.secrets/` directory is ignored by Git and excluded
from the build context. Keep it with the database volume across restarts.

```sh
curl http://localhost:8080/api/resources
curl http://localhost:8080/actuator/health/readiness
docker compose down
```

See [the runbook](docs/RUNBOOK.md) for checks, development, restart and failure
diagnosis, and [the evidence ledger](docs/CLAIM_EVIDENCE.md) for implemented scope.
The [booking API guide](docs/BOOKING_API.md) documents sessions, request keys,
conflict responses and the current fixed validation limits.

## Checks

With Java 21 and Docker, the pinned Maven wrapper runs the real PostgreSQL tests
through Testcontainers (no H2 or skip-if-Docker-is-missing substitution):

```sh
./mvnw -B -ntp verify
```

On Windows use `mvnw.cmd`. Without a host JDK, use the isolated Compose test path:

```sh
python scripts/init_demo.py
docker compose -f compose.test.yml up --abort-on-container-exit --exit-code-from check
docker compose -f compose.test.yml down
```

With Node 24.14+:

```sh
cd frontend
npm ci
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

The browser suite needs the running Docker demo. GitHub Actions runs both the
Testcontainers suite and the browser against the same-origin Docker application.

## Intended complete core demo (not delivered yet)

1. Configure a campus room and its booking policy.
2. Preview a resource CSV and inspect row-level validation errors.
3. Submit two conflicting reservations and see one rejected by PostgreSQL.
4. Approve a valid request, then cancel and rebook the slot.
5. Inspect the audit trail and executed acceptance cases.

No release tag has been earned yet. The catalogue foundation alone does not
satisfy the complete core contract or all planned resume claims.
