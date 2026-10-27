package com.azusachino.cappuccino.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/** Design tokens and styling parameters for Apple-style liquid glass UI components. */
object GlassDefaults {
  val RimStrokeWidth: Dp = 1.dp
  val CardCornerRadius: Dp = 14.dp
  val PillCornerRadius: Dp = 24.dp

  // Translucent dark mode surfaces: navy-charcoal glass, from the uniform
  val DarkSurface: Color = Color(0xCC1D2330)
  val DarkSurfaceElevated: Color = Color(0xD9262F40)

  // Translucent light mode surfaces: airy white over the cool canvas
  val LightSurface: Color = Color(0xE6FFFFFF)
  val LightSurfaceElevated: Color = Color(0xEEFDFEFF)

  // Haze backdrop tints: lower alpha than the surfaces above so the blur shows through
  val DarkBackdropTint: Color = Color(0xB31D2330)
  val LightBackdropTint: Color = Color(0xB3F2F5F9)

  // Specular rim gradient brushes
  val DarkRimGradient: Brush =
    Brush.verticalGradient(
      listOf(
        Color.White.copy(alpha = 0.22f),
        Color.White.copy(alpha = 0.05f),
      )
    )

  val LightRimGradient: Brush =
    Brush.verticalGradient(
      listOf(
        Color.White.copy(alpha = 0.85f),
        Color(0xFF35639F).copy(alpha = 0.10f),
      )
    )

  const val PressScale: Float = 0.97f

  val PressSpringSpec =
    spring<Float>(
      dampingRatio = 0.8f,
      stiffness = Spring.StiffnessMediumLow,
    )
}

/**
 * Applies a liquid glass surface styling: translucent surface tinting, clipping, and a specular
 * gradient rim border. No backdrop blur: use [glassBackdrop] for chrome that floats over scrolling
 * content.
 *
 * [dark] overrides the luminance-based theme detection when non-null.
 */
@Composable
fun Modifier.glassSurface(
  shape: Shape = RoundedCornerShape(GlassDefaults.PillCornerRadius),
  isElevated: Boolean = false,
  rimStroke: Dp = GlassDefaults.RimStrokeWidth,
  dark: Boolean? = null,
): Modifier {
  val isDark = dark ?: (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
  val surfaceColor =
    when {
      isDark && isElevated -> GlassDefaults.DarkSurfaceElevated
      isDark -> GlassDefaults.DarkSurface
      isElevated -> GlassDefaults.LightSurfaceElevated
      else -> GlassDefaults.LightSurface
    }
  val rimBrush = if (isDark) GlassDefaults.DarkRimGradient else GlassDefaults.LightRimGradient

  return this.clip(shape)
    .background(surfaceColor, shape)
    .then(if (rimStroke > 0.dp) Modifier.border(rimStroke, rimBrush, shape) else Modifier)
}

/**
 * Backdrop blur for chrome that floats over scrolling content. [hazeState] must be attached to that
 * content with `Modifier.hazeSource`. Haze blurs the captured content behind the surface and tints
 * it; only where blur is unavailable (for example hardware acceleration is off) does Haze draw the
 * solid elevated glass colour as a scrim instead. The specular rim and corner clip match
 * [glassSurface].
 *
 * [backgroundColor] is the colour painted behind the [hazeState] source content; it is passed to
 * Haze explicitly so the blur never runs without one. [dark] overrides the luminance-based theme
 * detection when non-null. [tint] replaces the default backdrop tint when non-null.
 */
@Composable
fun Modifier.glassBackdrop(
  hazeState: HazeState,
  backgroundColor: Color,
  shape: Shape = RoundedCornerShape(GlassDefaults.PillCornerRadius),
  dark: Boolean? = null,
  tint: Color? = null,
): Modifier {
  val isDark = dark ?: (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
  val rimBrush = if (isDark) GlassDefaults.DarkRimGradient else GlassDefaults.LightRimGradient
  val style = HazeBlurStyle {
    backgroundColor(backgroundColor)
    colorEffects(
      listOf(
        HazeColorEffect.tint(
          tint ?: if (isDark) GlassDefaults.DarkBackdropTint else GlassDefaults.LightBackdropTint
        )
      )
    )
    fallbackColorEffect(
      HazeColorEffect.tint(
        if (isDark) GlassDefaults.DarkSurfaceElevated else GlassDefaults.LightSurfaceElevated
      )
    )
  }

  return this.clip(shape)
    .hazeBlur(input = HazeInput.Sources(hazeState), style = style)
    .border(GlassDefaults.RimStrokeWidth, rimBrush, shape)
}

/**
 * Pill chip state: a selected chip is a solid `primaryContainer` fill with a 1dp primary outline,
 * so it reads in both themes; an unselected chip stays a plain glass surface.
 */
@Composable
fun Modifier.glassPillSelection(
  selected: Boolean,
  shape: Shape = RoundedCornerShape(GlassDefaults.PillCornerRadius),
): Modifier {
  if (!selected) return glassSurface(shape = shape)
  return this.clip(shape)
    .background(MaterialTheme.colorScheme.primaryContainer, shape)
    .border(1.dp, MaterialTheme.colorScheme.primary, shape)
}

/** Micro-interaction modifier that applies tactile spring scale-on-press feedback. */
@Composable
fun Modifier.pressScale(
  targetScale: Float = GlassDefaults.PressScale,
  enabled: Boolean = true,
): Modifier {
  if (!enabled) return this

  var isPressed by remember { mutableStateOf(false) }
  val scale by
    animateFloatAsState(
      targetValue = if (isPressed) targetScale else 1f,
      animationSpec = GlassDefaults.PressSpringSpec,
      label = "glass_press_scale",
    )

  return this.graphicsLayer {
      scaleX = scale
      scaleY = scale
    }
    .pointerInput(enabled, targetScale) {
      awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        try {
          // Quick taps and flings lift or cancel within the delay and never scale.
          val settled =
            withTimeoutOrNull(100L) {
              waitForUpOrCancellation()
              true
            }
          if (settled == null) {
            isPressed = true
            waitForUpOrCancellation()
          }
        } finally {
          isPressed = false
        }
      }
    }
}
