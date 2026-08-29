import XCTest
@testable import BranchDAM

final class BackgroundSyncManagerTests: XCTestCase {

    func testSyncTaskIdConstant() {
        XCTAssertEqual(BackgroundSyncManager.syncTaskId, "com.branchdam.mobile.sync")
    }
}
