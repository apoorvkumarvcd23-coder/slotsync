# SlotSync

Configurable room and equipment booking with transactional conflict prevention.

**Status: planning stage. There is no runnable implementation yet.**

The planned v1 uses Java 21, Spring Boot, React/TypeScript and PostgreSQL. It will
support requester/approver/admin roles, database-enforced non-overlapping slots,
idempotent reservations, configurable policies, approval workflows, validated CSV
resource imports and an auditable handover/UAT workflow.

See [the implementation plan](docs/IMPLEMENTATION_PLAN.md) for the architecture,
task sequence and release acceptance. The delivery target is 31 December 2026.
Features become implemented only when their tests and demonstration pass.

## Intended demo

1. Configure a campus room and its booking policy.
2. Preview a resource CSV and inspect row-level validation errors.
3. Submit two conflicting reservations and see one rejected by PostgreSQL.
4. Approve a valid request, then cancel and rebook the slot.
5. Inspect the audit trail and executed acceptance cases.

Setup commands will be added with the first runnable application slice. Until
then this repository contains planning documents, not application code.
