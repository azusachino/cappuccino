# A3 mutation session-binding proposal

Status: owner wire acceptance given in-session; implementation pending. Base:
`feat/bridge-android-reliability` at
`5cf12114edf26c89187297e7f9a1b70f8af030e3`.

This is the A3 slice of the [reliability plan](bridge-android-reliability-plan.md).
It checks the selected native binding before terminal input. It does not provide
atomic fencing, native admission, queue selection or an idempotent receipt.

## Scope and authority

The owner authorized continuing A3 through contract acceptance, implementation
and verification, and accepted this exact wire/strict-compatibility proposal.
This is the accepted contract, not yet an implemented capability.

Bridge, Android and Apple are the only implementation owners. No Herdr/Pi
runtime modification, owner session, global integration, deployment, push or PR
is authorized. The existing tailnet/loopback boundary is unchanged. Binding data
is a request precondition, not authentication or a secret.

Existing issues #8 and #9 retain native delivery/approval authority. Issue #10
covers broader lifecycle/grants reconciliation and is not closed by this slice.
[Issue #45](https://github.com/azusachino/cappuccino/issues/45) records the
accepted A3 criteria. Read-only searches found no existing matching binding issue.

## Wire contract

Keep protocol 1, current endpoints and current successful response meanings.
Advertise `capabilities.mutation_binding: true` in `GET /api/session` only when
all mutation variants enforce the following contract. It means pre-dispatch
binding checks, not atomic native enforcement.

Every mutation body carries the same required object:

```json
{
  "type": "prompt",
  "text": "synthetic input",
  "expected_session": {
    "machine_id": "11111111-1111-4111-8111-111111111111",
    "pane_id": "w1:a",
    "native_session_ref": "native-ref:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
}
```

Apply it to both existing routes:

- `POST /api/agents/{sessionId}/prompt`: `prompt`, `answer_prompt` (selection or
  cancel), and `answer_question`.
- `POST /api/agents/{sessionId}/control`: `interrupt` and `new_session`; here
  `action` replaces the example's `type`/`text` fields.

The URI remains the advertised locator. Machine identity uses UUID equality,
not case-sensitive string equality. Pane and native reference must be nonempty,
bounded strings matching the advertised values exactly. The native reference
is opaque; it is not a canonical conversation ID, file path or process token.
Each pane/reference field is limited to 256 UTF-8 bytes. Unknown extra object
fields remain ignorable for additive evolution.

A missing/null `expected_session` is different from malformed binding data.
Validate binding and ordinary input before any mutating Herdr RPC. Keep slash
confirmation, exact answer validation, prompt nonce claiming and idle-only
New session checks; the new precondition never substitutes for them.

### Compatibility

| Client / bridge | Mutation behavior |
| --- | --- |
| Updated / updated | Requires capability, complete selected binding and current live readiness; serializes the binding on every mutation. |
| Updated / older or capability absent/false | Read-only; no mutation POST and no unbound fallback. |
| Older / updated | Missing binding is rejected before terminal input; existing read endpoints remain available. |
| Missing selected native reference | Updated client keeps mutations unavailable; it must not infer identity from locator/cwd/history. |

This intentionally ends legacy unbound writes on the upgraded bridge. Updating
an old client is necessary to regain writes; it is not a protocol-version bump
or a new receipt guarantee. No old bridge is automatically upgraded or deployed.

## Server dispatch checks

1. Validate the expected machine against the bridge and resolve/check the
   expected pane, locator and native reference using fresh Herdr metadata.
   Missing agent/reference, wrong binding or ambiguous resolution fails closed.
2. Read and validate the current screen when answering a prompt/question. Keep
   its existing single-use nonce rules.
3. Recheck the binding immediately before each terminal-mutating RPC. Use the
   verified pane target, never resolve a different alias after checking a pane.
   New session must still be known idle at its final check.
4. Multi-step question answers recheck before each keys/text step. Stop as soon
   as a check fails; do not continue against a replacement.
5. Do not retry a write, reuse a spent decision, adopt the replacement's binding
   or reissue the action automatically. Do not echo a replacement binding as an
   invitation to silently update the request.

Reuse the existing reported-reference hashing rather than a second identity
algorithm. Herdr's captured request schema has no native-session precondition on
`agent.prompt` or `pane.send_keys`; adding an unsupported parameter would not
establish fencing.

### Errors and uncertainty

Keep the existing flat `event: error`, `code`, `message` envelope.

| Condition | HTTP / code | Effect semantics |
| --- | --- | --- |
| Missing/null binding | 428 `session_binding_required` | No terminal input. |
| Malformed binding | 400 `bad_session_binding` | No terminal input. |
| Machine/pane/native/locator mismatch or disappeared target | 409 `stale_session` | No input from this request when detected before its first write. |
| Metadata unavailable or malformed before first write | 503 `upstream_unavailable` | No terminal input; actions stay unavailable. |
| Binding lost/check fails after an earlier question step may have applied | 502 `mutation_outcome_unknown` | No further steps; earlier input may have applied. Never replay. |
| Write timeout/failure | Existing 5xx ambiguity, or `mutation_outcome_unknown` | Never infer rejection or native admission from missing acknowledgement. |

A stale preflight must not consume an otherwise valid prompt nonce. Once any
write is attempted, existing single-use rules and uncertainty still apply.
Clients surface stale binding as a reselection requirement, invalidate live and
pending action affordances, and preserve drafts/outcomes under the original
native session key. Replacement sessions inherit neither a pending decision nor
automatically resent input. Current transport-success responses remain PTY
acknowledgements only.

## Explicit ceiling

Replacement can occur after the final successful check but before Herdr applies
the write. Reported native references can also be reused; their hashes are not
unforgeable process-incarnation tokens. This slice cannot eliminate those races.
A deterministic mock-peer race must demonstrate the remaining window rather
than turn a passing preflight test into an atomic-safety claim. Closing it needs
a precondition enforced at the actual mutation authority (D2/E1 or an accepted
Herdr extension), outside this slice.

## Acceptance and implementation order

1. Owner accepts the wire/strict compatibility decision. Record these criteria
   in an owning A3 issue without changing #8/#9's acceptance. Pushes/PRs remain
   separately held under the current request.
2. Add shared binding/error fixtures and bridge boundary/dispatch guards. Use
   actual bridge processes with mock Herdr, not only handler/unit stubs.
3. Update Android and Apple transport/model/UI adapters in serialized writer
   stages; required binding and capability checks also cover direct callbacks.
4. Verify all routes/variants: correct binding, missing/null/malformed binding,
   foreign machine, missing native reference, reused locator/pane, screen drift,
   duplicate answer, and native replacement between multi-step writes.
5. Prove stale rejection before the first write causes zero mutating RPCs;
   prove partial failure stops later steps and remains delivery-unknown. Force
   post-final-check replacement and retain its observed limitation explicitly.
6. Prove both clients send the original binding, reject missing capabilities,
   clear stale affordances, isolate late outcomes and never replay uncertain
   input. Run task-owned native journeys with no owner session or credential.
7. Run `make check-bridge`, platform focused checks, `make validate`, both native
   UI targets, `make md-check` and `git diff --check`; preserve all prior tests.
   Persist exact source/gate evidence before committing owned implementation.
