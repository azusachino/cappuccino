// swift-tools-version:6.2
import PackageDescription

let package = Package(
  name: "cappuccino-daemon",
  platforms: [.macOS(.v26)],
  targets: [
    .target(name: "DaemonCore"),
    .executableTarget(name: "cappuccino-daemon", dependencies: ["DaemonCore"]),
    .executableTarget(name: "capctl", dependencies: ["DaemonCore"]),
    .testTarget(name: "DaemonCoreTests", dependencies: ["DaemonCore"]),
  ]
)
