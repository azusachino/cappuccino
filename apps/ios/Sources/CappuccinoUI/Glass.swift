import SwiftUI

/// Liquid Glass surfaces shared by the screens. iOS 26 and later use the glass APIs; iOS 17–25
/// use the matching material, so one call site serves both.
extension View {
  /// Bottom placement for the composer: a floating bar on iOS 26, an inset before.
  @ViewBuilder func bottomComposer<Panel: View>(@ViewBuilder _ panel: () -> Panel) -> some View {
    #if os(iOS)
      if #available(iOS 26, *) {
        self.safeAreaBar(edge: .bottom) { panel() }
      } else {
        self.safeAreaInset(edge: .bottom) { panel() }
      }
    #else
      self.safeAreaInset(edge: .bottom) { panel() }
    #endif
  }

  /// A glass panel on iOS 26 and later, a material panel before.
  @ViewBuilder func glassPanel(cornerRadius: CGFloat) -> some View {
    if #available(iOS 26, macOS 26, *) {
      self.glassEffect(.regular, in: .rect(cornerRadius: cornerRadius))
    } else {
      self.background(.regularMaterial, in: RoundedRectangle(cornerRadius: cornerRadius))
    }
  }

  @ViewBuilder func glassButton(tint: Color? = nil) -> some View {
    if #available(iOS 26, macOS 26, *) {
      self.buttonStyle(.glass).tint(tint)
    } else {
      self.buttonStyle(.bordered).tint(tint)
    }
  }

  @ViewBuilder func glassProminentButton() -> some View {
    if #available(iOS 26, macOS 26, *) {
      self.buttonStyle(.glassProminent)
    } else {
      self.buttonStyle(.borderedProminent)
    }
  }
}

/// Groups the composer's glass so its panels blend on iOS 26; a plain container before.
struct GlassGroup<Content: View>: View {
  @ViewBuilder let content: () -> Content

  var body: some View {
    if #available(iOS 26, macOS 26, *) {
      GlassEffectContainer { content() }
    } else {
      content()
    }
  }
}
