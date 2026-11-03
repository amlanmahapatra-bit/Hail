// swift-tools-version:5.9
// Pure-Swift logic shared by the iPhone app and its screen-reading extension. Builds and tests on
// Linux too (no UIKit), so it is verified without a Mac: `swift test`.
import PackageDescription

let package = Package(
    name: "HailCore",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [.library(name: "HailCore", targets: ["HailCore"])],
    targets: [
        .target(name: "HailCore"),
        .testTarget(name: "HailCoreTests", dependencies: ["HailCore"]),
    ],
    swiftLanguageVersions: [.v5]
)
