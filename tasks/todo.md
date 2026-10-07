# Android connected implementation checklist

[Acceptance plan](plan.md); owning [issue #12](https://github.com/azusachino/cappuccino/issues/12). This file tracks code checkpoints; live assignments/handoffs are authoritative in Asobi. A checked implementation step is not independent acceptance.

- [x] Reconcile merged source topology; open integration PR #22 without merging it.
- [x] Record Android-first, read-only scope and current pane-output/history limits.
- [x] A: pure wire models, URL builders, bounded reconciliation and actual/shared fixture tests.
- [x] B: concrete HTTP/WS transport, verified profiles, controller cancellation and foreground recovery tests.
- [x] C: native Machines/catalog/output UI and unavailable sending/Attention states.
- [x] C: actual synthetic transport and consuming Compose journeys on a task-owned emulator, including two-machine isolation and lifecycle failure cases.
- [x] D: source freeze and fresh independent source/runtime/native/UI verification.
- [x] D: record exact evidence, artifact hash and identity-scoped cleanup; reviewed commit/draft PR #23.
- [ ] E: hand debug APK to owner for phone installation and feature/UI iteration.
- [ ] E: separately prove actual phone/tailnet journeys before accepting device claims.

Deferred: canonical Pi history endpoint/parity, delivery and approvals, background alerting, iOS distribution and Mac-specific UX. Do not close issue #12 solely for this slice.
