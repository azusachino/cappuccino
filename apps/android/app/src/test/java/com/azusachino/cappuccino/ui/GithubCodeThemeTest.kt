package com.azusachino.cappuccino.ui

import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubCodeThemeTest {
  @Test
  fun nativeLexerUsesGithubStringColorsInBothModes() {
    for (dark in listOf(false, true)) {
      val theme = githubCodeTheme(dark)
      assertEquals(if (dark) 0xA5D6FF else 0x0A3069, theme.string)
      val colors =
        Highlights.Builder()
          .language(SyntaxLanguage.JAVASCRIPT)
          .theme(theme)
          .code("const message = \"hello\";")
          .build()
          .getHighlights()
          .filterIsInstance<ColorHighlight>()
      assertTrue(
        "Native string spans must use the selected GitHub palette",
        colors.any { it.rgb == theme.string },
      )
    }
  }
}
