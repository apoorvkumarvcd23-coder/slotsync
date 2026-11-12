# Configuration, approvals and resource imports (CORE07)

Run the [README quick start](../README.md), then open **My bookings / Sign in**.
Read generated demo credentials locally from ignored .secrets/demo-users.json.
Use admin for settings/imports/audit, approver for decisions, and requester or
requester2 for reservations. Passwords/CSRF values are never part of public examples.

## Policy

GET /api/policy is a public, no-store view: version, maxDurationMinutes and
approvalRequired. GET /api/admin/policy and POST /api/admin/policy require an
enabled administrator session; POST also requires the fresh CSRF header.

POST body:
```json
{"expectedVersion":1,"maxDurationMinutes":120,"approvalRequired":true}
```

Duration must be an integer from 1 to 1440 minutes. approvalRequired must be a
JSON boolean. Nulls, numeric strings, fractional integers and unknown properties
are rejected. A stale expectedVersion returns 409 POLICY_CHANGED; refresh and
review before resubmitting. An unchanged save creates no extra version/audit.

Each changed save creates an immutable policy version and atomic POLICY_CHANGED
audit event. A new booking locks/reads the current version and stores its FK.
Existing reservations keep their version/status; no retrospective cancellation.
The initial policy is 1440 minutes with approval required. V4 assigns that initial
version to older bookings; V1–V3 migration bytes remain unchanged.

The one-year future-start horizon and absolute 24-hour cap remain fixed. New
reservations return PENDING when approval is required, otherwise APPROVED.
Both hold time under the original PostgreSQL exclusion constraint. An existing
request-key replay returns its original response even after a policy change.

## Approval

GET /api/approvals returns at most 100 pending reservations, ordered by UTC start
and ID. Each includes booking, owner username and policyVersion. Only enabled
approvers can read it. Administrators do not inherit requester/approver authority.

POST /api/approvals/{id}/decision accepts:
```json
{"decision":"APPROVED"}
```

REJECTED is the other supported decision. The role and enabled account are checked
again in the database, even if an old cookie still carries earlier authorities.
A user who becomes an approver cannot decide their own historical reservation.
Decisions serialize actor → resource → booking. A repeated identical decision
returns 200 without another audit. An opposite decision or a decision after
cancellation returns 409 INVALID_TRANSITION. A missing booking returns 404.
A rejected booking releases time; an approved booking can still be cancelled by
its owner. Status and booking audit commit together; injected audit failure rolls
back the transition. The browser retains a failed request in view for refresh/retry.

## CSV preview and apply

This core supports create-only resource metadata, not bookings/users or updates.
The fixed header is exactly code,name,type,location,capacity.

```csv
code,name,type,location,capacity
ROOM-CEDAR,"Cedar, studio",ROOM,Demo campus / East wing,12
KIT-CAMERA,Camera kit,EQUIPMENT,Demo campus / Desk,1
```

POST /api/admin/imports/preview accepts {"csv":"..."} using the session CSRF
header. The React textarea submits that JSON. Limits: 64 KiB UTF-8, 1–100 data rows,
4096 characters per physical row. LF/CRLF, optional initial BOM, quoted commas
and doubled quotes are supported. Multiline cells and control characters are not.
Values are trimmed; codes are canonical uppercase letters/digits/_/-, at most 64
characters. Names/locations are nonempty with maxima 160/200. Types are ROOM or
EQUIPMENT; capacities are decimal integers 1–999999.

Bad header/size/row-count yields 400. Bad data yields a persisted preview with
inputRows, acceptedRows, rejectedRows, parsed rows and row-numbered errors.
Every input row contributes to accepted or rejected count. Duplicate codes in
the input or existing database are errors. A batch with any errors cannot apply;
no resources are written during preview. Edit the CSV and preview again.

POST /api/admin/imports/{id}/apply confirms the server-stored immutable preview.
All rows and one RESOURCES_IMPORTED audit event commit atomically. Import writers
serialize with a PostgreSQL transaction advisory lock, rechecking every code
before insertion. A code added since preview yields 409 IMPORT_CONFLICT and no
partial write. This is deliberately not an upsert/update API.

Batches belong to the previewing administrator; another administrator gets 404
on apply and cannot list that batch. GET /api/admin/imports restores the actor's
latest ten previews, including invalid and applied batches. The exact actor/CSV
hash reuses its original preview. Preview is a snapshot, not a freshness promise.
Revise the CSV and preview again after a stale conflict.

Applying an already applied ID returns the same inserted count with replayed=true;
no duplicate resource/audit is created. Retry that ID after an uncertain response.
The applied state is durable in PostgreSQL, independent of React memory. A policy
or import audit failure rolls back its business writes. The browser clears an
old preview when CSV text changes so it cannot apply an unseen edit.

## Audit and restart proof

GET /api/admin/audit shows up to 200 latest booking, decision, policy and import
events, with actor, entity, UTC time and transition/details. This is a local demo
audit ledger, not tamper-proof compliance storage. Retention/filtering follow later.

CI executes the existing booking/conflict/retry browser checks and a role-based
policy → invalid CSV → two-row import/replay → booking → approval → cancellation
→ rejection → audit walkthrough. It records synthetic screenshots and JSON.
Then it records authenticated policy/import/audit/catalogue state, restarts only
the app container (preserving the database), and compares the exact state again:

```sh
python scripts/check_persistence.py --record
docker compose restart app
python scripts/check_persistence.py
```

Do this after the browser workflow creates its synthetic ROOM-CORE07 batch.
The helper reads only local generated credentials and writes ignored target/
evidence. It never prints credentials. Missing expected fixtures fails the check.

WorkflowIT tests real PostgreSQL role/CSRF/ownership boundaries, policy races,
prospective snapshots, self-decision denial, competing decisions, quoted CSV
parsing, limits/row errors, conflicting imports, replay and audit rollback.
Native Windows verification uses an isolated PostgreSQL 18 cluster; PostgreSQL
17 and the clean Docker path are separately verified by Linux CI. Neither is H2.

## Remaining release gates

CORE10 still executes the formal five-case UAT/repair pack; final clean-clone
claim audit is 17 November. No core release tag is earned merely by implementing
CORE07. Rich policy types/opening hours, CSV column mapping/updates, pagination,
retention, quotas, load measurements and December v1 hardening remain planned.
