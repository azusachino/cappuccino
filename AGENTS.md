# Cappuccino

Native Apple companion for existing, long-running Herdr/Pi agents. Read [intent](docs/intent.md) before changing behavior and [plan](docs/plan.md) before the attachment spike. [Discovery](docs/discovery.md) contains source-backed research, not runtime guarantees.

## Work

- Current source is a disconnected app skeleton. Keep unavailable actions explicit; sample data never represents a real attachment.
- Herdr/Pi own agent lifetime. Attach/detach preserves the remote process and session. Setup reload is explicit and idle-only.
- Keep core logic in `Sources/CappuccinoCore/`, UI in `App/`, hermetic tests in `Tests/`, and shell smoke in `UITests/`.
- Use Swift 6, two-space indentation and `make fmt`. Comments explain non-obvious constraints; names explain behavior. Prefer standard Swift/SwiftUI APIs and concrete types to speculative services or factories.
- Edit `project.yml`, not generated `Cappuccino.xcodeproj`. Xcode supplies Swift; `.mise.toml` pins the tools Make invokes.

## Verify

Run `make check` during iteration. Before accepting app changes, run `make validate` and `make ui-test DESTINATION="platform=iOS Simulator,id=<task-owned simulator>"`. README covers Xcode selection and simulator discovery. Structural/package tests are not proof of an attachment, approval or notification.

Tests use no owner sessions, network credentials or real approvals. A live spike uses explicitly task-owned processes and scoped integration files; preserve its source/runtime identity evidence. Approval/transport changes need fail-closed, stale-action and reconnect tests.

## Records and Git

Live tasks/claims/handoffs live in Asobi; accepted requirements and evidence stay in the owning docs/issue/PR. Follow the workstation's tier and fresh-verification rules when working in its checkout.

Use focused feature branches and conventional commits. Run owning gates before committing. Keep signing credentials, pairing tokens, Telegram secrets, real transcripts and private machine addresses out of source and review artifacts. Preserve the existing license. Deployment and changes to an owner's long-running agents require separate approval.
