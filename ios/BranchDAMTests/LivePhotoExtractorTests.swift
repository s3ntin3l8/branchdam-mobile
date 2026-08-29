import XCTest
@testable import BranchDAM

final class LivePhotoExtractorTests: XCTestCase {

    func testLivePhotoLinking() {
        let eventUuid = LivePhotoExtractor.linkLivePhoto(
            stillId: "ph://still-1",
            videoId: "ph://video-1",
            stillFilename: "IMG_2001.HEIC",
            videoFilename: "IMG_2001.MOV"
        )
        XCTAssertFalse(eventUuid.isEmpty)
    }
}
