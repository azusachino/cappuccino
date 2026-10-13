import SwiftUI

/// Settings' appearance choice. Catppuccin Latte (light) and Mocha (dark) are the only palettes.
public enum AppearanceMode: String, CaseIterable, Identifiable, Sendable {
  case system, light, dark

  public static let storageKey = "appearance"

  public var id: String { rawValue }

  public var title: String {
    switch self {
    case .system: "System"
    case .light: "Light"
    case .dark: "Dark"
    }
  }

  public var colorScheme: ColorScheme? {
    switch self {
    case .system: nil
    case .light: .light
    case .dark: .dark
    }
  }
}

/// The Catppuccin roles the app uses. Status colour always comes with its word, never alone.
struct CatppuccinPalette {
  let background: Color
  let text: Color
  let muted: Color
  let accent: Color
  let ok: Color
  let warn: Color
  let error: Color

  static let latte = CatppuccinPalette(
    background: Color(hex: 0xEFF1F5), text: Color(hex: 0x4C4F69), muted: Color(hex: 0x6C6F85),
    accent: Color(hex: 0x8839EF), ok: Color(hex: 0x40A02B), warn: Color(hex: 0xDF8E1D),
    error: Color(hex: 0xD20F39))

  static let mocha = CatppuccinPalette(
    background: Color(hex: 0x1E1E2E), text: Color(hex: 0xCDD6F4), muted: Color(hex: 0xA6ADC8),
    accent: Color(hex: 0xCBA6F7), ok: Color(hex: 0xA6E3A1), warn: Color(hex: 0xF9E2AF),
    error: Color(hex: 0xF38BA8))

  static func current(_ scheme: ColorScheme) -> CatppuccinPalette {
    scheme == .dark ? mocha : latte
  }
}

extension Color {
  init(hex: UInt32) {
    self.init(
      .sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
      blue: Double(hex & 0xFF) / 255, opacity: 1)
  }
}

extension View {
  /// Liquid Glass tab bar on iOS 26 and later; the system default before.
  @ViewBuilder func glassTabBar() -> some View {
    // The package also compiles for macOS, where this iOS-only modifier does not exist.
    #if os(iOS)
      if #available(iOS 26, *) {
        self.tabBarMinimizeBehavior(.onScrollDown)
      } else {
        self
      }
    #else
      self
    #endif
  }
}

extension View {
  /// iOS keyboard and title modifiers. The package also compiles for macOS, where they do not exist.
  @ViewBuilder func urlEntry() -> some View {
    #if os(iOS)
      self.keyboardType(.URL).textInputAutocapitalization(.never)
    #else
      self
    #endif
  }

  @ViewBuilder func inlineTitle() -> some View {
    #if os(iOS)
      self.navigationBarTitleDisplayMode(.inline)
    #else
      self
    #endif
  }
}
