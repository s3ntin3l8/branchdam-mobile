import XCTest
@testable import BranchDAM

final class AppleSafeSpaceManagerTests: XCTestCase {

    override func setUp() {
        super.setUp()
        _ = BranchDamCoreBridge.shared.initialize(
            dbPath: NSTemporaryDirectory() + "safe_space_test.db",
            baseURL: "http://localhost:8080"
        )
    }

    func testReclaimSafeSpace() {
        let candidates = [
            (localId: "ph://verified-1", sizeBytes: Int64(45_000_000), isVerified: true),
            (localId: "ph://unverified-2", sizeBytes: Int64(30_000_000), isVerified: false)
        ]

        let report = AppleSafeSpaceManager.reclaimSafeSpace(candidates: candidates)
        XCTAssertEqual(report.totalCandidates, 2)
        XCTAssertEqual(report.verifiedCount, 1)
        XCTAssertEqual(report.reclaimedCount, 1)
        XCTAssertEqual(report.estimatedBytesFreed, 45_000_000)
    }
}
