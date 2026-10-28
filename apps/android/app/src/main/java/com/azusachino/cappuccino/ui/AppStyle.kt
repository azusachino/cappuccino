package com.azusachino.cappuccino.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.azusachino.cappuccino.R

// Visual language adapted from pi-pocket's web/style.css (MIT, docs/licenses/pi-pocket):
// one monospace face, surfaces mixed from the foreground over the background, square tiles.

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun jetBrainsMonoWeight(weight: FontWeight) =
  Font(
    R.font.jetbrains_mono,
    weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
  )

val JetBrainsMono =
  FontFamily(
    jetBrainsMonoWeight(FontWeight.Normal),
    jetBrainsMonoWeight(FontWeight.Medium),
    jetBrainsMonoWeight(FontWeight.SemiBold),
    jetBrainsMonoWeight(FontWeight.Bold),
  )

// Code ligatures turn `!=` and `->` into glyphs that are not what the agent wrote.
private const val NO_LIGATURES = "liga 0, calt 0"

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
  TextStyle(
    fontFamily = JetBrainsMono,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = 0.sp,
    fontFeatureSettings = NO_LIGATURES,
  )

// Monospace sets wide, so sizes sit a step under Material's; body keeps pi-pocket's 14 / 1.6.
val AppTypography =
  Typography(
    displayLarge = style(40, 48),
    displayMedium = style(34, 42),
    displaySmall = style(28, 36),
    headlineLarge = style(26, 34),
    headlineMedium = style(22, 30),
    headlineSmall = style(19, 26),
    titleLarge = style(17, 24, FontWeight.SemiBold),
    titleMedium = style(15, 22, FontWeight.SemiBold),
    titleSmall = style(13, 20, FontWeight.SemiBold),
    bodyLarge = style(15, 24),
    bodyMedium = style(14, 22),
    bodySmall = style(12, 18),
    labelLarge = style(13, 18, FontWeight.Medium),
    labelMedium = style(12, 16, FontWeight.Medium),
    labelSmall = style(11, 14, FontWeight.Medium),
  )

private val Square = RoundedCornerShape(0)

val AppShapes =
  Shapes(
    extraSmall = Square,
    small = Square,
    medium = Square,
    large = Square,
    extraLarge = Square,
  )

/** Roles Material has no slot for: quieter text tiers, rules and status hues. */
@Immutable
data class AppColors(
  val card: Color,
  val card2: Color,
  val raised: Color,
  val line: Color,
  val lineStrong: Color,
  val text: Color,
  val body: Color,
  val muted: Color,
  val faint: Color,
  val codeBackground: Color,
  val accent: Color,
  val ok: Color,
  val warn: Color,
  val err: Color,
  val working: Color,
)

private fun Color.over(base: Color, fraction: Float) = copy(alpha = fraction).compositeOver(base)

fun appColors(scheme: ColorScheme, dark: Boolean): AppColors {
  val bg = scheme.background
  val fg = scheme.onBackground
  return AppColors(
    card = fg.over(bg, 0.04f),
    card2 = fg.over(bg, 0.08f),
    raised = fg.over(bg, 0.11f),
    line = fg.over(bg, if (dark) 0.13f else 0.15f),
    lineStrong = fg.over(bg, 0.26f),
    text = fg,
    body = fg.over(bg, 0.9f),
    muted = fg.over(bg, if (dark) 0.62f else 0.78f),
    faint = fg.over(bg, if (dark) 0.38f else 0.58f),
    codeBackground = if (dark) Color.Black.over(bg, 0.22f) else fg.over(bg, 0.05f),
    accent = scheme.primary,
    // Status hues stay semantic: green, amber, Chino blue, rose.
    ok = if (dark) Color(0xFFA3D9B0) else Color(0xFF33774A),
    warn = if (dark) Color(0xFFE3C48E) else Color(0xFF8F5D26),
    err = scheme.error,
    working = if (dark) Color(0xFF9CC4EE) else Color(0xFF35639F),
  )
}

/** Material's surface roles re-derived from the same mixes so stock components match. */
fun flatSurfaceScheme(scheme: ColorScheme, dark: Boolean): ColorScheme {
  val tokens = appColors(scheme, dark)
  return scheme.copy(
    surface = scheme.background,
    surfaceDim = scheme.background,
    surfaceBright = tokens.raised,
    surfaceContainerLowest = scheme.background,
    surfaceContainerLow = tokens.card,
    surfaceContainer = tokens.card2,
    surfaceContainerHigh = tokens.raised,
    surfaceContainerHighest = tokens.raised,
    surfaceVariant = tokens.card2,
    onSurface = tokens.text,
    onSurfaceVariant = tokens.muted,
    outline = tokens.lineStrong,
    outlineVariant = tokens.line,
    surfaceTint = Color.Transparent,
  )
}

val LocalAppColors = staticCompositionLocalOf { appColors(FallbackDarkColorScheme, dark = true) }

val MaterialTheme.appColors: AppColors
  @Composable @ReadOnlyComposable get() = LocalAppColors.current

/** Telegram-style avatar brand colors: a soft vertical gradient per agent. */
internal fun agentBrandColors(agent: String, dark: Boolean): Pair<Color, Color> =
  when (agent.lowercase()) {
    "pi" ->
      if (dark) Color(0xFF9CC4EE) to Color(0xFF5B8FD0) else Color(0xFF5B8FD0) to Color(0xFF35639F)
    "claude" ->
      if (dark) Color(0xFFE8946A) to Color(0xFFB85C33) else Color(0xFFC15F3C) to Color(0xFF9A4A2C)
    "agy",
    "antigravity",
    "antigravity-cli" ->
      if (dark) Color(0xFFB08FE8) to Color(0xFF7A55C4) else Color(0xFF8E6AD9) to Color(0xFF6A4AB0)
    else ->
      if (dark) Color(0xFF8A94A8) to Color(0xFF5A6478) else Color(0xFF7A8498) to Color(0xFF58617A)
  }
