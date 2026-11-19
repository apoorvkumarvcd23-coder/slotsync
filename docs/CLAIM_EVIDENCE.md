# Implemented scope and evidence

The [17 November core acceptance audit](CORE_ACCEPTANCE.md) covers the complete
bounded v0.1.0 workflow, including CORE10 UAT. The slice reports below retain
their original source/platform labels. Full December v1.0.0, production scale
and business-result claims are outside this acceptance.

CORE07 is verified by [clean-checkout CI](https://github.com/apoorvkumarvcd23-coder/slotsync/actions/runs/43294045428)
at `6f735b996b1e7c7fa353034200e6fdfd8b1788cf`: 39 PostgreSQL integration tests,
13 frontend tests, five browser scenarios, two readiness-socket regressions and
actual app-restart persistence. [Report and screenshots](ADMIN_WORKFLOWS.md)
identify the native PostgreSQL 18 captures separately from Linux/PostgreSQL 17 CI.

| Claim | Code | Executable evidence |
| --- | --- | --- |
| Same-origin React catalogue reads real PostgreSQL resources | `frontend/src/App.tsx`, `resource/ResourceController.java`, `Dockerfile` | `FoundationIT.catalogueIsDeterministicAndContainsOnlyPublicFields`; `frontend/e2e/catalogue.spec.ts` |
| Inactive resources are hidden; ordering is stable | `ResourceRepository.java` | `inactiveResourcesAreHidden`, catalogue API assertion |
| Versioned schema and repeatable synthetic fixtures | `db/migration/V1*`, `V2*` | `migrationsValidateAndCanRunAgainWithoutDuplicatingFixtures` |
| PostgreSQL prevents active overlaps, including equivalent UTC instants | V1 `no_overlapping_active_bookings` | `pendingAndApprovedSlotsBothPreventOverlap`, `equivalentTimeZonesStillConflict` |
| Adjacent/different-resource bookings and released slots are allowed | V1 exclusion predicate/range bounds | `adjacentSlotsAndDifferentResourcesAreAllowed`, `cancelledAndRejectedSlotsDoNotHoldTime`, `cancellationFreesTimeAndReactivationCannotBypassConstraint` |
| Two real concurrent database connections leave one reservation | V1 exclusion constraint | `concurrentConnectionsProduceExactlyOneReservation` (one commit; loser aborts with overlap 23P01 or deadlock 40P01) |
| Invalid, zero-length and infinite intervals are rejected | V1 `valid_booking_interval` | `zeroReversedAndInfiniteIntervalsAreRejected` |
| Readiness depends on PostgreSQL; catalogue mutation stays closed | `application.yml`, `SecurityConfiguration.java` | `readinessIncludesDatabaseAndHidesOperationalDetails`, `writesAreClosedUntilAuthenticatedWorkflowExists` |
| UI handles errors, retry, empty catalogue and type filter | `frontend/src/App.tsx` | `frontend/src/App.test.tsx`; browser desktop/mobile check |
| Generated local role accounts use BCrypt; login rotates session and CSRF | `SeedUsers`, `SecurityConfiguration`, `SessionController`, `init_demo.py` | Actual login/CSRF/logout JUnit tests and browser role/session checks |
| Owner-scoped create/list/cancel API and React forms | `booking/`, `BookingPanel.tsx` | `BookingIT` ownership/cancellation tests and real browser book/conflict/cancel/rebook scenario |
| Actor-scoped keys replay the original response; changed payload conflicts | `idempotency_requests`, `BookingService.create` | Canonical-offset, different-actor, concurrent replay JUnit tests; browser dropped-response retry |
| Database conflicts become 409; deadlock retries are whole-transaction and bounded | `BookingService.atomic` and resource/actor locks | Concurrent overlaps, injected 40P01 success and exhausted-budget tests |
| Booking, replay response and audit commit together | V3 migration, `BookingService` | Audit-trigger failure verifies no booking/key/audit partial write |

## Remaining core gates

- The bounded core is audited in [CORE_ACCEPTANCE.md](CORE_ACCEPTANCE.md).
  A core tag still requires passing CI of its reviewed acceptance commit.

The [five-case UAT pack](CORE_UAT.md) adds policy/import/concurrent booking/
decision/audit scenarios in `frontend/e2e/uat.spec.ts`, with independent role
sessions and exact reconciliation. CI runs the pack and
`check_uat_persistence.py` across an actual app container restart. A successful
source run/report is required to claim recorded verification of these checks.
Recorded 16 November: [permanent five-case results and actual CI app restart](core-uat-2026-11-16.json)
passed at source `e3c7cf53ad07fb109066033bb884eb24332c0b99`;
[CI and inspected screenshots](CORE_UAT.md) distinguish native PostgreSQL 18
from Linux/PostgreSQL 17. Final clean-clone/claim audit and release remain open.

The implementation plan defines the remaining formal UAT/release gates. The
following CORE07 checks extend the original requester evidence:

| Claim | Code | Executable evidence |
| --- | --- | --- |
| Prospective policy versions, strict typed bounds and stale-editor protection | V4, AdminService, BookingService.create | WorkflowIT policy snapshot/bounds/concurrent editor tests |
| Approve/reject, no self-decision, repeat-safe audit and cancellation semantics | BookingService.decide, ApprovalController | WorkflowIT competing decisions, self-approval, rollback and slot-release tests |
| Bounded fixed-column CSV preview with row errors | AdminService.parseCsv | Quoted/BOM/CRLF, duplicate, malformed, type, size and row-limit tests |
| All-or-nothing apply, conflict recheck and durable replay/actor ownership | V4 import_batches, AdminService.apply | Concurrent import conflict, audit rollback, exact preview replay and wrong-admin tests |
| Live role/enabled checks complement session roles and CSRF | AdminService.actor, BookingService.lockActor, SecurityConfiguration | Privileged-route and stale-role/disabled-account integration tests |
| Admin/approver screens and audit use actual API/database | OperationsPanel, workflow.spec.ts | Invalid preview/apply/replay → imported resource booking → approve/cancel/reject → audit browser walkthrough |
| App restart preserves policy/import/audit/catalogue | check_persistence.py, CI | Authenticated exact before/after comparison across app container restart |
| Initial metadata cannot overwrite an admin edit | OperationsPanel policy controls | Deferred initial/read-save regression and actual browser saved-policy response assertion; 18 November release repair |

See [workflow contracts and run instructions](ADMIN_WORKFLOWS.md). Test adapters
are not substituted for PostgreSQL; frontend unit mocks are separate from actual
browser/API checks. No workload throughput or production/business impact is claimed.


## Weekly policy extension (XS01)

| Claim | Code | Executable evidence |
| --- | --- | --- |
| Admin saves a bounded horizon and UTC days/hours without changing old versions | V5, AdminController, AdminService, OperationsPanel | WorkflowIT weekly bounds, unchanged/stale edit, old policy/replay and legacy-client preservation; OperationsPanel weekly form test |
| Every occupied UTC date fits opening hours, including exclusive midnight boundaries | PolicySchedule, BookingService.create | WorkflowIT horizon/window and midnight tests; fixed-clock PolicyScheduleTest |
| Real requester UI sees policy denials/success and existing response replay is preserved | frontend/e2e/policy.spec.ts | Real React/Spring/PostgreSQL browser workflow, reload and desktop/390px screenshots; CI weekly-policy.json |

[Policy contract and repeatable checks](WEEKLY_POLICY.md). The existing core tag
does not include this later extension; full December v1 gates remain open.
