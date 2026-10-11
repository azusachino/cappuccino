# Native code and shell highlighting

## Scope and evidence

The owner selected Catppuccin and requested better code highlighting, including shell commands, without a WebView. This research inspects actual app integrations and upstream implementations. It does not adopt a dependency, copy third-party implementation, or establish device performance. Android/Apple acceptance and historical native-test failures remain open.

## Implementation checkpoint after owner authorization

The owner subsequently requested installation followed by implementation. The Catppuccin APK was installed with data preserved. Android now has an initial TextMate-backed native code-block path using the existing renderer's code callbacks, with structured command previews/full readers routed into the same component. Pinned VS Code grammars and upstream license notices are bundled locally; `code-grammars/sources.json` records the exact source URLs and hashes. No WebView, networking binding or command execution was added.

This first slice covers Bash/sh, Python, JSON/JSONC, JavaScript/JSX, TypeScript/TSX, Rust, YAML, Swift and Go. Kotlin, Nix, Fish, file-result provenance and the Apple adapter are still incomplete. Jobs are limited to two concurrent workers, own their mutable grammars, and only apply results matching current source/language/theme. Original command text remains the annotated string's backing text; tokenization does not reconstruct line endings. Latte token accents are contrast-adjusted against the actual code canvas.

Dependency preflight passed after fixing a Gradle version-catalog name collision; the initial failed gate is retained. Integration gate `ba8d09cf7` exposed YAML capture-map documentation comments incompatible with the library reader. The adapter now removes only string-valued `comment` metadata inside capture maps in memory; original assets and actual captures remain unchanged, with a preservation regression test. Full `validate-android` passed on rerun `b6f32f235`: 136 unit tests, zero failures/errors/skips, lint, app build and instrumentation compilation. The implementation was committed as `fd7fd54` and installed with `adb install -r` on the authorized phone, preserving data; APK SHA-256 `057c4703106245cc69b88d39f2f5b363e43839cf4cb2c21ae007bc96c98e03a3`. Installation and compilation do not establish native interaction or visual acceptance; those remain open.

## Why the previous renderer showed so much plain text

These were separate problems identified in the Android source before the implementation slice:

- `ui/ToolContent.kt`: `formatToolContent` defaults to `text`; valid JSON becomes `json`. Other tool results do not carry a source-file language into the renderer.
- `ui/ConversationDetails.kt`: extracted argument summaries bypass the code renderer. Both preview and full reader use plain monospace `Text`. The variable called `command` also includes paths, patterns and URLs through `toolSummary`; these must not all be treated as shell commands.
- `ui/MarkdownText.kt`: language lookup passes the whole fence language string to `SyntaxLanguage.getByName`. Only `json`, `bash`, `sh` and `shell` receive explicit handling. Aliases such as `py`, `js`, `rs` and fence attributes can therefore fall back to `DEFAULT`.
- The installed Highlights 1.1.0 JAR exposes 17 named languages, including Rust and Shell. It does **not** expose JSON, YAML, Nix or SQL. JSON currently uses its JavaScript lexer. Its bytecode confirms case-insensitive enum-name matching, not a language-alias registry. [The inspected upstream enum][highlights-enum] has the same names and lookup.

Changing syntax colors cannot supply missing language metadata or grammar coverage. A replacement engine still needs correct routing. Shell already has a lexer in the existing dependency; command rendering bypasses it.

## Actual apps and reusable integrations

| Source | Inspected implementation | Reuse boundary and suitability |
| --- | --- | --- |
| [SmolChat Android][smol-renderer] | Native Markwon `Spanned` output with Prism4j tokenization; no WebView in this renderer | [Apache-2.0][smol-license]. A real chat-app integration, but its [grammar locator][smol-grammars] has no Bash, Rust or TypeScript. Not sufficient for the requested shell coverage. Keep Cappuccino's existing Markdown renderer rather than copy its HTML/link plugins. |
| [Markdown Viewer for iOS/macOS][viewer-adapter] | MarkdownUI code callback → Highlightr → `NSAttributedString` → SwiftUI `Text` | [MIT][viewer-license]. A small, concrete native adapter worth adapting with attribution. It automatically retries detection for unsupported explicit languages; Cappuccino should not silently reinterpret them. |
| [RikkaHub Android][rikka-renderer] | Pure Kotlin port of highlight.js 11.11.1, token output rendered through Compose `AnnotatedString`; Bash grammar exists | [AGPL-3.0][rikka-license]: study only under this project's rules. Do not copy its implementation. Its composable also bypasses highlighting above 4,096 characters; that fallback does not meet our full-reader goal. |
| [KotlinTextMate sample/integration][textmate-adapter] | TextMate token scopes and VS Code themes become native Compose spans | [MIT][textmate-license], including identified third-party notices. This is a library/demo integration, not proof of a production chat reader. Its README explicitly calls it a proof of concept. |

CherryHQ/hanlin-ai was also inspected after search suggested a reusable Highlightr integration. The inspected dependency lock does not include Highlightr; its large chat-view source contains custom highlighters and WebView features. It is not evidence for that proposed integration and is not the selected reference.

