# Mihon patterns for the Android UI rewrite

## Scope and checkpoint

The owner rejected the live screen-jump fix across all conversations and asked
for a full Mihon-style UI rewrite, including whole-app palette selection.
Previously passing synthetic reader tests do not establish live acceptance.
This note records source findings, not an approved implementation specification.

Inspected the existing read-only Mihon reference on `main`, commit
`866d045c7aba343fd739c57d15c81ff6df59e796`. No upstream code or assets have been
copied. Cappuccino remains native Compose; its bridge, canonical identity,
connection independence, private storage and passive-content boundaries remain
requirements. Android is the proposed first slice; Apple changes are not
included in this study.

## Source-backed patterns

- **License:** the repository's [LICENSE](https://github.com/mihonapp/mihon/blob/866d045c7aba343fd739c57d15c81ff6df59e796/LICENSE)
  is Apache 2.0. This is not a dependency or asset-license audit. Any later reuse
  needs file-level provenance and applicable attribution; no copyleft code or
  assets are authorized for adoption.
- **Palette is separate from brightness mode.**
  [SettingsAppearanceScreen](https://github.com/mihonapp/mihon/blob/866d045c7aba343fd739c57d15c81ff6df59e796/app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsAppearanceScreen.kt)
  groups a mode selector, palette previews and a pure-black dark-theme switch.
  The pure-black option is disabled in light mode. These are whole-app choices,
  not just an accent-color picker.
- **Preview an interface, not only a swatch.**
  [AppThemePreferenceWidget](https://github.com/mihonapp/mihon/blob/866d045c7aba343fd739c57d15c81ff6df59e796/app/src/main/java/eu/kanade/presentation/more/settings/widget/AppThemePreferenceWidget.kt)
  uses a horizontally scrolling row of named miniature screens. Each preview
  shows background, toolbar, content, secondary/tertiary colors and bottom bar.
  Selection has both a border and an accessible check indicator. Stable theme
  names key the items; unsupported dynamic color and deprecated choices are
  hidden. Cappuccino previews should use machine/reader content, not manga
  covers or Mihon branding.
- **Explicit scheme catalog.**
  [AppTheme](https://github.com/mihonapp/mihon/blob/866d045c7aba343fd739c57d15c81ff6df59e796/app/src/main/java/eu/kanade/domain/ui/model/AppTheme.kt)
  includes Default, Monet, Catppuccin, Nord, Monochrome, Tokyo Night and other
  named presets. Deprecated entries remain identifiable but have no visible
  title. This suggests persistent palette IDs and a deliberate fallback,
  rather than saving a list index. It does not require adopting every preset.
- **One theme boundary supplies the whole app.**
  [TachiyomiTheme](https://github.com/mihonapp/mihon/blob/866d045c7aba343fd739c57d15c81ff6df59e796/app/src/main/java/eu/kanade/presentation/theme/TachiyomiTheme.kt)
  selects a full `ColorScheme` by palette, darkness and pure-black preference,
  then provides it through `MaterialExpressiveTheme`. Dynamic color has its
  own scheme implementation. The inspected file uses a remembered scheme;
  details of individual palette transformations remain to be inspected.

## Adaptation boundaries

Learn the Material layout and theme-selection patterns. Do not import Mihon's
navigation framework, dependency injection, manga model, cover assets or
reader gestures merely to imitate its appearance.

Mihon's palette and pure-black controls call `ActivityCompat.recreate`.
Cappuccino must not copy that behavior blindly: palette changes must preserve
machine connections, canonical selection, reading position and scoped drafts.
Prefer a reactive theme boundary that does not reset the reader.

The owner's tool-control complaint also changes the presentation target:
plain **Tools** access, without an icon, activity dot or bare historical-failure
badge. Any counts belong in tool history with explicit labels: shown, loaded,
not shown, and historical failed results. None means unread activity, an active
queue or a global lifetime total.

## Proposed rewrite boundaries — not yet approved

| Module ID | Responsibility | Depends on |
| --- | --- | --- |
| `theme-system` | Named full palettes, mode selection, representative previews, persistence | Existing private settings/native rendering |
| `app-shell` | Material navigation, machine/agent browsing, settings and saved-history access | `theme-system`; existing verified connections/archive identity |
| `conversation-reader` | Stable visible-answer timeline, separate tool/reasoning history, native full readers and honest historical labels | `theme-system`, `app-shell`; existing passive/native/read-only foundations |

Proposed order: source study and owner-reviewed design → theme boundary →
shell → reader/tool surfaces → real consuming acceptance. The reader is not
accepted until live tool-only updates preserve position across conversations;
a synthetic fixture or screenshot alone is insufficient.

## Safety and remaining work

No phone uninstall or Gradle connected-test cleanup against the owner's
installation. Preserve and verify private data for any authorized installation;
use an isolated package/device for automated test artifacts. Never manipulate
remote agent sessions as part of a UI rewrite.

Still needed: shell/settings source inspection, palette provenance and
contrast checks, an owner-approved capability/design boundary, native previews,
and a live-reader reproduction that records identity and geometry without
publishing private transcripts. The old patch acceptance remains rejected.
