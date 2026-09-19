import XCTest
@testable import BranchDAM

/// Tests for AppleSafeSpaceManager.reclaimSafeSpace — covers the
/// ineligible and error paths that the original tests missed.
final class AppleSafeSpaceManagerEligibleTests: XCTestCase {

    override func setUp() {
        super.setUp()
        _ = BranchDamCoreBridge.shared.initialize(
            dbPath: NSTemporaryDirectory() + "safe_space_test_\(UUID().uuidString).db",
            baseURL: "http://localhost:8080"
        )
    }

    func testUnverifiedCandidateIsSkipped() {
        var deleteCalled = false
        let report = AppleSafeSpaceManager.reclaimSafeSpace(
            candidates: [
                SafeSpaceCandidate(localId: "ph://unverified-1", sizeBytes: Int64(30_000_000), isVerified: false)
            ],
            deletionHandler: { _ in
                deleteCalled = true
                return true
            }
        )

        XCTAssertEqual(report.totalCandidates, 1)
        XCTAssertEqual(report.verifiedCount, 0)
        XCTAssertEqual(report.reclaimedCount, 0)
        XCTAssertEqual(report.estimatedBytesFreed, 0)
        XCTAssertFalse(deleteCalled, "deletion must not be called for unverified candidates")
    }

    func testMixedBatchWithUnverifiedAndVerified() {
        let deleted = NSMutableOrderedSet()
        let report = AppleSafeSpaceManager.reclaimSafeSpace(
            candidates: [
                SafeSpaceCandidate(localId: "ph://unverified", sizeBytes: Int64(10_000_000), isVerified: false),
                SafeSpaceCandidate(localId: "ph://verified-1", sizeBytes: Int64(20_000_000), isVerified: true),
                SafeSpaceCandidate(localId: "ph://verified-2", sizeBytes: Int64(30_000_000), isVerified: true),
            ],
            deletionHandler: { localId in
                deleted.add(localId)
                return true
            }
        )

        XCTAssertEqual(report.totalCandidates, 3)
        XCTAssertEqual(report.verifiedCount, 2)
        XCTAssertEqual(report.reclaimedCount, 2)
        XCTAssertEqual(deleted.count, 2)
    }

    func testDeletionHandlerReturningFalseDoesNotReclaim() {
        let report = AppleSafeSpaceManager.reclaimSafeSpace(
            candidates: [
                SafeSpaceCandidate(localId: "ph://delete-fail", sizeBytes: Int64(50_000_000), isVerified: true)
            ],
            deletionHandler: { _ in false }
        )

        XCTAssertEqual(report.reclaimedCount, 0)
        XCTAssertEqual(report.estimatedBytesFreed, 0)
    }

    func testEmptyCandidateList() {
        let report = AppleSafeSpaceManager.reclaimSafeSpace(
            candidates: [],
            deletionHandler: nil
        )

        XCTAssertEqual(report.totalCandidates, 0)
        XCTAssertEqual(report.verifiedCount, 0)
        XCTAssertEqual(report.reclaimedCount, 0)
        XCTAssertEqual(report.estimatedBytesFreed, 0)
    }
}
