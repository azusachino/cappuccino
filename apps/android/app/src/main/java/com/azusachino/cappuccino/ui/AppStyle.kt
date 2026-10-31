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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azusachino.cappuccino.R

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

fun monoStyle(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
  TextStyle(
    fontFamily = JetBrainsMono,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = 0.sp,
    fontFeatureSettings = NO_LIGATURES,
  )

private fun uiStyle(
  size: Int,
  line: Int,
  weight: FontWeight = FontWeight.Normal,
  letterSpacing: Double = 0.0,
) =
  TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = letterSpacing.sp,
  )

// tw93-inspired typography: clean, proportional system sans-serif hierarchy for UI,
// reserving JetBrainsMono exclusively for code, transcripts, hashes, and technical specs.
val AppTypography =
  Typography(
    displayLarge = uiStyle(32, 40, FontWeight.Bold),
    displayMedium = uiStyle(28, 36, FontWeight.Bold),
    displaySmall = uiStyle(24, 32, FontWeight.SemiBold),
    headlineLarge = uiStyle(24, 32, FontWeight.Bold),
    headlineMedium = uiStyle(20, 26, FontWeight.SemiBold),
    headlineSmall = uiStyle(18, 24, FontWeight.SemiBold),
    titleLarge = uiStyle(17, 22, FontWeight.SemiBold),
    titleMedium = uiStyle(15, 20, FontWeight.SemiBold),
    titleSmall = uiStyle(13, 18, FontWeight.SemiBold),
    bodyLarge = uiStyle(16, 23),
    bodyMedium = uiStyle(15, 22),
    bodySmall = uiStyle(13, 18),
    labelLarge = uiStyle(13, 18, FontWeight.Medium),
    labelMedium = uiStyle(12, 16, FontWeight.Medium, letterSpacing = 0.3),
    labelSmall = uiStyle(11, 14, FontWeight.Medium),
  )

// Soft, rounded elevated shapes matching modern Apple / tw93 liquid glass design.
val AppShapes =
  Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
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
    card = fg.over(bg, if (dark) 0.05f else 0.04f),
    card2 = fg.over(bg, if (dark) 0.09f else 0.07f),
    raised = fg.over(bg, if (dark) 0.13f else 0.10f),
    line = fg.over(bg, if (dark) 0.10f else 0.12f),
    lineStrong = fg.over(bg, if (dark) 0.22f else 0.20f),
    text = fg,
    body = fg.over(bg, if (dark) 0.92f else 0.90f),
    muted = fg.over(bg, if (dark) 0.70f else 0.78f),
    faint = fg.over(bg, if (dark) 0.44f else 0.52f),
    codeBackground = if (dark) Color.Black.over(bg, 0.24f) else fg.over(bg, 0.05f),
    accent = scheme.primary,
    // Status hues stay semantic: emerald, amber, coral rose, and soft chino blue.
    ok = if (dark) Color(0xFF34D399) else Color(0xFF059669),
    warn = if (dark) Color(0xFFFBBF24) else Color(0xFFD97706),
    err = scheme.error,
    working = if (dark) Color(0xFF60A5FA) else Color(0xFF2563EB),
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
