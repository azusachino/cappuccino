# Lead superseding correction to the Agy C7 report (replaces the earlier note)

The earlier lead note published here asserted that the common source manifest has 24 entries and excludes the wrapper JAR. **That assertion was wrong, as was the parent's 24-count framing.** This note supersedes and replaces it.

- The actual `source.sha256` / common manifest SHA-256 `1a1a61e41d6da3c79354362abe62f1109c4694ba8c56883432affe8970aada0c` has **25 entries**, including `gradle/wrapper/gradle-wrapper.jar` at `238e777fcddd7e34f9708186085def2abd6e08e658505b38718d79d74c21abd5`. The C7 bundle's `android/` tree does contain the JAR and all 25 files hash-match that manifest unchanged.
- The original Agy C7 report's "24 files" statement is therefore off by one; its actual source SHA value is correct. Do not treat this as an extra JAR added on top of an original 24-file hash: the common SHA was always over 25 entries.
- The C7 bundle JAR and source bytes were provided in C7; how many were independently byte-checked is **not** claimed here until the fresh C8 audit verifies the 25.

Published alongside: the Agy C7 report as a rumdl-formatted readable copy (`agy-step-c7-report.md`) and its raw original bytes (`agy-step-c7-report-raw-original.txt`).
