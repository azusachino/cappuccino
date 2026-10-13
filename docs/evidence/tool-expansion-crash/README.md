# Tool-expansion crash and test-device incident

## Observed failure

The owner's Android crash buffer repeatedly reports
`IllegalStateException: No local MarkdownColors`. The stack passes through
`MarkdownCodeTopBar`, `MarkdownCodeBackground`, `NativeCodeBlock` and
`ConversationDetails.ToolDetailSection` when expanding a shell tool.

A focused `ToolExpansionCrashTest` uses synthetic input and a standalone
Compose host, not owner sessions. Its first device run did **not** reproduce
the app exception: Espresso failed during setup with
`NoSuchMethodException: android.hardware.input.InputManager.getInstance`.
That harness failure is not a red app regression or acceptance evidence.
Dependency inspection resolved Compose's transitive Espresso to 3.5.0.
Android test dependencies now explicitly select Espresso 3.7.0: the
[official release notes](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0)
record replacing reflective `InputManager.getInstance` with `getSystemService`.
This is test-only; production dependencies and assertions are not weakened.

## Device-safety incident

The Gradle connected-test runner then uninstalled the target app and removed
its local data on the owner's OnePlus/API 36 phone. This violated the required
preservation of profiles, settings and saved history. Reinstalling the APK did
not recover that data. No remote agent/session operation was performed.

The owner authorized restoring the two previously verified machine
connections. They were recreated with new local profile IDs, their recorded
expected machine identities, and verified as Connected in the native machine
list. Deleted settings, drafts, reader state and saved history are not claimed
as recovered; any subsequent capture is new history.

Further automated connected tests must use an isolated task-owned emulator,
never the owner's installed app. `adb install -r` preserves existing data, but
cannot undo a preceding test-runner uninstall. Do not assume a connected-test
cleanup preserves target application data.

## Tool-only chat updates

The owner also reported the main chat reader moving while only tool calls
arrived, without an agent answer. Passive Mini metadata checks found tool-only
canonical turns that also contained reasoning parts; no transcript text was
published. `conversationFollowContent` treated those parts as follow-worthy.

The new `reasoningWithToolsDoesNotTriggerFollowingWithoutAnAnswer` unit test
failed against that implementation (one failure among eight focused tests).
The follow projection now excludes reasoning while leaving it inspectable.
Actual text replies and explicit aborted-response outcomes retain their
existing follow semantics; tool history is not discarded. A combined native
filter ran only the expansion test, as confirmed by its XML test count; it
provides no viewport evidence. The viewport method is being run separately
with an explicit one-test completion assertion. That run and its explicit-Follow
retry failed before adding any tool update: the anchor was beyond `MessageBody`'s
six-line preview, not visible chat content. These fixture failures do not
diagnose initial following or tool-only movement. The corrected fixture uses
separate short replies to create an overflowing reader with a visible final
anchor. Its before/after position assertions remain unchanged. The existing
historical display/tail regressions remain untouched and unresolved.

The short-reply fixture also failed at its initial anchor. A synthetic screenshot
showed replies 18–29 visible while the last replies remained below the viewport.
Follow now observes rendered text-body size changes so asynchronous Markdown
layout can settle at the end. Tool/reasoning layouts do not produce these
callbacks. Paused readers are not repositioned, and layout-only changes do not
create a new-answer notification.

The earlier full Android gate passed, including 155 unit tests with zero
failures, errors or skips. The additional text-layout repair requires a fresh
gate and loaded-reader viewport result. The basic viewport regression then
passed (one test, zero failures/errors/skips), preserving the anchor after a
tool/reasoning update. The final expanded test also passed: the latest-reading
anchor stays fixed, paused reading does not move or offer a false Jump control
when reasoning/tool results change, and a genuine new answer offers Jump and
can be followed.

A second focused test reproduced older-history trimming being treated as a
new answer (one failure among nine tests). The follow projection now tracks
only the latest meaningful answer/outcome, so removing an older prefix is not
a new follow signal. Retention limits themselves are unchanged. A subsequent
full gate and expanded native test passed. The full unit suite contains 156
tests with zero failures, errors or skips. This is scoped evidence, not a pass
for the retained full native display/tail matrix.

## Final phone installation

The gated follow/layout build was installed using `adb install -r` and opened.
APK SHA256:
`0c7cbacd5551f900d775acc683b07cc2253c74c14cf25928dbd291528ea57de9`.
A private backup was validated before installation; all four existing private
files compared byte-for-byte afterwards. This preserves the newly restored
profiles and subsequent local data, not the original deleted data. Owner
confirmation of the live reader remains pending.

## Hotfix and evidence boundary

`NativeCodeBlock` now owns its Material surface, language label and native
copy action rather than using Markdown's code-header component outside its
providers. Exact backing text, native highlighting and Catppuccin colors are
retained; the copy action copies the supplied source, never executes it.

The first full Android validation passed. The hotfix APK was installed using
`adb install -r` after a private backup of the newly restored app data.
Before/after private-file comparisons passed byte-for-byte, including the two
restored profiles. This proves preservation during this installation, not
recovery of data deleted earlier. Further test-dependency changes require a
new gate, which passed after the follow fix and Espresso alignment. The isolated
expansion regression passed: one test, zero failures,
errors or skips. It expands synthetic shell arguments and verifies native
command text and both command/result copy controls outside Markdown context.
An earlier assertion matched two copy controls ambiguously and was corrected
to require the expected two controls, not skipped. Owner confirmation and the
full native matrix remain pending.

Private crash logs, failed test XML, backups and recovery UI evidence remain
in workstation scratch, not published transcript artifacts. Full native
regression and visual acceptance remain separate from this scoped hotfix.