## Engine choices without a WebView

### Android: KotlinTextMate

[The upstream project][textmate-readme] loads TextMate JSON grammars, uses Joni for Oniguruma-compatible regex, and offers native `AnnotatedString` output. Its published artifact is on Maven Central. Catppuccin VS Code token themes are a natural format match, subject to checking each bundled grammar/theme license.

Risks stated upstream or visible in the inspected source:

- Unsupported backreferences in lookbehind become a never-matching sentinel. A grammar can load while some constructs remain unhighlighted.
- `Grammar` is mutable and not thread-safe. Do not share it between concurrent message-highlighting jobs.
- The supplied helper retokenizes the full block; no built-in incremental cache is supplied.
- [The Compose adapter][textmate-adapter] rebuilds text through `code.lines()` and inserts `\n`. Blindly copying it would normalize CRLF. Cappuccino should retain the original string and apply offset-based spans, with exact-text tests.
- The bundled sample is not evidence that every external grammar works. Bash, heredocs, embedded expressions and the selected configuration languages need testing.

This is a worthwhile small prototype, not an approved production replacement.

### Apple: HighlighterSwift, following the native viewer's pattern

[Highlightr now declares itself unmaintained][highlightr-readme] and recommends [HighlighterSwift][highlighter-readme]. HighlighterSwift 3.1.0 bundles highlight.js 11.11.1 and uses the system JavaScriptCore framework. [Its implementation][highlighter-source] passes code as a function argument and returns attributed text through a fast custom parser. It does not need a WebView.

The wrapper is MIT; bundled highlight.js is BSD-3-Clause, as recorded in [its license inventory][highlighter-license]. Preserve both notices.

Caveats:

- The maintainer explicitly says iOS support is untested. Our iOS 17/macOS builds and native reading journey are required before adopting it.
- Preflight supported languages and resource initialization. Its implementation includes forced unwraps around returned markup; unsupported-language behavior needs adversarial testing.
- Use its fast native parser, not the optional system HTML-import path. Do not enable arbitrary HTML, resource fetching or active links.
- CSS theme files require its expected normalization; the README warns against copying arbitrary upstream CSS unchanged. Verify Catppuccin colors against the actual code canvas.

The viewer supplies the concrete SwiftUI adapter pattern; the newer engine remains a candidate requiring verification.

### Android alternative: bundled highlight.js in QuickJS

[quickjs-kt][quickjs-readme] provides an Android JavaScript runtime without a browser, with cancellation and evaluation timeouts. [The Kotlin wrapper is Apache-2.0][quickjs-license]; the underlying QuickJS engine and bundled highlighter have their own notices.

This could give both platforms the same highlight.js grammar behavior: QuickJS on Android, JavaScriptCore on Apple, native text on both. It adds JNI/runtime packaging and a token/span adapter. It is not an existing drop-in Compose renderer verified here. Parse only bundled code, pass snippets as data, expose no networking/filesystem bindings, and never evaluate a displayed command.

### Fully native alternative: Tree-sitter

[Official Kotlin bindings][ktreesitter] and [SwiftTreeSitter][swift-treesitter] provide a common native parsing foundation. Each selected language needs its grammar and highlighting queries, with separate licensing/build checks. We would own capture-to-native-span mapping, Unicode offsets and caching. This avoids a JavaScript runtime but requires more integration than replacing a palette. Prefer this route if the TextMate prototype fails or a tokenizer runtime is unacceptable.

### Shiki itself

