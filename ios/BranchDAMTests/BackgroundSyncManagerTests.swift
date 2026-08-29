import XCTest
@testable import BranchDAM

final class BackgroundSyncManagerTests: XCTestCase {

    override func setUp() {
        super.setUp()
        UserDefaults.standard.removeObject(forKey: BackgroundSyncManager.keySyncOnMobileData)
    }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: BackgroundSyncManager.keySyncOnMobileData)
        super.tearDown()
    }

    func testSyncTaskIdConstant() {
        XCTAssertEqual(BackgroundSyncManager.syncTaskId, "com.branchdam.mobile.sync")
        XCTAssertEqual(BackgroundSyncManager.keySyncOnMobileData, "branchdam_sync_on_mobile_data")
    }

    func testSyncOnMobileDataPreferencePersistence() {
        let manager = BackgroundSyncManager.shared
        XCTAssertFalse(manager.syncOnMobileData)

        manager.syncOnMobileData = true
        XCTAssertTrue(manager.syncOnMobileData)
        XCTAssertTrue(UserDefaults.standard.bool(forKey: BackgroundSyncManager.keySyncOnMobileData))

        manager.syncOnMobileData = false
        XCTAssertFalse(manager.syncOnMobileData)
    }

    func testShouldAllowImmediateSyncConstraints() {
        let manager = BackgroundSyncManager.shared

        // When syncOnMobileData = false (default)
        manager.syncOnMobileData = false
        XCTAssertTrue(manager.shouldAllowImmediateSync(isOnCellular: false)) // Wi-Fi permitted
        XCTAssertFalse(manager.shouldAllowImmediateSync(isOnCellular: true))  // Cellular blocked

        // When syncOnMobileData = true (opted in)
        manager.syncOnMobileData = true
        XCTAssertTrue(manager.shouldAllowImmediateSync(isOnCellular: false)) // Wi-Fi permitted
        XCTAssertTrue(manager.shouldAllowImmediateSync(isOnCellular: true))  // Cellular permitted
    }

    func testTriggerImmediateSyncBlockedOnCellularWhenDisabled() {
        let manager = BackgroundSyncManager.shared
        manager.syncOnMobileData = false

        let expectation = expectation(description: "Sync skipped on cellular")
        manager.triggerImmediateSync(isOnCellular: true) { allowed in
            XCTAssertFalse(allowed)
            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 1.0)
    }
}
