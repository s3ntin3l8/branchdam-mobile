import XCTest
@testable import BranchDAM

final class LineageViewModelTests: XCTestCase {

    @MainActor
    func testCandidateQueueAdvancementOnReject() {
        let vm = LineageViewModel()
        let candidate1 = AppleAuditCandidate(
            id: "cand-1",
            masterLocalId: "ph://master-1",
            derivativeLocalId: "ph://deriv-1",
            masterFilename: "IMG_0001.DNG",
            derivativeFilename: "IMG_0001.JPG",
            confidence: 1.00,
            resolver: "ios_apple_proraw_pair"
        )
        let candidate2 = AppleAuditCandidate(
            id: "cand-2",
            masterLocalId: "ph://master-2",
            derivativeLocalId: "ph://deriv-2",
            masterFilename: "IMG_0002.DNG",
            derivativeFilename: "IMG_0002.JPG",
            confidence: 0.95,
            resolver: "ios_apple_proraw_pair"
        )

        vm.candidates = [candidate1, candidate2]
        XCTAssertEqual(vm.currentIndex, 0)

        vm.rejectCandidate(candidate1)
        XCTAssertEqual(vm.currentIndex, 1)

        vm.rejectCandidate(candidate2)
        XCTAssertEqual(vm.currentIndex, 2)
    }

    @MainActor
    func testConfirmCandidateEnqueuesEvent() {
        let vm = LineageViewModel()
        let candidate = AppleAuditCandidate(
            id: "cand-1",
            masterLocalId: "ph://master-1",
            derivativeLocalId: "ph://deriv-1",
            masterFilename: "IMG_0001.DNG",
            derivativeFilename: "IMG_0001.JPG",
            confidence: 1.00,
            resolver: "ios_apple_proraw_pair"
        )

        vm.candidates = [candidate]
        XCTAssertEqual(vm.currentIndex, 0)

        vm.confirmCandidate(candidate)
        XCTAssertEqual(vm.currentIndex, 1)
    }
}
