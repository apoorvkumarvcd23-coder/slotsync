# Requester booking slice (CORE03)

The Docker demo serves React and the API from the same origin. Run the README
setup, open My bookings / Sign in, and read the generated local credentials from
ignored `.secrets/demo-users.json`. Four synthetic accounts exist: requester,
requester2, approver and admin. Passwords are randomly generated, retained across
setup reruns and hashed with BCrypt in PostgreSQL. They are never committed or
printed by setup. Existing accounts are not overwritten on application restart.

## Session protocol

1. GET `/api/session` with a cookie jar. It returns username/role (null when
   anonymous), a masked CSRF token and its header name. It creates a session
   when needed and sends Cache-Control: no-store.
2. POST form-encoded username/password to `/api/login`, including that CSRF
   header and session cookie. Success is 204; invalid credentials are 401.
3. GET `/api/session` again after login: authentication changes the session ID
   and rotates the underlying CSRF token. React refreshes it automatically.
4. Include the fresh CSRF header on all writes. POST `/api/logout` invalidates
   the session and deletes its cookie. React clears private booking state.

Session cookies are HttpOnly and SameSite=Lax, with a 30-minute session timeout.
The local HTTP demo uses a non-Secure cookie; an HTTPS deployment must configure
`SLOTSYNC_SECURE_COOKIE=true`. There is no public deployment in this slice.
Tokens/passwords are not stored in browser localStorage. Browser tracing is off
to keep generated credentials out of trace artifacts; screenshots are taken
only after login.

## Create and replay

POST `/api/bookings` requires a requester session, CSRF and an
`Idempotency-Key` of 8–100 ASCII letters/digits/underscores/hyphens.

```json
{
  "resourceId": "10000000-0000-0000-0000-000000000001",
  "startsAt": "2026-12-01T10:00:00Z",
  "endsAt": "2026-12-01T11:00:00Z"
}
```

Choose future dates when running this example. Timestamps require an explicit
offset. The service canonicalizes instants to UTC microseconds; equivalent
timezone offsets produce the same request hash. Current fixed guards require a
future start within 365 days, strictly ordered times and a duration up to 24
hours. Versioned, configurable policies are the CORE07 slice.

New requests return 201 and PENDING, which holds the slot. Exact actor/key/payload
replays return 200 with `Idempotent-Replayed: true` and the **original response**,
even if the booking was subsequently cancelled. GET the booking for current state.
Changing the payload under the same actor/key returns 409 REQUEST_KEY_REUSED.
Another actor can use the same key independently. Failed transactions do not
reserve a key.

The browser retains the key for a retry of an unchanged form after an uncertain
network result. A changed form gets a new key. A browser test deliberately commits
the first request but drops its response, then verifies one booking and a replay.
This retry state lasts for the current mounted booking form; it is not persisted
through a full page reload.

## Ownership and cancellation

- GET `/api/bookings` lists only the actor's latest 100 bookings, ordered by
  start time. Pagination is future work.
- GET `/api/bookings/{id}` returns only an owned booking.
- POST `/api/bookings/{id}/cancel` cancels owned PENDING/APPROVED bookings.
  Repeated cancellation returns the existing result without another audit event.
- A different owner receives 404 for reads/cancellation. Approver/admin accounts
  receive 403 on requester booking endpoints. Anonymous reads receive 401.
- Account enabled/role state is checked again inside the transaction, so a
  previously authenticated session cannot bypass a revoked requester role.

## Conflict and atomicity

The database's GiST exclusion constraint remains authoritative. Pending/approved
half-open intervals overlap; adjacent slots are valid. All application writes
lock actor, then resource, then booking where applicable. This serializes same-key
requests and resource conflicts consistently across application instances.

The whole transaction, including booking, audit and replay response, rolls back
on failure. Deadlock/serialization failures retry the entire transaction up to
three times. Overlap maps to 409 SLOT_CONFLICT; exhausted retries map to 409
BOOKING_BUSY with a safe same-key retry instruction. Unexpected storage failures
return a generic 503 without SQL or source details.

JUnit uses real PostgreSQL, including a database audit trigger that fails after
the booking insert, and injected SQLSTATE 40P01 failures to prove whole-transaction
retry and retry-budget rollback. Other tests cover actual session authentication,
CSRF rotation, ownership, concurrent overlap/replay and cancellation.

## Remaining core

Approval decisions, configurable policy snapshots, CSV preview/atomic import,
approver/admin screens, audit browsing and the complete five-case UAT are still
pending. No complete-core release tag is earned by the requester slice.
