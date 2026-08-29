import XCTest
@testable import BranchDAM

final class PhotoKitObserverTests: XCTestCase {

    func testDiscoveredAssetModel() {
        let asset = DiscoveredAsset(
            localIdentifier: "TEST-UUID-1234",
            filename: "IMG_0042.DNG",
            creationDateUnix: 1724000000,
            isRaw: true,
            isVideo: false,
            pixelWidth: 8064,
            pixelHeight: 6048
        )

        XCTAssertEqual(asset.localIdentifier, "TEST-UUID-1234")
        XCTAssertEqual(asset.filename, "IMG_0042.DNG")
        XCTAssertEqual(asset.isRaw, true)
        XCTAssertEqual(asset.isVideo, false)
        XCTAssertEqual(asset.pixelWidth, 8064)
    }
}
