# Native Pi workspace prototype — accepted evidence record

Frozen, durable record of the owner-accepted synthetic native workspace prototype. The owner chose **Accept fit; proceed to D1** after inspecting the 12-original gallery and the Agy C7 independent technical source/criteria verification (PASS). The owner explicitly approved matching current-runtime evidence reuse for the independent verification WITHOUT another device journey.

This record is evidence only. It is **not** live Pi parity, not physical-device or TalkBack acceptance, not production adoption, not a merge/deploy/bridge authorization. The prototype source is a frozen synthetic fixture snapshot, not a production app adoption. PRs 29/31 remain open and unmerged.

## Identity

- Owning Git frozen base: `research/native-pi-agent-workspace` at `6d3463268b9fddaf5f8e29d8b92e618e7242064b` (clean).
- Common 25-file source manifest SHA-256 (relative to the prototype `android/` tree; includes `gradle/wrapper/gradle-wrapper.jar` `238e777f…c21abd5` — the parent/writer 24-count framing was wrong): `1a1a61e41d6da3c79354362abe62f1109c4694ba8c56883432affe8970aada0c`.
- Wrapper JAR: `gradle/wrapper/gradle-wrapper.jar`, SHA-256 `238e777fcddd7e34f9708186085def2abd6e08e658505b38718d79d74c21abd5`, included as the 25th entry of the common manifest. The prototype source tree is **not** part of the committed repository working tree; it exists as a frozen scratch workspace and is preserved verbatim in `archive/source.tar.gz` (exactly 26 regular files: the 25 manifest entries plus the manifest `source.sha256` at the archive root; canonical metadata, no absolute/`../` paths, no AppleDouble entries, no PAX headers; no `.gradle`, build outputs, SDK paths, `local.properties`, caches or APKs).

## Contents

| Path | What it is |
| --- | --- |
| `gallery/` | 12 byte-identical ORIGINAL curated PNGs plus `index.md` and full per-image `provenance.json` (hashes, dimensions, theme, font scale, run/method/raw XML, source manifest and APK hashes). |
| `evidence/prerequisite/` | Current prerequisite gate: 32 JVM tests green, compile main/test, `ktfmtCheck`, `lintDebug`, exit 0, gradle log, run meta, 5 JUnit XMLs, source manifest for the run. |
| `evidence/{short-light,short-dark,normal-light,normal-dark}/` | Four current UI runs, 13 tests each, 0 failures/errors/skips, exit 0; gradle log, run meta, instrumentation runner, JUnit XML, profile-after, source manifest per run. |
| `evidence/packaged-test-manifest-aapt2.txt` | daapt2-packaged test manifest: `androidx.test.runner.AndroidJUnitRunner`. |
| `review/agy-step-c7-report.md` | The Agy independent verification report as a rumdl-formatted **readable copy** (whitespace-only fixes; content unchanged). |
| `review/agy-step-c7-report-raw-original.txt` | The Agy C7 report **raw original bytes**, SHA-256 `1a88a195025672e17a41b79031e06bd2f2b8d4542daf594a7490e235dc44aa7f`. |
| `review/agy-step-c7-lead-note.md` | Required lead narrowing/corrections — read together with the report; see below. |
| `archive/source.tar.gz` | Frozen source snapshot, 26 regular files: 25 manifest entries + `source.sha256`; SHA-256 `d39d9991f7c89cd7908280c24d86ff6e465968c5eed4df614eb208675bac0822`. |

## Wrapper JAR correction (read before citing the report)

The report's 24-file count is off by one: the actual common manifest has **25 entries including the wrapper JAR**, while its SHA value is correct. The report's implication of a separate JAR byte-check was likewise overstated; no 25-independent-checks claim is made until the fresh C8 audit. See [review/agy-step-c7-lead-note.md](review/agy-step-c7-lead-note.md), the superseding correction.

## Performed vs reused (owner-approved)

**Performed by Agy (independent):** SHA-256 verification of the bundle source files against the common manifest (`all OK` at the time; the exact independent check count against the full 25-entry manifest, JAR included, is deferred to the fresh C8 audit); hash/dimension verification of all 12 PNGs (`12 OK`); line-by-line source audits (PrototypeApp Box minimum/handledAbsence/tail guard, PrototypeActivity URI provider and window flags, theme resources, PassiveMarkdown/PassivePolicy NoOpUriHandler and image fallback, StepCJourneyTest); inspection of logs, XMLs and provenance metadata.

**Reused (owner-approved, current-runtime matching):** the prerequisite gate (32/32 JVM tests, compile main/test, ktfmtCheck, lintDebug, exit 0) and the four UI runs (13 each, 0 failures/errors/skips, exit 0) with run names `141744-final-source-short-light`, `141814-final-source-short-dark`, `141118-normal-light`, `141158-normal-dark`. Profiles: short runs 320x640 @160 override (physical 1080x2340 @440), normal runs 1080x2340 @440; night per run; font scale 1.0. Evaluator: Gemini 3.8 Flash (Medium), runtime version UNKNOWN — evidence reuse, not a new device execution.

## Known limits (authoritative)

- No live Pi, physical-device or TalkBack proof; no live status-transition or bridge RPC parity. Status fixtures are distinct synthetic settled/heuristic/disconnected states.
- g4 (repeated same-index real-IME resize) was NOT run.
- The Box minimum guarantees intrinsic height **while composed**, not that a lazy item can never virtualize away.
- Image URL fallback was observed in **both static and streaming** paths when the native ALT resolver returned null. Do not claim all images lack ALT support or that static ALT is always retained.
- Production D1 must **preserve** the existing passive image accessibility contract; this prototype's fallback approval must not weaken existing assertions.
- Gradle 9 deprecation messages exist; no zero-warning claim. Toolchain errors/warnings are enforced normally.
- Earlier diagnostic failures occurred during development; this record makes no zero-historical-failure claim.
- Prior consuming journeys (32 JVM + 52 UI executions across Stage B/C) are recorded as prior current runtime, not rerun here.

## D1 handoff (not implemented here)

Owner-approved D1 candidate scope: keep an existing Markdown rendering dependency (0.45 Mike Penz, Apache-2.0) for rendering only, inside the production app's existing Markdown boundary, native M3. Must preserve: component identity, passive URL and image-ALT accessibility, disclosure, redaction, device-local Activity timezone, refresh, actual-tail manual anchors, connectivity, multiline composer, reset. No terminal, no WebView, no backend, no broad chat SDK, no custom Markdown renderer. D2 roster, D3 chrome and optional bridge are later. D1 is NOT implemented in this record.
