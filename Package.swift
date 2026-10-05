// swift-tools-version: 6.2
import PackageDescription

let package = Package(
  name: "CappuccinoCore",
  platforms: [.iOS(.v17), .macOS(.v26)],
  products: [.library(name: "CappuccinoCore", targets: ["CappuccinoCore"])],
  targets: [
    .target(name: "CappuccinoCore"),
    .testTarget(name: "CappuccinoCoreTests", dependencies: ["CappuccinoCore"]),
  ]
)
