import XCTest
@testable import BranchDAM

final class ApplePairDetectorTests: XCTestCase {

    func testProRawPairDetection() {
        let masters = [
            (id: "ph://master-1", filename: "IMG_1001.DNG", dateUnix: Int64(1724000000))
        ]
        let derivatives = [
            (id: "ph://child-1", filename: "IMG_1001.HEIC", dateUnix: Int64(1724000000))
        ]

        let pairs = ApplePairDetector.findProRawPairs(masters: masters, derivatives: derivatives)
        XCTAssertEqual(pairs.count, 1)
        XCTAssertEqual(pairs[0].masterLocalId, "ph://master-1")
        XCTAssertEqual(pairs[0].derivativeLocalId, "ph://child-1")
        XCTAssertEqual(pairs[0].confidence, 1.00)

        let count = ApplePairDetector.registerPairs(pairs: pairs)
        XCTAssertEqual(count, 1)
    }

    func testPixelTopShotPairDetection() {
        let masters = [
            (id: "ph://master-2", filename: "PXL_20260912_185504997.TS-001-02.ORIGINAL.dng", dateUnix: Int64(1724000000))
        ]
        let derivatives = [
            (id: "ph://child-2", filename: "PXL_20260912_185504997.TS-001-01.jpg", dateUnix: Int64(1724000000))
        ]

        let pairs = ApplePairDetector.findProRawPairs(masters: masters, derivatives: derivatives)
        XCTAssertEqual(pairs.count, 1)
        XCTAssertEqual(pairs[0].masterLocalId, "ph://master-2")
        XCTAssertEqual(pairs[0].derivativeLocalId, "ph://child-2")
        XCTAssertEqual(pairs[0].confidence, 1.00)
    }

    func testExtractStem_NegativeAndEdgeCases() {
        XCTAssertEqual(ApplePairDetector.extractStem("EXAMPLE.dng"), "EXAMPLE")
        XCTAssertEqual(ApplePairDetector.extractStem("MYCOVER.dng"), "MYCOVER")
        XCTAssertEqual(ApplePairDetector.extractStem("RAW_001.dng"), "RAW_001")
        XCTAssertEqual(ApplePairDetector.extractStem("LAST_NIGHT.dng"), "LAST_NIGHT")
        XCTAssertEqual(ApplePairDetector.extractStem("ALBUM_COVER.dng"), "ALBUM_COVER")
        XCTAssertEqual(ApplePairDetector.extractStem("road_trip_ACTION.dng"), "road_trip_ACTION")
        XCTAssertEqual(ApplePairDetector.extractStem("IMG_20260912_120000_PORTRAIT.dng"), "IMG_20260912_120000_PORTRAIT")
        XCTAssertEqual(ApplePairDetector.extractStem("PXL_20260912_185504997.TS-001-02.ORIGINAL.dng"), "PXL_20260912_185504997")
        XCTAssertEqual(ApplePairDetector.extractStem("IMG_20260912_120000.BURST001.dng"), "IMG_20260912_120000")
    }
}
