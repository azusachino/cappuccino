package com.azusachino.cappuccino.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTest {
  @Test
  fun glassSurfacesAreTranslucentAndElevatedIsMoreOpaque() {
    for (base in listOf(GlassDefaults.DarkSurface, GlassDefaults.LightSurface)) assertTrue(
      "Glass surface must stay translucent",
      base.alpha < 1f,
    )
    assertTrue(GlassDefaults.DarkSurfaceElevated.alpha > GlassDefaults.DarkSurface.alpha)
    assertTrue(GlassDefaults.LightSurfaceElevated.alpha > GlassDefaults.LightSurface.alpha)
  }

  @Test
  fun backdropTintsAreLighterThanSurfacesSoBlurShowsThrough() {
    assertTrue(GlassDefaults.DarkBackdropTint.alpha < GlassDefaults.DarkSurface.alpha)
    assertTrue(GlassDefaults.LightBackdropTint.alpha < GlassDefaults.LightSurface.alpha)
  }

  @Test
  fun lightAndDarkGlassTokensAreDistinct() {
    assertTrue(GlassDefaults.DarkSurface != GlassDefaults.LightSurface)
    assertTrue(GlassDefaults.DarkBackdropTint != GlassDefaults.LightBackdropTint)
  }

  @Test
  fun pressScaleSpringsBackToFullSize() {
    assertTrue(GlassDefaults.PressScale in 0.9f..0.99f)
    assertTrue(GlassDefaults.PressSpringSpec.dampingRatio < 1f)
  }
}
