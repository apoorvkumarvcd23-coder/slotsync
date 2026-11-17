# Core acceptance — 17 November 2026

This audit covers the bounded **v0.1.0 core**, with extensions and full v1.0.0
hardening continuing through December. The clean checkout audited here is
`34cd7cc97f89dc8d5b3729edd9dadf5907504295`. The release tag is created only after the reviewed acceptance
commit also passes its complete clean-checkout CI. Exact immutable tag/commit/CI
links are recorded in the [portfolio release ledger](https://github.com/apoorvkumarvcd23-coder/apoorvkumarvcd23-coder/blob/main/docs/CORE_RELEASES_2026-11-18.md).
Native audit observations are dated 17 November; publication and release checks
carried forward to 18 November. Commit and tag timestamps use their actual time.
No version tag substitutes for the checks.

The first 18 November release CI exposed an administration loading race: initial
metadata could overwrite a duration edit made before it arrived, producing an
unchanged save and no policy-change event. Policy controls now wait for loaded
metadata and remain disabled during a save/refresh. A deferred-response regression
failed before this repair; the real browser also asserts the saved 120-minute
policy response. The repaired acceptance commit must pass all checks before tagging.
The repaired frontend suite has 14 tests; the 17 November native snapshot below
retains its original 13-test count rather than changing historical observations.

## Architecture and contract ledger

React → Spring session/CSRF API → transactional PostgreSQL booking, policy, import and audit records.

| Core contract | Implementation | Actual verification |
| --- | --- | --- |
| Requester catalogue and UTC book/cancel UI | frontend/src/BookingPanel.tsx; resource/ResourceController.java; booking/BookingService.java | booking.spec.ts; catalogue.spec.ts; BookingIT |
| Real overlap, adjacency and actor-key replay | V1 GiST exclusion constraint; V3 idempotency records; BookingService.atomic/create | 39 PostgreSQL integration tests; concurrent browser 201/409; changed payload 409 and exact replay 200 |
| Prospective maximum-duration/approval settings | booking/AdminService.java; OperationsPanel.tsx; V4 policy versions | WorkflowIT; UAT01 snapshot/stale/unchanged policy checks |
| Approver role, decisions, cancellation and audit | booking/ApprovalController.java; BookingService.decide/cancel | WorkflowIT role/self-decision/rollback checks; UAT04/05 exact transitions |
| Bounded atomic fixed-column CSV imports | booking/AdminService.java preview/parseCsv/apply; V4 import batches | CSV bounds/quotes/conflict/replay regressions; UAT02; invalid batch has no writes |
| Complete UAT and durable app restart | frontend/e2e/uat.spec.ts; scripts/check_uat_persistence.py | Five formal cases; exact four-role views/original-response replay across real CI app-container restart |

See the [detailed claim-to-code/test ledger](CLAIM_EVIDENCE.md) and
[full workflow/runbook](CORE_UAT.md). The [machine-readable audit](core-acceptance-2026-11-17.json)
records native observations and hashes of the existing public evidence, retaining
their platform/model distinction. The [previous verified CI](https://github.com/apoorvkumarvcd23-coder/slotsync/actions/runs/43753629314)
is source-grounded evidence; fresh release-head CI must pass before tagging.

[Actual native acceptance screenshot](evidence/2026-11-17/core-acceptance.png)

## Reproduce from a clean clone

Install the prerequisites in README and follow the full runbook before these
commands. Use a new ignored artifact directory and synthetic/public data only.
Keep generated account/config files private. All service demos use disposable
owned local services; no paid service or external account is required.

```sh
python scripts/init_demo.py
docker compose up --build --wait --wait-timeout 180
# In frontend after npm ci and Chromium installation:
npm run test:e2e
npm run test:uat
# Back in repository root:
python scripts/check_uat_persistence.py --record
docker compose restart app
python scripts/check_uat_persistence.py
```

The native audit used actual installed dependencies and fresh disposable data.
Its platform is recorded explicitly; native fallback does not prove a different
database/service backend. Linux CI executes the documented real-service path.

## Defensible project wording

These are permitted implementation claims for this core, not a personal resume
file or a claim of customer/employer use:

- Implemented a Java/Spring/React room and equipment booking workflow with PostgreSQL overlap protection, actor-scoped replay and role-based approval.
- Added prospective policy settings, atomic resource CSV preview/apply/replay and audit; verified five synthetic UAT cases and application-restart persistence.

## Remaining scope and limits

- Local HTTP demo with generated accounts, not a public production deployment.
- Fixed CSV columns/create-only imports; richer mapping/updates and opening-hour rules remain December work.
- Pagination, retention, load measurements and full December v1 hardening remain open.
