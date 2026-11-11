# Implemented scope and evidence

This covers CORE01 and the CORE03 requester slice, not a complete release. No v0.1.0/v1.0.0
tag or production/scale/business-result claim is made.

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

- Policy configuration/snapshots, approval/rejection and bounded atomic CSV import.
- Approver/admin UI and audit browsing.
- Full five-case UAT, permission/browser matrix and clean-clone acceptance of that complete workflow.

The implementation plan defines these upcoming slices. Requester API/session
checks do not complete approval, policy, import or the full role-based UAT gates.
