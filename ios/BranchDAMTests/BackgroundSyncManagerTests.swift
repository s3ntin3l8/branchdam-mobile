import XCTest
@testable import BranchDAM

final class BackgroundSyncManagerTests: XCTestCase {

    /// The `syncOnMobileData` value stored before the test, restored in
    /// tearDown so no test leaks the shared singleton's preference into
    /// the next one (or into the test host's defaults).
    private var savedSyncOnMobileData: Any?

    override func setUp() {
        super.setUp()
        savedSyncOnMobileData = UserDefaults.standard.object(forKey: BackgroundSyncManager.keySyncOnMobileData)
        UserDefaults.standard.removeObject(forKey: BackgroundSyncManager.keySyncOnMobileData)
    }

    override func tearDown() {
        if let saved = savedSyncOnMobileData {
            UserDefaults.standard.set(saved, forKey: BackgroundSyncManager.keySyncOnMobileData)
        } else {
            UserDefaults.standard.removeObject(forKey: BackgroundSyncManager.keySyncOnMobileData)
        }
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

    // MARK: - Measured network path (NWPathMonitor)

    func testMobileDataDecision() {
        XCTAssertFalse(BackgroundSyncManager.isMobileData(isExpensive: false, usesCellular: false), "Wi-Fi / Ethernet")
        XCTAssertTrue(BackgroundSyncManager.isMobileData(isExpensive: false, usesCellular: true), "cellular")
        XCTAssertTrue(BackgroundSyncManager.isMobileData(isExpensive: true, usesCellular: false), "hotspot from a phone")
        XCTAssertTrue(BackgroundSyncManager.isMobileData(isExpensive: true, usesCellular: true))
    }

    func testCellularGateCombinesMeasuredPathAndSetting() {
        let manager = BackgroundSyncManager.shared
        let onCellular = BackgroundSyncManager.isMobileData(isExpensive: true, usesCellular: true)

        manager.syncOnMobileData = false
        XCTAssertFalse(manager.shouldAllowImmediateSync(isOnCellular: onCellular))
        manager.syncOnMobileData = true
        XCTAssertTrue(manager.shouldAllowImmediateSync(isOnCellular: onCellular))
    }

    func testMeasuredPathIsReadable() {
        // The simulator's path depends on the host; the property must
        // just be readable without blocking.
        _ = BackgroundSyncManager.shared.isOnCellular
    }

    // MARK: - Post-batch staging sweep (Hermes on #210)

    func testImmediateSyncSweepsStagingAfterTheBatch() {
        let manager = BackgroundSyncManager.shared
        let savedBatch = manager.runSyncBatch
        let savedSweep = manager.afterBatchSweep
        defer {
            manager.runSyncBatch = savedBatch
            manager.afterBatchSweep = savedSweep
        }
        let calls = SyncCallRecorder()
        manager.runSyncBatch = { _, _ in
            calls.record("batch")
            return (uploaded: 1, eventsSent: 0)
        }
        manager.afterBatchSweep = { calls.record("sweep") }

        let finished = expectation(description: "immediate sync finished")
        manager.triggerImmediateSync(isOnCellular: false) { uploaded in
            XCTAssertTrue(uploaded)
            finished.fulfill()
        }
        wait(for: [finished], timeout: 2.0)

        XCTAssertEqual(calls.calls, ["batch", "sweep"], "the sweep runs once, after the batch")
    }

    func testSkippedSyncDoesNotSweep() {
        let manager = BackgroundSyncManager.shared
        manager.syncOnMobileData = false
        let savedBatch = manager.runSyncBatch
        let savedSweep = manager.afterBatchSweep
        defer {
            manager.runSyncBatch = savedBatch
            manager.afterBatchSweep = savedSweep
        }
        let calls = SyncCallRecorder()
        manager.runSyncBatch = { _, _ in
            calls.record("batch")
            return (uploaded: 0, eventsSent: 0)
        }
        manager.afterBatchSweep = { calls.record("sweep") }

        let skipped = expectation(description: "sync skipped on cellular")
        manager.triggerImmediateSync(isOnCellular: true) { _ in skipped.fulfill() }
        wait(for: [skipped], timeout: 1.0)

        XCTAssertEqual(calls.calls, [])
    }
}

/// Records the sync seams' calls in order. A separate Sendable box, because
/// the seams are @Sendable and the XCTestCase itself is not.
private final class SyncCallRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var _calls: [String] = []

    func record(_ call: String) {
        lock.lock()
        _calls.append(call)
        lock.unlock()
    }

    var calls: [String] {
        lock.lock()
        defer { lock.unlock() }
        return _calls
    }
}
