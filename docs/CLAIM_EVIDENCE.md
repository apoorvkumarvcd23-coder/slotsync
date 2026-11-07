# Implemented scope and evidence

This is the CORE01 foundation, not a complete booking release. No v0.1.0/v1.0.0
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
| Readiness depends on PostgreSQL; no write interface exposed | `application.yml`, `SecurityConfiguration.java` | `readinessIncludesDatabaseAndHidesOperationalDetails`, `writesAreClosedUntilAuthenticatedWorkflowExists` |
| UI handles errors, retry, empty catalogue and type filter | `frontend/src/App.tsx` | `frontend/src/App.test.tsx`; browser desktop/mobile check |

## Remaining core gates

- Seeded hashed-password accounts, login/session/CSRF and requester/approver/admin authorization.
- Transactional booking create/cancel API/UI, bounded deadlock transaction retry, stable 409 handling and actor-scoped idempotency.
- Policy configuration, approval/rejection, persisted audit and bounded atomic CSV import.
- Full five-case UAT, permission/browser matrix and clean-clone acceptance of that complete workflow.

The implementation plan defines these upcoming slices. Passing the constraint
tests proves database behavior; it does not prove API-level booking semantics,
idempotency, authentication, approvals or end-to-end UAT.
