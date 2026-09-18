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
}
