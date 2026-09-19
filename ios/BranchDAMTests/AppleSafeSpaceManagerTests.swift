import XCTest
@testable import BranchDAM

final class AppleSafeSpaceManagerTests: XCTestCase {

    override func setUp() {
        super.setUp()
        _ = BranchDamCoreBridge.shared.initialize(
            dbPath: NSTemporaryDirectory() + "safe_space_test_\(UUID().uuidString).db",
            baseURL: "http://localhost:8080"
        )
    }

    func testReclaimSafeSpace() {
        let candidates = [
            SafeSpaceCandidate(localId: "ph://verified-1", sizeBytes: Int64(45_000_000), isVerified: true),
            SafeSpaceCandidate(localId: "ph://unverified-2", sizeBytes: Int64(30_000_000), isVerified: false)
        ]
        _ = BranchDamCoreBridge.shared.enqueueMedia(localPath: "/tmp/v1.jpg", filename: "v1.jpg", capturedAtUnix: 1000, localID: "ph://verified-1")
        _ = BranchDamCoreBridge.shared.enqueueMedia(localPath: "/tmp/u2.jpg", filename: "u2.jpg", capturedAtUnix: 1000, localID: "ph://unverified-2")

        let report = AppleSafeSpaceManager.reclaimSafeSpace(candidates: candidates, deletionHandler: { _ in true })
        XCTAssertEqual(report.totalCandidates, 2)
        XCTAssertEqual(report.verifiedCount, 1)
        XCTAssertEqual(report.reclaimedCount, 1)
        XCTAssertEqual(report.estimatedBytesFreed, 45_000_000)

        // Verify offloaded flag was set on successful deletion
        XCTAssertTrue(BranchDamCoreBridge.shared.isMediaOffloaded(localID: "ph://verified-1"))
    }

    func testReclaimSafeSpace_DeletionFailure() {
        let candidates = [
            SafeSpaceCandidate(localId: "ph://verified-fail", sizeBytes: Int64(50_000_000), isVerified: true)
        ]

        // When deletion fails, reclaimedCount should not increment and offloaded should not be set
        let report = AppleSafeSpaceManager.reclaimSafeSpace(candidates: candidates, deletionHandler: { _ in false })
        XCTAssertEqual(report.totalCandidates, 1)
        XCTAssertEqual(report.verifiedCount, 1)
        XCTAssertEqual(report.reclaimedCount, 0)
        XCTAssertEqual(report.estimatedBytesFreed, 0)

        XCTAssertFalse(BranchDamCoreBridge.shared.isMediaOffloaded(localID: "ph://verified-fail"))
    }
}
