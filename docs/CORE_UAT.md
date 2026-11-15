# Five-case core UAT

Run this pack on the isolated generated-account demo after the existing five
browser scenarios. It uses actual React, Spring, PostgreSQL and four independent
cookie sessions. No API responses or model adapters are substituted.

```sh
cd frontend
npm ci
npx playwright install chromium
npm run test:e2e
npm run test:uat
cd ..
python scripts/check_uat_persistence.py --record
docker compose restart app
python scripts/check_uat_persistence.py
```

Use README setup first: generate private demo credentials, then start the clean
Compose app/database. An existing personal deployment is not the UAT target.
The pack starts with approval required and a 1440-minute maximum, restores that
policy, uses unique synthetic import codes and cancels its remaining bookings.
Preview/import/audit records are intentionally retained for restart verification.
Use a fresh disposable database when rerunning all existing catalogue scenarios.

| Case | User action and expected result | Executable check |
| --- | --- | --- |
| UAT01 | Lower duration limit prospectively; stale editor conflicts, unchanged save creates no version, old booking keeps its policy and original replay; new over-limit booking fails | Real admin/requester UI and approver queue, HTTP 409/400, exact old response |
| UAT02 | Duplicate-row batch cannot apply or write resources; valid two-row batch applies atomically; replay creates no duplicates | Preview errors, disabled confirmation, direct apply denial, exact catalogue and 390px viewport |
| UAT03 | Two browser identities compete for one slot; one succeeds, one conflicts; adjacent slot works; same key replays and changed payload conflicts | Concurrent actual POSTs 201/409, replay 200 with header and exact body, changed request 409 |
| UAT04 | Foreign owner cannot read/cancel; requester cannot approve/read audit; approver approves/rejects; cancellation/rejection release slots | UI transitions, 404/403 boundary checks, repeated decision and successful replacement booking |
| UAT05 | Every transition has exactly one audit record; invalid import/replays add none; policy is restored | Exact action multiset for five booking IDs (11 events), one valid import and two policy events |

`frontend/e2e/uat.spec.ts` emits `test-results/uat.json` and six screenshots only
after assertions pass. The JSON contains synthetic IDs and an actor-scoped
idempotency key, never login credentials/cookies/CSRF. Browser tracing is off.
`scripts/check_uat_persistence.py` separately compares exact policy, imports,
audit, catalogue, both owner booking lists and approver queue using fresh
authenticated sessions after the app process restarts with the same database.
It then replays the original pending response of a now-cancelled booking and
checks that replay adds no audit. Its invoker must actually perform the restart;
calling the comparison helper twice alone is not process-restart evidence.

The foundation CI executes both packs, a real database outage/recovery and both
restart verifiers on clean Linux/PostgreSQL 17. Native Windows/PostgreSQL 18
captures are labelled separately. Permanent recorded results are added only
after their source CI succeeds. Five synthetic UAT cases establish this bounded
workflow, not throughput, production security or the December v1 feature gates.
Final four-project core/claim audit remains 17 November; no release tag yet.
