# Versioned weekly policy

Administrators configure duration, approval, a 1–365 day future-start horizon and
one UTC opening window on selected weekdays. The requester form uses UTC too;
host/browser local time and daylight-saving transitions do not change the rule.

## Contract

GET /api/policy returns the current public policy. For authenticated admin writes,
fetch a current session/CSRF token and GET /api/admin/policy, then POST:

```json
{
  "expectedVersion": 1,
  "maxDurationMinutes": 120,
  "approvalRequired": true,
  "horizonDays": 14,
  "openingDays": [1, 2, 3, 4, 5],
  "opensAt": "09:00",
  "closesAt": "17:00"
}
```

Use the actual current version rather than the example's 1. Weekdays use ISO
numbers: Monday=1 through Sunday=7. At least one distinct day is required; order
does not matter. Times use exactly HH:mm. Only closing accepts 24:00. Opening
must precede closing: overnight/split windows, exceptions and campus-local time
zones are not supported. Use 00:00–24:00 for full-day opening.

The horizon is an elapsed number of 24-hour days from the request's captured
server time and limits the booking **start**. Equality at the horizon is allowed;
a later start is rejected. The end must still follow duration and opening rules.
Duration remains 1–1440 minutes; start must be future and within the absolute
365-day guard. Interval endpoints are canonical UTC microseconds.

Opening hours cover the complete half-open interval [start,end). A 09:00 start
and 17:00 end fit 09:00–17:00; an end one microsecond later does not. Every
occupied UTC date must be open. An exclusive midnight end does not occupy the
next day. Full-day consecutive opening days can permit a booking across midnight.

A denied new request returns 400 HORIZON_EXCEEDED or OUTSIDE_OPENING_HOURS, or an
earlier interval/duration validation error. Nothing is booked or audited and no
idempotency key is reserved. Existing conflict/permission/CSRF responses remain.

## Version and compatibility behavior

Every changed save creates one version and one atomic POLICY_CHANGED audit
event, including the complete schedule. Stale editors receive 409 POLICY_CHANGED.
An unchanged save (including reordered weekdays) creates neither version nor audit.

A booking and policy edit serialize on policy_current. Existing booking policy
references and statuses stay unchanged. Replay is checked before current policy
validation and returns the original actor/key/payload response after a policy
change, even when that interval would now be rejected.

Older clients may omit the four new schedule fields (or send null); each omitted
field retains the current value. They do not reset a restricted policy to all-day.
Required duration/approval/version fields retain strict typing. Fractional/numeric
string integers, invalid day lists and malformed times fail without partial writes.

V5 adds constrained schedule columns with the original defaults (365 days, every
weekday, 00:00–24:00) to existing versions. V1–V4 are unchanged. No new booking,
policy update or credential is seeded by this migration.

## Repeatable demonstration and checks

Start the README demo, sign in as admin, choose a short horizon, opening day and
09:00–17:00, save, then reload the page. As requester, try a later start, a closed
hour and an allowed interval ending exactly at 17:00. Inspect the resulting
version/audit; existing reservations retain their saved versions.

- `./mvnw -B -ntp verify`: real PostgreSQL HTTP, migration, race, rollback,
  schedule bounds, closed days, exact hours/midnight, old policy/replay and legacy
  editor preservation; a fixed-clock unit check proves the exact horizon boundary.
- In frontend: `npm test` and `npm run build`: form persistence, empty-week denial,
  loading/save protection and typed production build.
- `npm run test:e2e`: the actual Chromium `policy.spec.ts` changes hours/horizon
  through React, reloads settings, observes requester denials/success, checks old
  response replay and a 390px layout, and restores the prior complete policy.
- `npm run test:uat` and the existing restart scripts retain the five core UAT
  cases and exact persisted role/policy state.

CI runs the full real PostgreSQL 17/Docker workflow and uploads weekly-policy.json
and the actual desktop/mobile PNGs. Native PostgreSQL 18 checks are distinct from
that Linux evidence. Synthetic workflows demonstrate behavior, not production
scale or completion of every December v1 gate.



## Recorded native verification — 19 November 2026

The [native report](weekly-policy-native-2026-11-19.json) records Windows/Java 21
with real PostgreSQL 18: 43 integration checks, one fixed-clock unit check,
15 React tests/build, two real-socket readiness checks, six actual browser
scenarios and five business UAT cases pass. Actual app-process replacement with
the same database preserves the exact restricted schedule, policy/import/audit
state and four-role UAT/replay views. Fresh sessions enforce both horizon and
opening-hour denials after restart. Windows termination is recorded as exit 1;
this is not a graceful-zero-exit claim.

- [Actual admin configuration](evidence/2026-11-19/weekly-policy.png)
- [Actual 390px configuration](evidence/2026-11-19/weekly-policy-mobile.png)

To repeat the restricted-policy restart drill on a disposable demo:
```sh
python scripts/check_policy_persistence.py --record
docker compose restart app
python scripts/check_policy_persistence.py
```

The verifier records a seven-day 09:00–17:00 UTC schedule, compares it through
fresh sessions after the invoker's real restart, exercises requester denials and
restores all initial settings. Its ignored target/ snapshot contains no passwords.
Running the verifier alone does not establish a process restart.
