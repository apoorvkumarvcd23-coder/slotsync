# SlotSync — December 2026 implementation plan

## Starting point and purpose

No SlotSync repository or implementation was found in the connected GitHub
account on 7 November. This is a greenfield build plan derived from the existing
resource-booking commitments, not evidence of delivered Java or customer work.

Build a campus room/equipment booking application. A requester finds a resource,
submits a time slot, gets an approval decision and can cancel. An administrator
changes policies and imports resources without source-code edits.

## Bounded v1

Use **Java 21, Spring Boot, Spring Security, JPA/Flyway, PostgreSQL 17, React and
TypeScript**. Pin a compatible stable Spring Boot release and Maven wrapper in
the first task; the current official requirements support Java 21. Use one
backend, one database and a simple frontend, with same-origin routing in Docker.
Avoid microservices, Kubernetes, payments, external calendars and notifications.

Roles: requester, approver and administrator. Use local accounts with hashed
passwords, generated ignored seed credentials and server sessions with secure
cookie settings and CSRF protection. Scope object access by actor/role; a role
check alone must not permit access to another user's booking.

Core records:

- `users`, `roles`, `resources`, `resource_types`, `policy_versions`;
- `bookings` with owner, resource, UTC start/end, status and policy snapshot;
- `idempotency_requests` with actor/key uniqueness, canonical payload hash and
  stored response; `audit_events`; `import_batches` and import rows.

Reservations use timezone-aware half-open intervals `[start,end)`, with
`start < end`. PostgreSQL must enforce non-overlap per resource using a GiST
exclusion constraint and `btree_gist`; pending and approved bookings hold a slot.
Rejected/cancelled bookings do not. Adjacent slots are allowed. Store UTC and
display the user's explicit time zone; never silently assume UTC for naive input.

State transitions: `PENDING -> APPROVED | REJECTED | CANCELLED`, and
`APPROVED -> CANCELLED`. Repeated transitions return the existing result where
appropriate; invalid transitions fail without partial writes. Approval rules
must specify whether self-approval is forbidden. All booking changes and audit
events commit together. Map database conflicts to a stable HTTP 409 response.

## Interfaces and workflow

Provide session/login/logout, resources/availability, create/list/cancel bookings,
approval decisions, policy configuration, CSV preview/apply and audit endpoints.
Publish OpenAPI with permission and conflict examples. Screens: login, resource
list/availability, booking form and own bookings, approval queue, admin settings,
CSV preview/errors and audit history.

Configuration includes resource type, maximum duration, booking horizon,
opening hours and approval requirement. Validate policy edits and retain a
version reference on existing bookings. Apply changes prospectively by default;
do not silently cancel existing reservations.

CSV v1 imports resource metadata only. Map columns, validate rows and show
accepted/rejected counts before applying. Reject an invalid batch atomically;
replaying an already applied batch must not duplicate resources. Record who
applied it and reconcile input/output counts. Do not import bookings or users
in this version.

## Task sequence and acceptance

| ID | Work unit | Observable acceptance |
| --- | --- | --- |
| S01 | Maven/Spring/React skeleton, PostgreSQL Compose, Flyway and CI | Clean checkout builds; health check detects database readiness; versions pinned |
| S02 | Resource/booking schema and range constraint | Real PostgreSQL rejects overlapping active intervals; adjacent intervals pass |
| S03 | Accounts, sessions and three roles | Anonymous, wrong-role and wrong-owner requests are denied; no credentials committed |
| S04 | Transactional create/cancel API | Cancel frees a slot; validation and conflict responses match OpenAPI |
| S05 | Actor-scoped idempotency | Same key/payload replays one result; changed payload returns 409; simultaneous replay is safe |
| S06 | Concurrency, audit and rollback | Concurrent overlapping submissions yield one booking; injected failure leaves no partial audit/booking |
| S07 | Versioned resource policy API | Admin edits are validated and affect new requests; old bookings retain policy version |
| S08 | Approval/rejection transitions | Forbidden self/wrong-role decisions fail; duplicate approvals produce one transition |
| S09 | Requester React screens | Browser can log in, find availability, book, see 409 feedback and cancel |
| S10 | Approver React screens | Queue and decisions reflect persisted state and permission failures |
| S11 | Administrator configuration UI | Resource/policy edits work through UI and survive restart |
| S12 | CSV mapping and preview | Malformed values, duplicate IDs and missing columns have row-level errors and counts |
| S13 | Atomic import apply/replay | Invalid batch changes nothing; valid replay does not duplicate resources |
| S14 | Requirements/workflow/UAT pack | Each requirement maps to config/API, UAT case, execution result and issue resolution |
| S15 | Browser end-to-end suite | Requester/approver/admin flows and a booking conflict pass against real PostgreSQL |
| S16 | Clean-clone Docker and recovery | Generated seed users, migrations, volume restart and documented reset work from scratch |
| S17 | Release gate, measurement and handover | All tests/UAT pass; bounded concurrency report records hardware, workload and actual observations |

## Release evidence

`./mvnw verify` (or `mvnw.cmd verify`) runs JUnit and Testcontainers PostgreSQL
integration checks. Frontend checks build and execute browser tests against the
real service/database. CI uses synthetic data, not employer systems. Include a
five-minute demo: configure a resource, reject an invalid import, import a valid
batch, submit concurrent/conflicting bookings, approve and cancel.

`v1.0.0` requires S01–S17 acceptance, setup/runbook, architecture/ER diagram,
requirements-to-UAT matrix, a resolved issue log and real screenshots. Expected
completion is late December; no production scale or business savings are claimed.

Design reference: [PostgreSQL range constraints](https://www.postgresql.org/docs/current/rangetypes.html#RANGETYPES-CONSTRAINT)
and [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html).
