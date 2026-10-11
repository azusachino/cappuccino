package com.azusachino.cappuccino.ui

import dev.snipme.highlights.model.SyntaxTheme

// Map GitHub Light/Dark colors to Highlights' existing token categories.
// Colors are RGB integers, as required by Highlights 1.1.0; tokenization is unchanged.
internal fun githubCodeTheme(dark: Boolean): SyntaxTheme =
  if (dark) {
    SyntaxTheme(
      key = "github-dark",
      code = 0xE6EDF3,
      keyword = 0xFF7B72,
      string = 0xA5D6FF,
      literal = 0x79C0FF,
      comment = 0x8B949E,
      metadata = 0xD2A8FF,
      multilineComment = 0x8B949E,
      punctuation = 0xE6EDF3,
      mark = 0x79C0FF,
    )
  } else {
    SyntaxTheme(
      key = "github-light",
      code = 0x24292F,
      keyword = 0xCF222E,
      string = 0x0A3069,
      literal = 0x0550AE,
      comment = 0x6E7781,
      metadata = 0x8250DF,
      multilineComment = 0x6E7781,
      punctuation = 0x24292F,
      mark = 0x0550AE,
    )
  }
