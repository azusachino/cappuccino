# Mihon default color-scheme attribution

The native app and debug design preview adapt `TachiyomiColorScheme.kt` from
[mihonapp/mihon](https://github.com/mihonapp/mihon/blob/866d045c7aba343fd739c57d15c81ff6df59e796/app/src/main/java/eu/kanade/presentation/theme/colorscheme/TachiyomiColorScheme.kt),
pinned commit `866d045c7aba343fd739c57d15c81ff6df59e796`.

Source license: Apache-2.0, reproduced in `LICENSE` here and bundled in the
APK at `assets/licenses/mihon-LICENSE.txt` (main assets, also packaged for
release). The adapted file retains the
source's Material Theme Builder/key-color documentation. Changes are package,
object name and standalone light/dark scheme declarations rather than extending
Mihon's base class. No Mihon navigation, manga models, cover assets or palette
chooser implementation is copied.

Default uses those source schemes. Dynamic uses Android's native Material 3
system schemes on API 31+, without Mihon's compatibility library. Catppuccin
uses Cappuccino's existing palette implementation and attribution. These are
the owner's initial three-choice scope, not a claimed popularity ranking.
The owner subsequently added GitHub and One Dark. Those app color-role mappings
are Cappuccino-authored editor-inspired schemes, not copied app layouts; One
Dark uses a One Light-inspired counterpart in light mode. The actual
app picker and persisted preference now consume the same scheme definitions
as the debug preview; acceptance and a complete native contrast matrix are
separate from the unit contrast checks.