[Shiki's documented engines][shiki-engines] include Oniguruma/WASM and a JavaScript regex engine. It can produce tokens rather than requiring HTML display. A WebView is not intrinsically required, but embedding it in these clients still needs a JavaScript runtime, a compatible regex engine, bundled grammars and a native token adapter. No Android/iOS runtime compatibility was demonstrated in this research. Do not call it a simple native dependency swap.

## Shell presentation requirements

- Render real command strings as native, selectable code blocks in previews and full readers. Keep working directory and other arguments separate. Identify commands from structured command fields, not from every generic summary.
- Normalize known aliases such as `bash` and `sh`; handle shell sessions separately from source scripts. Keep the declared language visible. Fish is not automatically Bash.
- Render command names, quoting, substitutions, pipelines, redirects, multiline continuations and heredocs according to the selected grammar. Do not promise every flag or filename is a semantic token.
- Preserve the entire command exactly, including newlines, escapes, Unicode and trailing spaces. Copy original text, not reconstructed colored output.
- Keep stdout, errors and arbitrary logs as output; do not guess that all tool results are executable source.
- No Run button, terminal emulator, command execution, automatic link opening, script evaluation of snippet contents or remote-agent interaction.

## Recommended next slice

First fix routing: command blocks, declared-language aliases, and source-file language metadata where the tool actually supplies a path. Do not strip line numbers, invent a dialect, reinterpret explicitly plain-text fences, or silently turn unknown languages into JavaScript.

Then compare a small Android KotlinTextMate prototype and an Apple HighlighterSwift adapter against the current renderer, using the same representative corpus. If TextMate grammar compatibility fails, evaluate the shared highlight.js/QuickJS route before committing to a custom parser or large editor dependency. No engine dependency is approved by this note.

Required checks before choosing:

1. Bash/sh commands, pipelines, quoted strings, variables, heredocs and incomplete streaming commands; explicit Fish samples where applicable.
2. Rust, Kotlin, Swift, Python, JavaScript/TypeScript, JSON, YAML and Nix; aliases, fence attributes, absent language and unknown language. Count actual supported cases, not advertised grammar totals.
3. Rendered text and copied text equal the original, including CRLF, emoji, non-Latin text and trailing newlines. Token ranges stay in bounds and cover source without dropping content.
4. Native light/dark Catppuccin samples and measured contrast. A colored function/keyword/string must genuinely come from a token, not a decorative recolor of all prose.
5. Long blocks and streaming growth remain readable. Measure on the authorized phone and an explicitly owned Apple test target; no invented timings or unconditional plain-text cutoff that defeats the full reader.
6. Background work is bounded/cancellable, cached by content/language/theme and isolated from mutable grammar state. Late results cannot cross conversation identity or change follow-scroll intent.
7. Retain passive Markdown/media rules and owning build/lint/unit/native gates. Research and compilation alone are not native acceptance.

## Primary sources

The inspected app/library source revisions are pinned below. Discovery search answers were not treated as license or implementation evidence.

[highlights-enum]: https://github.com/SnipMeDev/Highlights/blob/4c0caa8105a766b03870aa3b16bf3d61d7c9a2bd/src/commonMain/kotlin/dev/snipme/highlights/model/SyntaxLanguage.kt
[smol-renderer]: https://github.com/shubham0204/SmolChat-Android/blob/b663cd64ce5a19e85cd5c932e240125800bf8e49/app/src/main/java/io/shubham0204/smollmandroid/ui/screens/chat/MDRenderer.kt
[smol-grammars]: https://github.com/shubham0204/SmolChat-Android/blob/b663cd64ce5a19e85cd5c932e240125800bf8e49/app/src/main/java/io/shubham0204/smollmandroid/prism4j/PrismGrammarLocator.kt
[smol-license]: https://github.com/shubham0204/SmolChat-Android/blob/b663cd64ce5a19e85cd5c932e240125800bf8e49/LICENSE
[viewer-adapter]: https://github.com/hazzahex/markdown-viewer/blob/72cc612311ac2c41659097b199884d2762ce9a2e/Packages/MarkdownRenderer/Sources/MarkdownRenderer/CodeHighlighter.swift
[viewer-license]: https://github.com/hazzahex/markdown-viewer/blob/72cc612311ac2c41659097b199884d2762ce9a2e/LICENSE
[rikka-renderer]: https://github.com/rikkahub/rikkahub/blob/0f34229aef947ee061ecdb5ff23d9d6943731fa4/highlight/src/main/java/me/rerere/highlight/Highlighter.kt
[rikka-license]: https://github.com/rikkahub/rikkahub/blob/0f34229aef947ee061ecdb5ff23d9d6943731fa4/LICENSE
[textmate-readme]: https://github.com/ivan-magda/kotlin-textmate/blob/a500bc7a6bef8046d1e961a8738e0629a04b9fcb/README.md
[textmate-adapter]: https://github.com/ivan-magda/kotlin-textmate/blob/a500bc7a6bef8046d1e961a8738e0629a04b9fcb/compose-ui/src/main/kotlin/dev/textmate/compose/CodeHighlighter.kt
[textmate-license]: https://github.com/ivan-magda/kotlin-textmate/blob/a500bc7a6bef8046d1e961a8738e0629a04b9fcb/LICENSE
[highlightr-readme]: https://github.com/raspu/Highlightr/blob/da9bb2e4604da001f1d1a1adaa55f6a38509b405/README.md
[highlighter-readme]: https://github.com/smittytone/HighlighterSwift/blob/fe7aae9c9b31d3b296fd3d2dd575e1a207bb29e0/README.md
[highlighter-source]: https://github.com/smittytone/HighlighterSwift/blob/fe7aae9c9b31d3b296fd3d2dd575e1a207bb29e0/Sources/Highlighter/Highlighter.swift
[highlighter-license]: https://github.com/smittytone/HighlighterSwift/blob/fe7aae9c9b31d3b296fd3d2dd575e1a207bb29e0/LICENCE.md
[quickjs-readme]: https://github.com/dokar3/quickjs-kt/blob/a081755bcdb18ee6afa7ffee25b8fa976f06d31f/README.md
[quickjs-license]: https://github.com/dokar3/quickjs-kt/blob/a081755bcdb18ee6afa7ffee25b8fa976f06d31f/LICENSE.txt
[ktreesitter]: https://github.com/tree-sitter/kotlin-tree-sitter/blob/ee7283285972ceb305f33685f9270fe69d1879bb/README.md
[swift-treesitter]: https://github.com/tree-sitter/swift-tree-sitter/blob/cbe31cb3601aa29c1f64b6e4328733511126d6d5/README.md
[shiki-engines]: https://shiki.style/guide/regex-engines
