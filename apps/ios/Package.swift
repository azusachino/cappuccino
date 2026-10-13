// swift-tools-version: 6.2
import PackageDescription

let package = Package(
  name: "CappuccinoApple",
  platforms: [.iOS(.v17), .macOS(.v26)],
  products: [
    .library(name: "CappuccinoCore", targets: ["CappuccinoCore"]),
    .library(name: "CappuccinoUI", targets: ["CappuccinoUI"]),
  ],
  targets: [
    .target(name: "CappuccinoCore"),
    .target(name: "CappuccinoUI", dependencies: ["CappuccinoCore"]),
    .testTarget(name: "CappuccinoCoreTests", dependencies: ["CappuccinoCore"]),
  ]
)
