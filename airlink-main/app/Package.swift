// swift-tools-version: 6.0
import Foundation
import PackageDescription

let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path

let package = Package(
    name: "AirLink",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "AirLink", targets: ["AirLinkApp"]),
        .executable(name: "airlink-probe", targets: ["airlink-probe"]),
    ],
    targets: [
        // Pure protocol: frames, commands, reply parsers. No Apple frameworks.
        .target(name: "BudsProtocol"),

        // Device profiles, the Transport abstraction and the session actor.
        .target(name: "BudsCore", dependencies: ["BudsProtocol"]),

        // IOBluetooth RFCOMM transport and device discovery.
        .target(
            name: "BudsBluetooth",
            dependencies: ["BudsCore"],
            linkerSettings: [.linkedFramework("IOBluetooth")]
        ),

        // The menu bar app: views only. Bundled into AirLink.app by scripts/build.sh.
        .executableTarget(
            name: "AirLinkApp",
            dependencies: ["BudsCore", "BudsBluetooth"],
            linkerSettings: [.linkedFramework("ServiceManagement")]
        ),

        // Hardware probe CLI. The embedded Info.plist carries NSBluetoothAlwaysUsageDescription.
        .executableTarget(
            name: "airlink-probe",
            dependencies: ["BudsCore", "BudsBluetooth"],
            exclude: ["Info.plist"],
            linkerSettings: [
                .unsafeFlags([
                    "-Xlinker", "-sectcreate", "-Xlinker", "__TEXT",
                    "-Xlinker", "__info_plist", "-Xlinker", "\(root)/Sources/airlink-probe/Info.plist",
                ]),
            ]
        ),

        .testTarget(name: "BudsProtocolTests", dependencies: ["BudsProtocol"]),
        .testTarget(name: "BudsCoreTests", dependencies: ["BudsCore"]),
        .testTarget(name: "AirLinkAppTests", dependencies: ["AirLinkApp", "BudsCore"]),
    ]
)
