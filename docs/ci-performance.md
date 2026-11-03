# CI performance round

## Baseline and measurement

Source: `48ae174b013ce88d53726ebd267a92876b908094`. Retrieved up to the latest 50 runs with `gh run list --repo azusachino/cappuccino --limit 50` and jobs with `gh api repos/azusachino/cappuccino/actions/runs/<id>/jobs?per_page=100`. The sample contains 289 jobs, including a Dependabot run. Failures, cancellations and skipped jobs remain visible; successful-duration summaries exclude them.

Queue means job creation to start. Upstream wait means workflow creation to job creation, including dependency delay. Preparation is job start to the first validation/work step; work spans validation through the last functional check. Rust setup or emulator startup inside that span remains included, because historical grouped steps cannot be split accurately from the job API. Artifact upload and device cleanup are outside the work interval. Job total includes cleanup and excludes queue; timestamps have one-second precision.

## Comparable baselines

| Platform | PR successful jobs / median | Main-push successful jobs / median |
| --- | --- | --- |
| Apple | 13 / 118s | 13 / 694s |
| Android | 18 / 197s | 11 / 445s |
| Bridge | 16 / 64s | 17 / 61s |

PR and main coverage differ: main also runs the iPhone simulator journey. The historical Android medians include revisions before the current journey, so they are context, not an equivalent-coverage A/B comparison. The latest main [run 41415865158](https://github.com/azusachino/cappuccino/actions/runs/41415865158) is the closer functional baseline: Apple 756s, Android 513s. The preceding successful Android PR job took 519s. Its main Android log records a 128s Rust bridge compilation inside the emulator journey, separately from the approximately 37s emulator boot and 98s Gradle connected-test invocation.

## Changes and coverage

- Cancel only superseded PR runs; use unique run IDs for main and manual events, preserving both pending and running commits.
- Skip PRs changing only explicit root docs and `docs/`. No required status checks were configured: main protection exists but has no required-status section, and active branch rules are empty. If requirements change, replace workflow-level filtering with an always-running status gate first. Keep the existing `make check` status and platform conditions.
- Scope Apple mise installation to xcodegen and rumdl, excluding xtool (not used by validation). Scope the fallback docs job to rumdl only. No tools are built from source as a replacement.
- Use the existing bridge cache identity (`shared-key: bridge`) in Android after selecting the same stable Rust toolchain. Android restores it read-only (`save-if: false`); the bridge job remains the writer. The [rust-cache action inputs](https://github.com/Swatinem/rust-cache/blob/v2/action.yml) document shared keys and restore-only operation.
- Retain all platform format, tests, lint, build and UI-journey commands; retain artifact evidence and task-owned-device cleanup. Apple and Android jobs remain parallel. No product source or simulator/emulator specification is changed.

The cache API reported 11,511,552,473 bytes across 18 entries. Adding another large Android Rust writer would worsen cache pressure. This change reuses the bridge namespace instead; no existing cache is deleted. Main or PR run measurements must confirm a compatible cache hit and the actual net saving.

## Local verification and limits

An isolated worktree preserves the owner's feature checkout and untracked PNG. Its machine-specific SDK path lives only in ignored `.env.local`. Baseline `mise exec -- make validate` passed: Android format/unit/lint/APK validation, all hermetic bridge tests, release example and Markdown. This is CI-only work; Apple implementation remains deferred under the repository's default acceptance policy. Local Xcode is 27.2 while hosted CI explicitly uses 26.6; no hosted runner fallback is introduced.

Actionlint and diff checks passed. The post-edit default `mise exec -- make validate` gate passed. First-pass source `0623c1da694e85a0e91d0123412c169357367f78` is under [PR #100](https://github.com/azusachino/cappuccino/pull/100). [PR run 41613409233, attempt 1](https://github.com/azusachino/cappuccino/actions/runs/41613409233/attempts/1) passed: Apple 149s, Android 371s and bridge 68s. Android restored the existing bridge key with an exact match in 17s; bridge compilation inside the journey took 18.10s versus 128s in the equivalent-source main baseline. The Android journey step took 207s versus 312s baseline. Its cache post-step took 0s because Android is read-only. The 371s job is 28.5% below the latest comparable PR's 519s, but runner and Gradle-cache state vary; historical medians span older coverage and are not an equivalent comparison.

A warm PR rerun and the full manual simulator journey ran on the same source. [Manual run 41613407671](https://github.com/azusachino/cappuccino/actions/runs/41613407671) passed every job, including the iPhone shell journey on a task-owned simulator and its evidence upload. [PR attempt 2](https://github.com/azusachino/cappuccino/actions/runs/41613409233/attempts/2) failed its Android journey on two pre-existing `MarkdownTextUiTest` semantics-tree assertions (compose unmerged-tree finders) on the same commit that had already passed twice; issue [#101](https://github.com/azusachino/cappuccino/issues/101) records the artifact evidence. No warm PR timing is claimed, and no test was changed, retried or skipped in response.

Second-pass review found the Android validation still took 129s. Replace setup-java's dependency-only Gradle cache with native setup-gradle v5 and enable task caching through CI's `GRADLE_OPTS`. This avoids overlapping Gradle caches and leaves connected instrumentation tests uncached. Local gates and hosted measurements for this refinement are pending.

## Every baseline job

Times are seconds. n/a indicates a skipped or missing timestamp interval. The work column includes grouped setup where it cannot be separated faithfully.

| Run | Event | Job | Result | Upstream wait | Queue | Preparation | Work | Job total |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [41471801226](https://github.com/azusachino/cappuccino/actions/runs/41471801226) | pull_request | Detect changes | success | 4 | 3 | 4 | 1 | 8 |
| [41471801226](https://github.com/azusachino/cappuccino/actions/runs/41471801226) | pull_request | Bridge (Rust) | success | 16 | 1 | 18 | 78 | 109 |
| [41471801226](https://github.com/azusachino/cappuccino/actions/runs/41471801226) | pull_request | Apple platform (iOS) | skipped | 16 | 0 | n/a | n/a | n/a |
| [41471801226](https://github.com/azusachino/cappuccino/actions/runs/41471801226) | pull_request | Android platform | skipped | 16 | 0 | n/a | n/a | n/a |
| [41471801226](https://github.com/azusachino/cappuccino/actions/runs/41471801226) | pull_request | Documentation (rumdl) | skipped | 16 | 0 | n/a | n/a | n/a |
| [41471801226](https://github.com/azusachino/cappuccino/actions/runs/41471801226) | pull_request | make check | success | 126 | 2 | 1 | 0 | 8 |
| [41431370002](https://github.com/azusachino/cappuccino/actions/runs/41431370002) | dynamic | Dependabot | success | 2 | 3 | 2 | 51 | 55 |
| [41415865158](https://github.com/azusachino/cappuccino/actions/runs/41415865158) | push | Detect changes | success | 1 | 2 | 2 | 0 | 4 |
| [41415865158](https://github.com/azusachino/cappuccino/actions/runs/41415865158) | push | Bridge (Rust) | success | 7 | 12 | 24 | 47 | 73 |
| [41415865158](https://github.com/azusachino/cappuccino/actions/runs/41415865158) | push | Apple platform (iOS) | success | 7 | 3 | 16 | 717 | 756 |
| [41415865158](https://github.com/azusachino/cappuccino/actions/runs/41415865158) | push | Android platform | success | 7 | 2 | 13 | 497 | 513 |
| [41415865158](https://github.com/azusachino/cappuccino/actions/runs/41415865158) | push | Documentation (rumdl) | skipped | 7 | 0 | n/a | n/a | 0 |
| [41415865158](https://github.com/azusachino/cappuccino/actions/runs/41415865158) | push | make check | success | 767 | 7 | 1 | 0 | 3 |
| [41413344965](https://github.com/azusachino/cappuccino/actions/runs/41413344965) | pull_request | Detect changes | success | 1 | 2 | 3 | 0 | 5 |
| [41413344965](https://github.com/azusachino/cappuccino/actions/runs/41413344965) | pull_request | Android platform | success | 8 | 2 | 16 | 491 | 519 |
| [41413344965](https://github.com/azusachino/cappuccino/actions/runs/41413344965) | pull_request | Bridge (Rust) | success | 8 | 2 | 19 | 46 | 87 |
| [41413344965](https://github.com/azusachino/cappuccino/actions/runs/41413344965) | pull_request | Documentation (rumdl) | skipped | 8 | 0 | n/a | n/a | 0 |
| [41413344965](https://github.com/azusachino/cappuccino/actions/runs/41413344965) | pull_request | Apple platform (iOS) | skipped | 9 | 0 | n/a | n/a | n/a |
| [41413344965](https://github.com/azusachino/cappuccino/actions/runs/41413344965) | pull_request | make check | success | 529 | 6 | 1 | 0 | 3 |
| [41377809026](https://github.com/azusachino/cappuccino/actions/runs/41377809026) | push | Detect changes | success | 0 | 2 | 4 | 0 | 7 |
| [41377809026](https://github.com/azusachino/cappuccino/actions/runs/41377809026) | push | Android platform | success | 9 | 2 | 15 | 437 | 457 |
| [41377809026](https://github.com/azusachino/cappuccino/actions/runs/41377809026) | push | Apple platform (iOS) | success | 9 | 2 | 20 | 658 | 694 |
| [41377809026](https://github.com/azusachino/cappuccino/actions/runs/41377809026) | push | Bridge (Rust) | success | 9 | 2 | 16 | 36 | 54 |
| [41377809026](https://github.com/azusachino/cappuccino/actions/runs/41377809026) | push | Documentation (rumdl) | skipped | 9 | 0 | n/a | n/a | 0 |
| [41377809026](https://github.com/azusachino/cappuccino/actions/runs/41377809026) | push | make check | success | 705 | 2 | 1 | 1 | 4 |
| [41376640622](https://github.com/azusachino/cappuccino/actions/runs/41376640622) | pull_request | Detect changes | success | 0 | 2 | 3 | 0 | 5 |
| [41376640622](https://github.com/azusachino/cappuccino/actions/runs/41376640622) | pull_request | Android platform | success | 7 | 2 | 13 | 352 | 369 |
| [41376640622](https://github.com/azusachino/cappuccino/actions/runs/41376640622) | pull_request | Bridge (Rust) | skipped | 8 | 0 | n/a | n/a | n/a |
| [41376640622](https://github.com/azusachino/cappuccino/actions/runs/41376640622) | pull_request | Documentation (rumdl) | skipped | 8 | 0 | n/a | n/a | n/a |
| [41376640622](https://github.com/azusachino/cappuccino/actions/runs/41376640622) | pull_request | Apple platform (iOS) | skipped | 8 | 0 | n/a | n/a | n/a |
| [41376640622](https://github.com/azusachino/cappuccino/actions/runs/41376640622) | pull_request | make check | success | 378 | 2 | 1 | 0 | 3 |
| [41318035328](https://github.com/azusachino/cappuccino/actions/runs/41318035328) | push | Detect changes | success | 0 | 2 | 4 | 0 | 7 |
| [41318035328](https://github.com/azusachino/cappuccino/actions/runs/41318035328) | push | Android platform | success | 9 | 2 | 13 | 375 | 391 |
| [41318035328](https://github.com/azusachino/cappuccino/actions/runs/41318035328) | push | Bridge (Rust) | success | 9 | 2 | 14 | 41 | 57 |
| [41318035328](https://github.com/azusachino/cappuccino/actions/runs/41318035328) | push | Apple platform (iOS) | success | 9 | 3 | 74 | 782 | 882 |
| [41318035328](https://github.com/azusachino/cappuccino/actions/runs/41318035328) | push | Documentation (rumdl) | skipped | 10 | 0 | n/a | n/a | n/a |
| [41318035328](https://github.com/azusachino/cappuccino/actions/runs/41318035328) | push | make check | success | 894 | 2 | 1 | 0 | 4 |
| [41314568353](https://github.com/azusachino/cappuccino/actions/runs/41314568353) | pull_request | Detect changes | success | 0 | 2 | 4 | 1 | 7 |
| [41314568353](https://github.com/azusachino/cappuccino/actions/runs/41314568353) | pull_request | Android platform | success | 9 | 2 | 16 | 505 | 526 |
| [41314568353](https://github.com/azusachino/cappuccino/actions/runs/41314568353) | pull_request | Apple platform (iOS) | success | 9 | 2 | 13 | 164 | 182 |
| [41314568353](https://github.com/azusachino/cappuccino/actions/runs/41314568353) | pull_request | Documentation (rumdl) | skipped | 9 | 0 | n/a | n/a | 0 |
| [41314568353](https://github.com/azusachino/cappuccino/actions/runs/41314568353) | pull_request | Bridge (Rust) | skipped | 9 | 0 | n/a | n/a | 0 |
| [41314568353](https://github.com/azusachino/cappuccino/actions/runs/41314568353) | pull_request | make check | success | 537 | 2 | 1 | 0 | 4 |
| [41302315821](https://github.com/azusachino/cappuccino/actions/runs/41302315821) | pull_request | Detect changes | success | 0 | 2 | 2 | 0 | 4 |
| [41302315821](https://github.com/azusachino/cappuccino/actions/runs/41302315821) | pull_request | Android platform | failure | 6 | 2 | 15 | 490 | 509 |
| [41302315821](https://github.com/azusachino/cappuccino/actions/runs/41302315821) | pull_request | Apple platform (iOS) | success | 6 | 2 | 9 | 104 | 118 |
| [41302315821](https://github.com/azusachino/cappuccino/actions/runs/41302315821) | pull_request | Bridge (Rust) | skipped | 7 | 0 | n/a | n/a | n/a |
| [41302315821](https://github.com/azusachino/cappuccino/actions/runs/41302315821) | pull_request | Documentation (rumdl) | skipped | 7 | 0 | n/a | n/a | n/a |
| [41302315821](https://github.com/azusachino/cappuccino/actions/runs/41302315821) | pull_request | make check | failure | 517 | 2 | 1 | 0 | 3 |
| [41291493451](https://github.com/azusachino/cappuccino/actions/runs/41291493451) | push | Detect changes | success | 1 | 1 | 3 | 0 | 5 |
| [41291493451](https://github.com/azusachino/cappuccino/actions/runs/41291493451) | push | Apple platform (iOS) | success | 7 | 3 | 28 | 963 | 1014 |
| [41291493451](https://github.com/azusachino/cappuccino/actions/runs/41291493451) | push | Bridge (Rust) | success | 7 | 2 | 20 | 40 | 63 |
| [41291493451](https://github.com/azusachino/cappuccino/actions/runs/41291493451) | push | Android platform | success | 7 | 2 | 13 | 407 | 424 |
| [41291493451](https://github.com/azusachino/cappuccino/actions/runs/41291493451) | push | Documentation (rumdl) | skipped | 7 | 0 | n/a | n/a | 0 |
| [41291493451](https://github.com/azusachino/cappuccino/actions/runs/41291493451) | push | make check | success | 1024 | 2 | 1 | 1 | 4 |
| [41290539847](https://github.com/azusachino/cappuccino/actions/runs/41290539847) | pull_request | Detect changes | success | 1 | 2 | n/a | 0 | 5 |
| [41290539847](https://github.com/azusachino/cappuccino/actions/runs/41290539847) | pull_request | Android platform | success | 9 | 2 | n/a | 485 | 505 |
| [41290539847](https://github.com/azusachino/cappuccino/actions/runs/41290539847) | pull_request | Bridge (Rust) | success | 9 | 1 | n/a | 33 | 54 |
| [41290539847](https://github.com/azusachino/cappuccino/actions/runs/41290539847) | pull_request | Apple platform (iOS) | success | 9 | 1 | n/a | 93 | 110 |
| [41290539847](https://github.com/azusachino/cappuccino/actions/runs/41290539847) | pull_request | Documentation (rumdl) | skipped | 9 | 0 | n/a | n/a | 0 |
| [41290539847](https://github.com/azusachino/cappuccino/actions/runs/41290539847) | pull_request | make check | success | 516 | 2 | n/a | 0 | 2 |
| [41284090695](https://github.com/azusachino/cappuccino/actions/runs/41284090695) | pull_request | Detect changes | success | 1 | 2 | 3 | 0 | 6 |
| [41284090695](https://github.com/azusachino/cappuccino/actions/runs/41284090695) | pull_request | Bridge (Rust) | success | 9 | 2 | 16 | 45 | 63 |
| [41284090695](https://github.com/azusachino/cappuccino/actions/runs/41284090695) | pull_request | Apple platform (iOS) | success | 9 | 2 | 12 | 97 | 113 |
| [41284090695](https://github.com/azusachino/cappuccino/actions/runs/41284090695) | pull_request | Android platform | success | 9 | 2 | 10 | 348 | 361 |
| [41284090695](https://github.com/azusachino/cappuccino/actions/runs/41284090695) | pull_request | Documentation (rumdl) | skipped | 9 | 0 | n/a | n/a | 0 |
| [41284090695](https://github.com/azusachino/cappuccino/actions/runs/41284090695) | pull_request | make check | success | 372 | 2 | 1 | 0 | 3 |
| [41278576169](https://github.com/azusachino/cappuccino/actions/runs/41278576169) | push | Detect changes | success | 1 | 1 | 3 | 1 | 6 |
| [41278576169](https://github.com/azusachino/cappuccino/actions/runs/41278576169) | push | Android platform | success | 8 | 2 | 15 | 405 | 424 |
| [41278576169](https://github.com/azusachino/cappuccino/actions/runs/41278576169) | push | Apple platform (iOS) | success | 8 | 2 | 53 | 659 | 729 |
| [41278576169](https://github.com/azusachino/cappuccino/actions/runs/41278576169) | push | Bridge (Rust) | success | 8 | 2 | 17 | 33 | 52 |
| [41278576169](https://github.com/azusachino/cappuccino/actions/runs/41278576169) | push | Documentation (rumdl) | skipped | 9 | 0 | n/a | n/a | n/a |
| [41278576169](https://github.com/azusachino/cappuccino/actions/runs/41278576169) | push | make check | success | 739 | 1 | 1 | 0 | 3 |
| [41278448643](https://github.com/azusachino/cappuccino/actions/runs/41278448643) | pull_request | Detect changes | success | 0 | 2 | 2 | 1 | 5 |
| [41278448643](https://github.com/azusachino/cappuccino/actions/runs/41278448643) | pull_request | Apple platform (iOS) | success | 7 | 2 | 11 | 80 | 94 |
| [41278448643](https://github.com/azusachino/cappuccino/actions/runs/41278448643) | pull_request | Android platform | skipped | 7 | 0 | n/a | n/a | 0 |
| [41278448643](https://github.com/azusachino/cappuccino/actions/runs/41278448643) | pull_request | Documentation (rumdl) | skipped | 7 | 0 | n/a | n/a | 0 |
| [41278448643](https://github.com/azusachino/cappuccino/actions/runs/41278448643) | pull_request | Bridge (Rust) | skipped | 7 | 0 | n/a | n/a | 0 |
| [41278448643](https://github.com/azusachino/cappuccino/actions/runs/41278448643) | pull_request | make check | success | 103 | 2 | 1 | 0 | 3 |
| [41268027119](https://github.com/azusachino/cappuccino/actions/runs/41268027119) | push | Detect changes | success | 132 | 2 | 2 | 0 | 4 |
| [41268027119](https://github.com/azusachino/cappuccino/actions/runs/41268027119) | push | Bridge (Rust) | success | 139 | 1 | 15 | 46 | 63 |
| [41268027119](https://github.com/azusachino/cappuccino/actions/runs/41268027119) | push | Apple platform (iOS) | success | 139 | 2 | 23 | 522 | 564 |
| [41268027119](https://github.com/azusachino/cappuccino/actions/runs/41268027119) | push | Android platform | success | 139 | 1 | 14 | 427 | 445 |
| [41268027119](https://github.com/azusachino/cappuccino/actions/runs/41268027119) | push | Documentation (rumdl) | skipped | 139 | 0 | n/a | n/a | 0 |
| [41268027119](https://github.com/azusachino/cappuccino/actions/runs/41268027119) | push | make check | success | 705 | 2 | 1 | 0 | 2 |
| [41267815812](https://github.com/azusachino/cappuccino/actions/runs/41267815812) | pull_request | Detect changes | success | 1 | 2 | 3 | 0 | 6 |
| [41267815812](https://github.com/azusachino/cappuccino/actions/runs/41267815812) | pull_request | Apple platform (iOS) | success | 9 | 3 | 15 | 135 | 156 |
| [41267815812](https://github.com/azusachino/cappuccino/actions/runs/41267815812) | pull_request | Bridge (Rust) | skipped | 9 | 0 | n/a | n/a | 0 |
| [41267815812](https://github.com/azusachino/cappuccino/actions/runs/41267815812) | pull_request | Android platform | skipped | 9 | 0 | n/a | n/a | 0 |
| [41267815812](https://github.com/azusachino/cappuccino/actions/runs/41267815812) | pull_request | Documentation (rumdl) | skipped | 10 | 0 | n/a | n/a | n/a |
| [41267815812](https://github.com/azusachino/cappuccino/actions/runs/41267815812) | pull_request | make check | success | 168 | 2 | 1 | 0 | 10 |
| [41267639376](https://github.com/azusachino/cappuccino/actions/runs/41267639376) | push | Detect changes | success | 87 | 2 | 2 | 0 | 4 |
| [41267639376](https://github.com/azusachino/cappuccino/actions/runs/41267639376) | push | Apple platform (iOS) | cancelled | 93 | 3 | 27 | 239 | 380 |
| [41267639376](https://github.com/azusachino/cappuccino/actions/runs/41267639376) | push | Bridge (Rust) | success | 93 | 2 | 18 | 45 | 66 |
| [41267639376](https://github.com/azusachino/cappuccino/actions/runs/41267639376) | push | Android platform | cancelled | 93 | 2 | 14 | 240 | 256 |
| [41267639376](https://github.com/azusachino/cappuccino/actions/runs/41267639376) | push | Documentation (rumdl) | skipped | 93 | 0 | n/a | n/a | 0 |
| [41267639376](https://github.com/azusachino/cappuccino/actions/runs/41267639376) | push | make check | failure | 476 | 1 | 1 | 0 | 3 |
| [41267446410](https://github.com/azusachino/cappuccino/actions/runs/41267446410) | pull_request | Detect changes | success | 0 | 2 | 3 | 0 | 6 |
| [41267446410](https://github.com/azusachino/cappuccino/actions/runs/41267446410) | pull_request | Apple platform (iOS) | success | 8 | 2 | 16 | 118 | 140 |
| [41267446410](https://github.com/azusachino/cappuccino/actions/runs/41267446410) | pull_request | Android platform | skipped | 8 | 0 | n/a | n/a | 0 |
| [41267446410](https://github.com/azusachino/cappuccino/actions/runs/41267446410) | pull_request | Bridge (Rust) | skipped | 8 | 0 | n/a | n/a | 0 |
| [41267446410](https://github.com/azusachino/cappuccino/actions/runs/41267446410) | pull_request | Documentation (rumdl) | skipped | 8 | 0 | n/a | n/a | 0 |
| [41267446410](https://github.com/azusachino/cappuccino/actions/runs/41267446410) | pull_request | make check | success | 150 | 2 | 1 | 0 | 3 |
| [41267069158](https://github.com/azusachino/cappuccino/actions/runs/41267069158) | push | Detect changes | success | 1 | 2 | 6 | 0 | 8 |
| [41267069158](https://github.com/azusachino/cappuccino/actions/runs/41267069158) | push | Android platform | success | 11 | 2 | 11 | 415 | 430 |
| [41267069158](https://github.com/azusachino/cappuccino/actions/runs/41267069158) | push | Apple platform (iOS) | cancelled | 11 | 2 | 22 | 523 | 608 |
| [41267069158](https://github.com/azusachino/cappuccino/actions/runs/41267069158) | push | Bridge (Rust) | success | 11 | 2 | 13 | 45 | 60 |
| [41267069158](https://github.com/azusachino/cappuccino/actions/runs/41267069158) | push | Documentation (rumdl) | skipped | 11 | 0 | n/a | n/a | 0 |
| [41267069158](https://github.com/azusachino/cappuccino/actions/runs/41267069158) | push | make check | failure | 621 | 2 | 0 | 0 | 2 |
| [41266514880](https://github.com/azusachino/cappuccino/actions/runs/41266514880) | pull_request | Detect changes | success | 0 | 1 | 2 | 1 | 5 |
| [41266514880](https://github.com/azusachino/cappuccino/actions/runs/41266514880) | pull_request | Android platform | success | 6 | 2 | 15 | 482 | 502 |
| [41266514880](https://github.com/azusachino/cappuccino/actions/runs/41266514880) | pull_request | Apple platform (iOS) | success | 6 | 3 | 15 | 143 | 164 |
| [41266514880](https://github.com/azusachino/cappuccino/actions/runs/41266514880) | pull_request | Bridge (Rust) | success | 6 | 2 | 18 | 45 | 66 |
| [41266514880](https://github.com/azusachino/cappuccino/actions/runs/41266514880) | pull_request | Documentation (rumdl) | skipped | 6 | 0 | n/a | n/a | 0 |
| [41266514880](https://github.com/azusachino/cappuccino/actions/runs/41266514880) | pull_request | make check | success | 510 | 1 | 1 | 0 | 3 |
| [41266376889](https://github.com/azusachino/cappuccino/actions/runs/41266376889) | push | Detect changes | success | 32 | 2 | 3 | 0 | 5 |
| [41266376889](https://github.com/azusachino/cappuccino/actions/runs/41266376889) | push | Android platform | success | 39 | 2 | 13 | 408 | 425 |
| [41266376889](https://github.com/azusachino/cappuccino/actions/runs/41266376889) | push | Bridge (Rust) | success | 39 | 2 | 20 | 36 | 58 |
| [41266376889](https://github.com/azusachino/cappuccino/actions/runs/41266376889) | push | Apple platform (iOS) | success | 39 | 3 | 19 | 462 | 507 |
| [41266376889](https://github.com/azusachino/cappuccino/actions/runs/41266376889) | push | Documentation (rumdl) | skipped | 40 | 0 | n/a | n/a | n/a |
| [41266376889](https://github.com/azusachino/cappuccino/actions/runs/41266376889) | push | make check | success | 549 | 3 | 1 | 0 | 3 |
| [41266243767](https://github.com/azusachino/cappuccino/actions/runs/41266243767) | pull_request | Detect changes | success | 1 | 2 | 3 | 0 | 5 |
| [41266243767](https://github.com/azusachino/cappuccino/actions/runs/41266243767) | pull_request | Apple platform (iOS) | success | 8 | 3 | 14 | 77 | 96 |
| [41266243767](https://github.com/azusachino/cappuccino/actions/runs/41266243767) | pull_request | Android platform | skipped | 9 | 0 | n/a | n/a | n/a |
| [41266243767](https://github.com/azusachino/cappuccino/actions/runs/41266243767) | pull_request | Bridge (Rust) | skipped | 9 | 0 | n/a | n/a | n/a |
| [41266243767](https://github.com/azusachino/cappuccino/actions/runs/41266243767) | pull_request | Documentation (rumdl) | skipped | 9 | 0 | n/a | n/a | n/a |
| [41266243767](https://github.com/azusachino/cappuccino/actions/runs/41266243767) | pull_request | make check | success | 107 | 2 | 1 | 0 | 4 |
| [41266081952](https://github.com/azusachino/cappuccino/actions/runs/41266081952) | push | Detect changes | success | 50 | 2 | 2 | 1 | 5 |
| [41266081952](https://github.com/azusachino/cappuccino/actions/runs/41266081952) | push | Apple platform (iOS) | cancelled | 57 | 2 | 59 | 178 | 249 |
| [41266081952](https://github.com/azusachino/cappuccino/actions/runs/41266081952) | push | Bridge (Rust) | success | 57 | 1 | 14 | 42 | 57 |
| [41266081952](https://github.com/azusachino/cappuccino/actions/runs/41266081952) | push | Android platform | cancelled | 57 | 2 | 13 | 209 | 225 |
| [41266081952](https://github.com/azusachino/cappuccino/actions/runs/41266081952) | push | Documentation (rumdl) | skipped | 57 | 0 | n/a | n/a | 0 |
| [41266081952](https://github.com/azusachino/cappuccino/actions/runs/41266081952) | push | make check | failure | 308 | 2 | 1 | 0 | 3 |
| [41265956157](https://github.com/azusachino/cappuccino/actions/runs/41265956157) | pull_request | Detect changes | success | 0 | 2 | 2 | 1 | 4 |
| [41265956157](https://github.com/azusachino/cappuccino/actions/runs/41265956157) | pull_request | Apple platform (iOS) | success | 6 | 3 | 15 | 75 | 94 |
| [41265956157](https://github.com/azusachino/cappuccino/actions/runs/41265956157) | pull_request | Bridge (Rust) | skipped | 7 | 0 | n/a | n/a | n/a |
| [41265956157](https://github.com/azusachino/cappuccino/actions/runs/41265956157) | pull_request | Documentation (rumdl) | skipped | 7 | 0 | n/a | n/a | n/a |
| [41265956157](https://github.com/azusachino/cappuccino/actions/runs/41265956157) | pull_request | Android platform | skipped | 7 | 0 | n/a | n/a | n/a |
| [41265956157](https://github.com/azusachino/cappuccino/actions/runs/41265956157) | pull_request | make check | success | 104 | 1 | 1 | 1 | 3 |
| [41265594296](https://github.com/azusachino/cappuccino/actions/runs/41265594296) | push | Detect changes | success | 0 | 2 | 4 | 0 | 7 |
| [41265594296](https://github.com/azusachino/cappuccino/actions/runs/41265594296) | push | Android platform | cancelled | 9 | 2 | 13 | 408 | 424 |
| [41265594296](https://github.com/azusachino/cappuccino/actions/runs/41265594296) | push | Apple platform (iOS) | cancelled | 9 | 3 | 28 | 404 | 463 |
| [41265594296](https://github.com/azusachino/cappuccino/actions/runs/41265594296) | push | Bridge (Rust) | success | 9 | 12 | 13 | 42 | 63 |
| [41265594296](https://github.com/azusachino/cappuccino/actions/runs/41265594296) | push | Documentation (rumdl) | skipped | 9 | 0 | n/a | n/a | 0 |
| [41265594296](https://github.com/azusachino/cappuccino/actions/runs/41265594296) | push | make check | failure | 475 | 2 | 1 | 0 | 3 |
| [41265578540](https://github.com/azusachino/cappuccino/actions/runs/41265578540) | pull_request | Detect changes | success | 0 | 3 | 3 | 1 | 7 |
| [41265578540](https://github.com/azusachino/cappuccino/actions/runs/41265578540) | pull_request | Apple platform (iOS) | success | 10 | 2 | 12 | 60 | 77 |
| [41265578540](https://github.com/azusachino/cappuccino/actions/runs/41265578540) | pull_request | Documentation (rumdl) | skipped | 10 | 0 | n/a | n/a | 0 |
| [41265578540](https://github.com/azusachino/cappuccino/actions/runs/41265578540) | pull_request | Android platform | skipped | 10 | 0 | n/a | n/a | 0 |
| [41265578540](https://github.com/azusachino/cappuccino/actions/runs/41265578540) | pull_request | Bridge (Rust) | skipped | 10 | 0 | n/a | n/a | 0 |
| [41265578540](https://github.com/azusachino/cappuccino/actions/runs/41265578540) | pull_request | make check | success | 89 | 2 | 1 | 0 | 3 |
| [41217708673](https://github.com/azusachino/cappuccino/actions/runs/41217708673) | push | Detect changes | success | 182 | 2 | 4 | 0 | 7 |
| [41217708673](https://github.com/azusachino/cappuccino/actions/runs/41217708673) | push | Apple platform (iOS) | success | 191 | 3 | 36 | 543 | 607 |
| [41217708673](https://github.com/azusachino/cappuccino/actions/runs/41217708673) | push | Android platform | success | 191 | 2 | 17 | 482 | 504 |
| [41217708673](https://github.com/azusachino/cappuccino/actions/runs/41217708673) | push | Bridge (Rust) | success | 191 | 2 | 13 | 41 | 55 |
| [41217708673](https://github.com/azusachino/cappuccino/actions/runs/41217708673) | push | Documentation (rumdl) | skipped | 191 | 0 | n/a | n/a | 0 |
| [41217708673](https://github.com/azusachino/cappuccino/actions/runs/41217708673) | push | make check | success | 801 | 2 | 0 | 0 | 2 |
| [41217676850](https://github.com/azusachino/cappuccino/actions/runs/41217676850) | push | Detect changes | success | 1 | 1 | 3 | 0 | 5 |
| [41217676850](https://github.com/azusachino/cappuccino/actions/runs/41217676850) | push | Bridge (Rust) | cancelled | 7 | 2 | 23 | 39 | 64 |
| [41217676850](https://github.com/azusachino/cappuccino/actions/runs/41217676850) | push | Apple platform (iOS) | cancelled | 7 | 3 | 25 | 95 | 191 |
| [41217676850](https://github.com/azusachino/cappuccino/actions/runs/41217676850) | push | Android platform | cancelled | 7 | 2 | 13 | 15 | 32 |
| [41217676850](https://github.com/azusachino/cappuccino/actions/runs/41217676850) | push | Documentation (rumdl) | skipped | 8 | 0 | n/a | n/a | n/a |
| [41217676850](https://github.com/azusachino/cappuccino/actions/runs/41217676850) | push | make check | failure | 201 | 2 | 1 | 0 | 2 |
| [41217411434](https://github.com/azusachino/cappuccino/actions/runs/41217411434) | pull_request | Detect changes | success | 1 | 2 | 3 | 1 | 7 |
| [41217411434](https://github.com/azusachino/cappuccino/actions/runs/41217411434) | pull_request | Android platform | success | 10 | 2 | 14 | 133 | 150 |
| [41217411434](https://github.com/azusachino/cappuccino/actions/runs/41217411434) | pull_request | Apple platform (iOS) | skipped | 10 | 0 | n/a | n/a | 0 |
| [41217411434](https://github.com/azusachino/cappuccino/actions/runs/41217411434) | pull_request | Documentation (rumdl) | skipped | 10 | 0 | n/a | n/a | 0 |
| [41217411434](https://github.com/azusachino/cappuccino/actions/runs/41217411434) | pull_request | Bridge (Rust) | skipped | 10 | 0 | n/a | n/a | 0 |
| [41217411434](https://github.com/azusachino/cappuccino/actions/runs/41217411434) | pull_request | make check | success | 162 | 1 | 1 | 0 | 3 |
| [41213666146](https://github.com/azusachino/cappuccino/actions/runs/41213666146) | pull_request | Detect changes | success | 1 | 1 | 2 | 1 | 5 |
| [41213666146](https://github.com/azusachino/cappuccino/actions/runs/41213666146) | pull_request | Android platform | success | 7 | 2 | 12 | 401 | 417 |
| [41213666146](https://github.com/azusachino/cappuccino/actions/runs/41213666146) | pull_request | Bridge (Rust) | success | 7 | 2 | 18 | 41 | 62 |
| [41213666146](https://github.com/azusachino/cappuccino/actions/runs/41213666146) | pull_request | Apple platform (iOS) | success | 7 | 3 | 15 | 103 | 123 |
| [41213666146](https://github.com/azusachino/cappuccino/actions/runs/41213666146) | pull_request | Documentation (rumdl) | skipped | 8 | 0 | n/a | n/a | n/a |
| [41213666146](https://github.com/azusachino/cappuccino/actions/runs/41213666146) | pull_request | make check | success | 426 | 3 | 1 | 0 | 2 |
| [41213608070](https://github.com/azusachino/cappuccino/actions/runs/41213608070) | push | Detect changes | success | 1 | 2 | 3 | 0 | 5 |
| [41213608070](https://github.com/azusachino/cappuccino/actions/runs/41213608070) | push | Android platform | success | 8 | 2 | 14 | 460 | 478 |
| [41213608070](https://github.com/azusachino/cappuccino/actions/runs/41213608070) | push | Apple platform (iOS) | success | 8 | 2 | 44 | 632 | 712 |
| [41213608070](https://github.com/azusachino/cappuccino/actions/runs/41213608070) | push | Bridge (Rust) | success | 8 | 2 | 26 | 33 | 61 |
| [41213608070](https://github.com/azusachino/cappuccino/actions/runs/41213608070) | push | Documentation (rumdl) | skipped | 8 | 0 | n/a | n/a | 0 |
| [41213608070](https://github.com/azusachino/cappuccino/actions/runs/41213608070) | push | make check | success | 722 | 2 | 0 | 1 | 2 |
| [41211880838](https://github.com/azusachino/cappuccino/actions/runs/41211880838) | pull_request | Detect changes | success | 0 | 2 | 4 | 0 | 7 |
| [41211880838](https://github.com/azusachino/cappuccino/actions/runs/41211880838) | pull_request | Bridge (Rust) | success | 9 | 2 | 17 | 33 | 51 |
| [41211880838](https://github.com/azusachino/cappuccino/actions/runs/41211880838) | pull_request | Android platform | success | 9 | 7 | 13 | 157 | 171 |
| [41211880838](https://github.com/azusachino/cappuccino/actions/runs/41211880838) | pull_request | Documentation (rumdl) | skipped | 10 | 0 | n/a | n/a | n/a |
| [41211880838](https://github.com/azusachino/cappuccino/actions/runs/41211880838) | pull_request | Apple platform (iOS) | skipped | 10 | 0 | n/a | n/a | n/a |
| [41211880838](https://github.com/azusachino/cappuccino/actions/runs/41211880838) | pull_request | make check | success | 188 | 2 | 0 | 1 | 3 |
| [41203537261](https://github.com/azusachino/cappuccino/actions/runs/41203537261) | push | Detect changes | success | 0 | 2 | 2 | 1 | 4 |
| [41203537261](https://github.com/azusachino/cappuccino/actions/runs/41203537261) | push | Apple platform (iOS) | success | 7 | 2 | 27 | 624 | 683 |
| [41203537261](https://github.com/azusachino/cappuccino/actions/runs/41203537261) | push | Android platform | failure | 7 | 1 | 15 | 486 | 504 |
| [41203537261](https://github.com/azusachino/cappuccino/actions/runs/41203537261) | push | Bridge (Rust) | success | 7 | 12 | 24 | 37 | 64 |
| [41203537261](https://github.com/azusachino/cappuccino/actions/runs/41203537261) | push | Documentation (rumdl) | skipped | 7 | 0 | n/a | n/a | n/a |
| [41203537261](https://github.com/azusachino/cappuccino/actions/runs/41203537261) | push | make check | failure | 692 | 2 | 1 | 0 | 2 |
| [41198496048](https://github.com/azusachino/cappuccino/actions/runs/41198496048) | pull_request | Detect changes | success | 1 | 2 | 3 | 0 | 6 |
| [41198496048](https://github.com/azusachino/cappuccino/actions/runs/41198496048) | pull_request | Android platform | success | 9 | 2 | 18 | 176 | 197 |
| [41198496048](https://github.com/azusachino/cappuccino/actions/runs/41198496048) | pull_request | Documentation (rumdl) | skipped | 10 | 0 | n/a | n/a | n/a |
| [41198496048](https://github.com/azusachino/cappuccino/actions/runs/41198496048) | pull_request | Apple platform (iOS) | skipped | 10 | 0 | n/a | n/a | n/a |
| [41198496048](https://github.com/azusachino/cappuccino/actions/runs/41198496048) | pull_request | Bridge (Rust) | skipped | 10 | 0 | n/a | n/a | n/a |
| [41198496048](https://github.com/azusachino/cappuccino/actions/runs/41198496048) | pull_request | make check | success | 208 | 2 | 1 | 0 | 3 |
| [41159187711](https://github.com/azusachino/cappuccino/actions/runs/41159187711) | push | Detect changes | success | 0 | 2 | 2 | 1 | 4 |
| [41159187711](https://github.com/azusachino/cappuccino/actions/runs/41159187711) | push | Bridge (Rust) | success | 6 | 2 | 14 | 50 | 78 |
| [41159187711](https://github.com/azusachino/cappuccino/actions/runs/41159187711) | push | Apple platform (iOS) | success | 6 | 2 | 33 | 457 | 519 |
| [41159187711](https://github.com/azusachino/cappuccino/actions/runs/41159187711) | push | Android platform | failure | 6 | 2 | 14 | 347 | 365 |
| [41159187711](https://github.com/azusachino/cappuccino/actions/runs/41159187711) | push | Documentation (rumdl) | skipped | 7 | 0 | n/a | n/a | n/a |
| [41159187711](https://github.com/azusachino/cappuccino/actions/runs/41159187711) | push | make check | failure | 528 | 2 | 1 | 0 | 4 |
| [41158775714](https://github.com/azusachino/cappuccino/actions/runs/41158775714) | pull_request | Detect changes | success | 1 | 2 | 2 | 1 | 5 |
| [41158775714](https://github.com/azusachino/cappuccino/actions/runs/41158775714) | pull_request | Android platform | success | 8 | 1 | 12 | 105 | 119 |
| [41158775714](https://github.com/azusachino/cappuccino/actions/runs/41158775714) | pull_request | Bridge (Rust) | success | 8 | 1 | 16 | 46 | 64 |
| [41158775714](https://github.com/azusachino/cappuccino/actions/runs/41158775714) | pull_request | Apple platform (iOS) | success | 8 | 3 | 18 | 119 | 142 |
| [41158775714](https://github.com/azusachino/cappuccino/actions/runs/41158775714) | pull_request | Documentation (rumdl) | skipped | 8 | 0 | n/a | n/a | 0 |
| [41158775714](https://github.com/azusachino/cappuccino/actions/runs/41158775714) | pull_request | make check | success | 153 | 2 | 1 | 0 | 3 |
| [41029442091](https://github.com/azusachino/cappuccino/actions/runs/41029442091) | pull_request | Detect changes | success | 1 | 2 | 2 | 1 | 5 |
| [41029442091](https://github.com/azusachino/cappuccino/actions/runs/41029442091) | pull_request | Android platform | success | 8 | 2 | 14 | 135 | 152 |
| [41029442091](https://github.com/azusachino/cappuccino/actions/runs/41029442091) | pull_request | Bridge (Rust) | success | 8 | 2 | 18 | 45 | 65 |
| [41029442091](https://github.com/azusachino/cappuccino/actions/runs/41029442091) | pull_request | Apple platform (iOS) | skipped | 8 | 0 | n/a | n/a | 0 |
| [41029442091](https://github.com/azusachino/cappuccino/actions/runs/41029442091) | pull_request | Documentation (rumdl) | skipped | 8 | 0 | n/a | n/a | 0 |
| [41029442091](https://github.com/azusachino/cappuccino/actions/runs/41029442091) | pull_request | make check | success | 163 | 16 | 1 | 0 | 3 |
| [41023259681](https://github.com/azusachino/cappuccino/actions/runs/41023259681) | pull_request | Detect changes | success | 1 | 2 | 3 | 1 | 7 |
| [41023259681](https://github.com/azusachino/cappuccino/actions/runs/41023259681) | pull_request | Bridge (Rust) | success | 10 | 2 | 18 | 47 | 68 |
| [41023259681](https://github.com/azusachino/cappuccino/actions/runs/41023259681) | pull_request | Android platform | success | 10 | 2 | 17 | 177 | 197 |
| [41023259681](https://github.com/azusachino/cappuccino/actions/runs/41023259681) | pull_request | Documentation (rumdl) | skipped | 10 | 0 | n/a | n/a | 0 |
| [41023259681](https://github.com/azusachino/cappuccino/actions/runs/41023259681) | pull_request | Apple platform (iOS) | skipped | 10 | 0 | n/a | n/a | 0 |
| [41023259681](https://github.com/azusachino/cappuccino/actions/runs/41023259681) | pull_request | make check | success | 209 | 2 | 1 | 0 | 2 |
| [41019991643](https://github.com/azusachino/cappuccino/actions/runs/41019991643) | pull_request | Detect changes | success | 0 | 2 | 3 | 1 | 7 |
| [41019991643](https://github.com/azusachino/cappuccino/actions/runs/41019991643) | pull_request | Bridge (Rust) | success | 9 | 2 | 13 | 42 | 56 |
| [41019991643](https://github.com/azusachino/cappuccino/actions/runs/41019991643) | pull_request | Android platform | success | 9 | 2 | 13 | 179 | 194 |
| [41019991643](https://github.com/azusachino/cappuccino/actions/runs/41019991643) | pull_request | Documentation (rumdl) | skipped | 9 | 0 | n/a | n/a | 0 |
| [41019991643](https://github.com/azusachino/cappuccino/actions/runs/41019991643) | pull_request | Apple platform (iOS) | skipped | 9 | 0 | n/a | n/a | 0 |
| [41019991643](https://github.com/azusachino/cappuccino/actions/runs/41019991643) | pull_request | make check | success | 205 | 2 | 1 | 0 | 2 |
| [41016910678](https://github.com/azusachino/cappuccino/actions/runs/41016910678) | pull_request | Detect changes | success | 0 | 2 | 2 | 1 | 4 |
| [41016910678](https://github.com/azusachino/cappuccino/actions/runs/41016910678) | pull_request | Android platform | success | 6 | 2 | 12 | 134 | 147 |
| [41016910678](https://github.com/azusachino/cappuccino/actions/runs/41016910678) | pull_request | Bridge (Rust) | success | 6 | 2 | 16 | 42 | 61 |
| [41016910678](https://github.com/azusachino/cappuccino/actions/runs/41016910678) | pull_request | Apple platform (iOS) | skipped | 6 | 0 | n/a | n/a | 0 |
| [41016910678](https://github.com/azusachino/cappuccino/actions/runs/41016910678) | pull_request | Documentation (rumdl) | skipped | 7 | 0 | n/a | n/a | n/a |
| [41016910678](https://github.com/azusachino/cappuccino/actions/runs/41016910678) | pull_request | make check | success | 156 | 1 | 1 | 0 | 4 |
| [41016459043](https://github.com/azusachino/cappuccino/actions/runs/41016459043) | pull_request | Detect changes | success | 0 | 2 | 2 | 1 | 4 |
| [41016459043](https://github.com/azusachino/cappuccino/actions/runs/41016459043) | pull_request | Android platform | success | 7 | 2 | 15 | 168 | 186 |
| [41016459043](https://github.com/azusachino/cappuccino/actions/runs/41016459043) | pull_request | Bridge (Rust) | success | 7 | 1 | 14 | 49 | 64 |
| [41016459043](https://github.com/azusachino/cappuccino/actions/runs/41016459043) | pull_request | Documentation (rumdl) | skipped | 7 | 0 | n/a | n/a | 0 |
| [41016459043](https://github.com/azusachino/cappuccino/actions/runs/41016459043) | pull_request | Apple platform (iOS) | skipped | 7 | 0 | n/a | n/a | 0 |
| [41016459043](https://github.com/azusachino/cappuccino/actions/runs/41016459043) | pull_request | make check | success | 195 | 2 | 1 | 0 | 4 |
| [41015057654](https://github.com/azusachino/cappuccino/actions/runs/41015057654) | pull_request | Detect changes | success | 1 | 2 | 2 | 0 | 4 |
| [41015057654](https://github.com/azusachino/cappuccino/actions/runs/41015057654) | pull_request | Bridge (Rust) | success | 7 | 2 | 18 | 50 | 83 |
| [41015057654](https://github.com/azusachino/cappuccino/actions/runs/41015057654) | pull_request | Android platform | success | 7 | 2 | 20 | 181 | 203 |
| [41015057654](https://github.com/azusachino/cappuccino/actions/runs/41015057654) | pull_request | Apple platform (iOS) | skipped | 7 | 0 | n/a | n/a | 0 |
| [41015057654](https://github.com/azusachino/cappuccino/actions/runs/41015057654) | pull_request | Documentation (rumdl) | skipped | 7 | 0 | n/a | n/a | 0 |
| [41015057654](https://github.com/azusachino/cappuccino/actions/runs/41015057654) | pull_request | make check | success | 213 | 1 | 1 | 0 | 2 |
| [40893791411](https://github.com/azusachino/cappuccino/actions/runs/40893791411) | push | Detect changes | success | 2 | 2 | 5 | 0 | 7 |
| [40893791411](https://github.com/azusachino/cappuccino/actions/runs/40893791411) | push | Bridge (Rust) | success | 11 | 2 | 21 | 34 | 57 |
| [40893791411](https://github.com/azusachino/cappuccino/actions/runs/40893791411) | push | Android platform | failure | 11 | 2 | 13 | 390 | 408 |
| [40893791411](https://github.com/azusachino/cappuccino/actions/runs/40893791411) | push | Apple platform (iOS) | success | 11 | 3 | 72 | 784 | 889 |
| [40893791411](https://github.com/azusachino/cappuccino/actions/runs/40893791411) | push | Documentation (rumdl) | skipped | 11 | 0 | n/a | n/a | 0 |
| [40893791411](https://github.com/azusachino/cappuccino/actions/runs/40893791411) | push | make check | failure | 904 | 2 | 1 | 0 | 4 |
| [40893757819](https://github.com/azusachino/cappuccino/actions/runs/40893757819) | push | Detect changes | cancelled | 1 | 2 | 2 | 0 | 4 |
| [40893757819](https://github.com/azusachino/cappuccino/actions/runs/40893757819) | push | make check | failure | 7 | 3 | 1 | 0 | 4 |
| [40893757819](https://github.com/azusachino/cappuccino/actions/runs/40893757819) | push | Apple platform (iOS) | cancelled | 7 | 0 | n/a | n/a | 0 |
| [40893757819](https://github.com/azusachino/cappuccino/actions/runs/40893757819) | push | Documentation (rumdl) | cancelled | 7 | 0 | n/a | n/a | 0 |
| [40893757819](https://github.com/azusachino/cappuccino/actions/runs/40893757819) | push | Bridge (Rust) | cancelled | 8 | 0 | n/a | n/a | n/a |
| [40893757819](https://github.com/azusachino/cappuccino/actions/runs/40893757819) | push | Android platform | cancelled | 8 | 0 | n/a | n/a | n/a |
| [40892311190](https://github.com/azusachino/cappuccino/actions/runs/40892311190) | pull_request | Detect changes | success | 1 | 2 | 2 | 1 | 5 |
| [40892311190](https://github.com/azusachino/cappuccino/actions/runs/40892311190) | pull_request | Bridge (Rust) | success | 9 | 2 | 20 | 48 | 71 |
| [40892311190](https://github.com/azusachino/cappuccino/actions/runs/40892311190) | pull_request | Android platform | skipped | 9 | 0 | n/a | n/a | 0 |
| [40892311190](https://github.com/azusachino/cappuccino/actions/runs/40892311190) | pull_request | Documentation (rumdl) | skipped | 9 | 0 | n/a | n/a | 0 |
| [40892311190](https://github.com/azusachino/cappuccino/actions/runs/40892311190) | pull_request | Apple platform (iOS) | skipped | 9 | 0 | n/a | n/a | 0 |
| [40892311190](https://github.com/azusachino/cappuccino/actions/runs/40892311190) | pull_request | make check | success | 83 | 2 | 1 | 0 | 3 |
| [40891872149](https://github.com/azusachino/cappuccino/actions/runs/40891872149) | workflow_dispatch | Detect changes | success | 4 | 2 | 3 | 6 | 12 |
| [40891872149](https://github.com/azusachino/cappuccino/actions/runs/40891872149) | workflow_dispatch | Android platform | failure | 18 | 2 | 12 | 466 | 481 |
| [40891872149](https://github.com/azusachino/cappuccino/actions/runs/40891872149) | workflow_dispatch | Bridge (Rust) | success | 18 | 2 | 17 | 31 | 50 |
| [40891872149](https://github.com/azusachino/cappuccino/actions/runs/40891872149) | workflow_dispatch | Apple platform (iOS) | success | 18 | 3 | 20 | 597 | 641 |
| [40891872149](https://github.com/azusachino/cappuccino/actions/runs/40891872149) | workflow_dispatch | Documentation (rumdl) | skipped | 18 | 0 | n/a | n/a | 0 |
| [40891872149](https://github.com/azusachino/cappuccino/actions/runs/40891872149) | workflow_dispatch | make check | failure | 663 | 2 | 1 | 0 | 3 |
| [40891618146](https://github.com/azusachino/cappuccino/actions/runs/40891618146) | pull_request | Detect changes | success | 1 | 2 | 4 | 1 | 8 |
| [40891618146](https://github.com/azusachino/cappuccino/actions/runs/40891618146) | pull_request | Bridge (Rust) | success | 12 | 2 | 21 | 31 | 55 |
| [40891618146](https://github.com/azusachino/cappuccino/actions/runs/40891618146) | pull_request | Documentation (rumdl) | skipped | 13 | 0 | n/a | n/a | n/a |
| [40891618146](https://github.com/azusachino/cappuccino/actions/runs/40891618146) | pull_request | Android platform | skipped | 13 | 0 | n/a | n/a | n/a |
| [40891618146](https://github.com/azusachino/cappuccino/actions/runs/40891618146) | pull_request | Apple platform (iOS) | skipped | 13 | 0 | n/a | n/a | n/a |
| [40891618146](https://github.com/azusachino/cappuccino/actions/runs/40891618146) | pull_request | make check | success | 69 | 2 | 1 | 0 | 4 |
| [40891606205](https://github.com/azusachino/cappuccino/actions/runs/40891606205) | pull_request | Detect changes | success | 1 | 1 | 3 | 0 | 5 |
| [40891606205](https://github.com/azusachino/cappuccino/actions/runs/40891606205) | pull_request | Android platform | success | 8 | 1 | 15 | 164 | 181 |
| [40891606205](https://github.com/azusachino/cappuccino/actions/runs/40891606205) | pull_request | Bridge (Rust) | skipped | 8 | 0 | n/a | n/a | n/a |
| [40891606205](https://github.com/azusachino/cappuccino/actions/runs/40891606205) | pull_request | Documentation (rumdl) | skipped | 8 | 0 | n/a | n/a | n/a |
| [40891606205](https://github.com/azusachino/cappuccino/actions/runs/40891606205) | pull_request | Apple platform (iOS) | skipped | 8 | 0 | n/a | n/a | n/a |
| [40891606205](https://github.com/azusachino/cappuccino/actions/runs/40891606205) | pull_request | make check | success | 190 | 2 | 1 | 1 | 4 |
| [40887911842](https://github.com/azusachino/cappuccino/actions/runs/40887911842) | push | Detect changes | success | 208 | 2 | 2 | 1 | 5 |
| [40887911842](https://github.com/azusachino/cappuccino/actions/runs/40887911842) | push | Bridge (Rust) | success | 215 | 2 | 14 | 45 | 61 |
| [40887911842](https://github.com/azusachino/cappuccino/actions/runs/40887911842) | push | Apple platform (iOS) | success | 215 | 6 | 25 | 544 | 599 |
| [40887911842](https://github.com/azusachino/cappuccino/actions/runs/40887911842) | push | Android platform | success | 215 | 2 | 20 | 551 | 577 |
| [40887911842](https://github.com/azusachino/cappuccino/actions/runs/40887911842) | push | Documentation (rumdl) | skipped | 215 | 0 | n/a | n/a | 0 |
| [40887911842](https://github.com/azusachino/cappuccino/actions/runs/40887911842) | push | make check | success | 820 | 2 | 1 | 0 | 3 |
