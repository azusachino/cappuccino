# Offline conversation identity

Status: owner-selected local contract change, implementation and verification in progress. Deployment and whole-flow acceptance are not authorized or established by this document. This refines the [native continuation plan](https://github.com/azusachino/cappuccino/blob/35e72ff/docs/plan/native-ux-improvement-session.md) after the [Paseo study](paseo-source-study.md).

## Recorded failure and decision

The conversation response previously contained a reusable Herdr `session_id` locator, source and turns, but no canonical conversation generation. Keying snapshots by machine/locator could overwrite a different conversation when a pane was reused. The owner selected adding canonical bridge identity rather than shipping a last-snapshot-per-pane feature.

Keep `session_id` as the existing routing/response-ownership field. Include the producer's `machine_id` in the response so a restart between handshake and fetch cannot silently attribute another machine's content to the verified profile. Add optional `conversation_id` only when the bridge can establish source-backed canonical identity. Do not derive it from a pane, PID, cwd, terminal title, message contents, timestamp or directory modification time. This additive field does not change protocol version 1 or enable any remote action.

## Producer contract

- Pi: use the `id` from the first JSONL record only when it has `type: session`, in the exact file selected by the existing reported-session/store resolver. Missing, invalid, blank, control-bearing or oversized identity means no archive key. Read at most 16 KiB of the header; an oversized header does not manufacture a fallback identity.
- Agy: use the brain session directory selected by the existing process-owned presence-lock resolver. Keep its foreground PID/FD, unique-session, canonical-store and revalidation checks. The identity helper is not an alternative resolver.
- Hash domain `cappuccino:canonical-conversation:v1`, NUL, provider, NUL, raw session ID using SHA-256. Emit `pi:<64 lowercase hex>` or `agy:<64 lowercase hex>`. Raw session IDs and filesystem paths are not returned by this field. This is identity, not authentication.
- Read canonical content and identity through the same open transcript file. Compare Pi header identity before/after the bounded tail read; changed identity has no archive key. Ordinary appends keep identity stable; a replacement header changes it.
- Scrollback never receives `conversation_id`. Legacy responses remain valid without the field. An unavailable identity does not prevent passive content display.

## Android consumer and storage

Require response `session_id` to equal the requested locator. A canonical ID requires a valid response `machine_id`, and the ViewModel requires it to match the verified profile. Reject malformed canonical IDs, foreign/missing modern machine identity and scrollback identity claims. Before saving, verify the expected machine and require canonical source, supported identity and matching provider, with existing profile/selection/epoch guards.

Snapshot schema 2 requires the canonical ID. Disk keys are hashes of verified machine UUID plus canonical ID; the captured locator remains metadata, not the archive key. Two canonical conversations from one reused pane stay separate; the same canonical conversation observed in another pane updates the same archive. Schema 1 cannot be guessed/migrated from its locator alone; reject it without deleting its files or remote data.

Retain the existing bounds: 20 entries, 32 MiB retained record bytes, 4 MiB per entry, atomic replacement and bounded message/tool windows. The atomic write can temporarily coexist with the previous retained data. Profiles, drafts/preferences and temporary cache have separate storage ownership.

Saved views are read-only, show their capture time, use unknown current agent state and contain no actionable approvals. Discovery cannot replace them with live agent state. Canonical identity scopes reader state, but does not itself merge saved/live history or establish synchronization coverage. Older bridges still show live content; no identity means no new snapshot and an honest storage limitation, not a fabricated cache entry.

## Acceptance and remaining evidence

- Hermetic bridge checks: identity stable on append, replacement session separated, provider namespace separated, invalid/oversized header has no key, legacy response remains compatible, scrollback has no key; preserve the existing exact-session/process ownership tests and mandatory resource soak.
- Hermetic Android checks: response ownership/type/source rejection, legacy readability without saving, same-pane canonical separation, moved-pane continuity, machine isolation, reconstruction, read-only actions, discovery isolation, corruption and preservation/bounds.
- The first full bridge attempt passed 51 unit tests but failed the structured-conversation integration request with a connection error; the exact focused diagnostic subsequently passed. The fixture had a source-visible reserve/release readiness race: TCP success could observe another parallel server. Readiness now requires the spawned root's machine ID, with child stderr retained and no expanded deadlines, retries, skipped tests or serial-only substitute. Full parallel gate reproduction remains required; the focused pass alone does not prove the failure's cause.
- Full `make check-bridge` and `make validate-android` are required for changed inputs. Source/build/unit checks are not native acceptance or independent verification.
- Still required: stale in-flight save/read coverage, consuming native saved-reader/restart/selection journeys, artifact identity and owner visual acceptance. Existing display/tail failures and Attention/delivery gaps remain open.
- No bridge deployment, push, release or owner-session manipulation follows from local gates. Old deployed bridges will not acquire this field until a separately authorized rollout.
