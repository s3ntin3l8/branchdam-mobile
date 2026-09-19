// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "branchdam",
    platforms: [
        .iOS(.v15),
        .macOS(.v12)
    ],
    products: [
        .library(
            name: "branchdam",
            targets: ["branchdam"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "branchdam",
            path: "ios/Frameworks/branchdam.xcframework"
        )
    ]
)
