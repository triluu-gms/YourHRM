# ADR-0007: In-house workflow and approval engine in Core

- **Status:** Accepted (2026-10-16, OD-4)
- **Date:** 2026-10-16
- **Related:** Core BRD §4.5 (BR-CORE workflow), NFR-CORE-004; BRD DP6, RK5; gap-analysis GAP-063; [architecture-overview](../architecture-overview.md) §9.2

## Context

Core must provide approvals for leave, OT, corrections, HR changes, payroll runs, requisitions and more: sequential and any-of/all-of steps (MVP), parallel branches (P2), conditions, approver resolution by org/position/role/scope, delegation (incl. auto-suggestion from leave), SLA in working time, escalation, reminders, return-to-requester, and e-mail/Zalo action links (P2). Definitions are configured per tenant by HR administrators, not by developers. The BRD flags workflow-engine generality as a scope-creep risk (RK5). Targets: submit ≤ 500 ms p95, ≥ 50 submissions/s per node, no lost tasks.

## Decision

Build a **table-driven approval engine** in `core` (implementation) with client APIs/SPIs in `shared/workflow`:

- Definition model: request type → versioned definition → ordered steps (mode, approver resolver, condition in YFL, SLA, escalation, allow-return, allow-delegate). Running instances keep their definition version.
- Runtime: `workflow_instance`, `workflow_task`, `workflow_action` in `core`; commands are idempotent; timers (SLA, reminders, escalation) are scheduler jobs (ADR-0011); working-time SLA uses the T&A `WorkingCalendarProvider` SPI.
- Integration: owning modules start instances via `WorkflowService.start(requestType, subjectRef, context)` and react to `core.WorkflowStepCompleted` / `core.WorkflowCompleted` events in their own transactions.
- UI: approvals inbox (ESS/MSS), definition editor as a form-based step list (no BPMN canvas).
- Long-running *system* processes (sagas) are **not** modelled in the workflow engine; they use module process managers (ADR-0019).

## Alternatives considered

- **Flowable 7 (embedded):** mature BPMN/DMN, but multi-tenant definition management, versioning per tenant, and HR-friendly configuration UI would still have to be built; BPMN is heavier than needed; adds ~200 tables and a second persistence model. Strong fallback if P3 needs arbitrary process modelling.
- **Camunda 8:** Zeebe cluster and licence for production use; operations and residency burden; rejected.
- **Temporal:** excellent for system sagas, not for tenant-configured human approvals; adds a cluster.

## Consequences

- + Approval semantics match the BRD exactly; tenant configuration stays simple and safe.
- + No extra cluster or licence; reuses YFL, scheduler, notification centre, audit.
- − We own the engine code (estimated 6–8 developer-weeks for MVP scope); parallel branches in P2 add complexity.
- − Requests for general BPM features must be pushed back or trigger re-evaluation of Flowable.
