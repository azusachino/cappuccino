// swift-tools-version: 6.2
import PackageDescription

let package = Package(
  name: "CappuccinoApple",
  // iroh-ffi needs iOS 17.5. Its v1.2.0 tag is the same iroh release the Android app pins.
  platforms: [.iOS("17.5"), .macOS(.v26)],
  products: [
    .library(name: "CappuccinoCore", targets: ["CappuccinoCore"]),
    .library(name: "CappuccinoUI", targets: ["CappuccinoUI"]),
    .library(name: "Cappuccino", targets: ["CappuccinoApp"]),
  ],
  dependencies: [
    .package(url: "https://github.com/n0-computer/iroh-ffi.git", exact: "1.2.0")
  ],
  targets: [
    .target(
      name: "CappuccinoCore",
      dependencies: [.product(name: "IrohLib", package: "iroh-ffi")]),
    .target(name: "CappuccinoUI", dependencies: ["CappuccinoCore"]),
    // The iPhone entry point, shared by xtool (this product) and the Xcode app
    // target in project.yml, which compiles the same sources directly. The target
    // is not named Cappuccino: a package target of that name collides with the
    // Xcode app target in xcodebuild's UI-test host lookup.
    .target(
      name: "CappuccinoApp", dependencies: ["CappuccinoCore", "CappuccinoUI"], path: "App",
      exclude: ["Debug-Info.plist", "Info.plist"]),
    .testTarget(name: "CappuccinoCoreTests", dependencies: ["CappuccinoCore"]),
  ]
)
