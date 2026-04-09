// swift-tools-version:5.9
//
// Tiny helper that acts as the XPC bridge between the Kotlin UI and the
// Audio Server Plug-in. The UI never talks to CoreAudio directly – it
// connects to this helper, which in turn queries the virtual device's
// custom properties and forwards volume/mute commands.

import PackageDescription

let package = Package(
    name: "VolumeManagerHelper",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "VolumeManagerHelper", targets: ["VolumeManagerHelper"])
    ],
    targets: [
        .executableTarget(
            name: "VolumeManagerHelper",
            path: "Sources/VolumeManagerHelper"
        )
    ]
)
