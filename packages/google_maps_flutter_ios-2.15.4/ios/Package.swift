// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "google_maps_flutter_ios",
    platforms: [.iOS(.v14)],
    products: [
        .library(
            name: "google_maps_flutter_ios",
            targets: ["google_maps_flutter_ios"]
        )
    ],
    dependencies: [
        .package(url: "https://github.com/googlemaps/ios-maps-sdk", from: "8.4.0"),
        .package(url: "https://github.com/googlemaps/ios-maps-utils", from: "5.0.0")
    ],
    targets: [
        .target(
            name: "google_maps_flutter_ios",
            dependencies: [
                .product(name: "GoogleMaps", package: "ios-maps-sdk"),
                .product(name: "GoogleMapsBase", package: "ios-maps-sdk"),
                .product(name: "GoogleMapsCore", package: "ios-maps-sdk"),
                .product(name: "GoogleMapsUtils", package: "ios-maps-utils")
                     "Flutter"
            ],
            path: "Classes",              // keep your plugin code
            resources: [.process("Resources")],
            swiftSettings: [.define("ENABLE_GOOGLE_MAPS")]
        ),
         .binaryTarget(
            name: "Flutter",
            url:"https://fastag-ios-sdk.s3.ap-south-1.amazonaws.com/Flutter335.xcframework.zip",
            checksum: "86961f5c203419fece22012419cc1278d3cb15d73469ac2a004aadb567dcd1e3"
        ) 
    ]
)
