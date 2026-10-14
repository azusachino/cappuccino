// swift-tools-version: 6.2
import PackageDescription

let package = Package(
  name: "CappuccinoApple",
  platforms: [.iOS(.v17), .macOS(.v26)],
  products: [
    .library(name: "CappuccinoCore", targets: ["CappuccinoCore"]),
    .library(name: "CappuccinoUI", targets: ["CappuccinoUI"]),
    .library(name: "Cappuccino", targets: ["CappuccinoApp"]),
  ],
  targets: [
    .target(name: "CappuccinoCore"),
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
