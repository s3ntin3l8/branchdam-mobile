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

        wait(for: [expectation], timeout: 15.0)
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
            return .success(uploaded: 1, eventsSent: 0)
        }
        manager.afterBatchSweep = { calls.record("sweep") }

        let finished = expectation(description: "immediate sync finished")
        manager.triggerImmediateSync(isOnCellular: false) { uploaded in
            XCTAssertTrue(uploaded)
            finished.fulfill()
        }
        wait(for: [finished], timeout: 15.0)

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
            return .success(uploaded: 0, eventsSent: 0)
        }
        manager.afterBatchSweep = { calls.record("sweep") }

        let skipped = expectation(description: "sync skipped on cellular")
        manager.triggerImmediateSync(isOnCellular: true) { _ in skipped.fulfill() }
        wait(for: [skipped], timeout: 15.0)

        XCTAssertEqual(calls.calls, [])
    }

    // MARK: - AUTH_FAILED outcome recording (#306)

    func testRecordOutcomeSetsAndClearsAuthFailedTimestamp() {
        let suiteName = "BackgroundSyncManagerTests.recordOutcome.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let key = BranchDamKeys.syncAuthFailedAt.rawValue
        let t1 = Date(timeIntervalSince1970: 1_000)
        let t2 = Date(timeIntervalSince1970: 2_000)

        BackgroundSyncManager.recordOutcome(.failed(code: CoreErrorCodes.authFailed), defaults: defaults, now: t1)
        XCTAssertEqual(defaults.double(forKey: key), 1_000)

        // Other failures leave the recorded AUTH_FAILED timestamp untouched.
        BackgroundSyncManager.recordOutcome(.failed(code: "NETWORK_ERROR"), defaults: defaults, now: t2)
        XCTAssertEqual(defaults.double(forKey: key), 1_000)
        BackgroundSyncManager.recordOutcome(.failed(code: nil), defaults: defaults, now: t2)
        XCTAssertEqual(defaults.double(forKey: key), 1_000)

        // A later success clears the key.
        BackgroundSyncManager.recordOutcome(.success(uploaded: 0, eventsSent: 0), defaults: defaults, now: t2)
        XCTAssertNil(defaults.object(forKey: key))

        // Non-auth failures when unset do not set the key.
        BackgroundSyncManager.recordOutcome(.failed(code: "NETWORK_ERROR"), defaults: defaults, now: t2)
        XCTAssertNil(defaults.object(forKey: key))
    }

    func testTriggerImmediateSyncAuthFailedStoresSyncAuthFailedAt() {
        let manager = BackgroundSyncManager.shared
        let savedBatch = manager.runSyncBatch
        let savedSweep = manager.afterBatchSweep
        let key = BranchDamKeys.syncAuthFailedAt.rawValue
        let savedAuthFailedAt = UserDefaults.standard.object(forKey: key)
        UserDefaults.standard.removeObject(forKey: key)
        defer {
            manager.runSyncBatch = savedBatch
            manager.afterBatchSweep = savedSweep
            if let savedAuthFailedAt {
                UserDefaults.standard.set(savedAuthFailedAt, forKey: key)
            } else {
                UserDefaults.standard.removeObject(forKey: key)
            }
        }

        manager.runSyncBatch = { _, _ in .failed(code: CoreErrorCodes.authFailed) }
        manager.afterBatchSweep = {}

        let finished = expectation(description: "auth failed sync finished")
        manager.triggerImmediateSync(isOnCellular: false) { success in
            XCTAssertFalse(success)
            finished.fulfill()
        }
        wait(for: [finished], timeout: 15.0)

        XCTAssertGreaterThan(UserDefaults.standard.double(forKey: key), 0)
    }

    // MARK: - Background sync exit paths (#305)

    func testBackgroundSyncExpiryCancelsCompletesFalseAndSchedulesOnce() {
        let manager = BackgroundSyncManager.shared
        let savedBatch = manager.runSyncBatch
        let savedSweep = manager.afterBatchSweep
        let savedSchedule = manager.schedule
        let key = BranchDamKeys.syncAuthFailedAt.rawValue
        let savedAuthFailedAt = UserDefaults.standard.object(forKey: key)
        defer {
            manager.runSyncBatch = savedBatch
            manager.afterBatchSweep = savedSweep
            manager.schedule = savedSchedule
            if let savedAuthFailedAt {
                UserDefaults.standard.set(savedAuthFailedAt, forKey: key)
            } else {
                UserDefaults.standard.removeObject(forKey: key)
            }
        }

        let batchStarted = expectation(description: "batch started")
        let batchSwept = expectation(description: "batch swept")
        let releaseBatch = DispatchSemaphore(value: 0)
        let calls = SyncCallRecorder()

        manager.runSyncBatch = { _, _ in
            batchStarted.fulfill()
            releaseBatch.wait()
            return .success(uploaded: 0, eventsSent: 0)
        }
        manager.afterBatchSweep = { batchSwept.fulfill() }
        manager.schedule = { calls.record("schedule") }

        let expire = manager.runBackgroundSync(
            isOnCellular: false,
            queue: .global(qos: .userInitiated),
            complete: { success in calls.record("complete(\(success))") },
            cancel: { calls.record("cancel") }
        )

        wait(for: [batchStarted], timeout: 15.0)
        expire()

        XCTAssertEqual(calls.calls, ["cancel", "complete(false)", "schedule"])

        releaseBatch.signal()
        wait(for: [batchSwept], timeout: 15.0)
    }

    func testBackgroundSyncExpiryThenBatchCompletionCompletesAndSchedulesOnce() {
        let manager = BackgroundSyncManager.shared
        let savedBatch = manager.runSyncBatch
        let savedSweep = manager.afterBatchSweep
        let savedSchedule = manager.schedule
        let key = BranchDamKeys.syncAuthFailedAt.rawValue
        let savedAuthFailedAt = UserDefaults.standard.object(forKey: key)
        defer {
            manager.runSyncBatch = savedBatch
            manager.afterBatchSweep = savedSweep
            manager.schedule = savedSchedule
            if let savedAuthFailedAt {
                UserDefaults.standard.set(savedAuthFailedAt, forKey: key)
            } else {
                UserDefaults.standard.removeObject(forKey: key)
            }
        }

        let batchStarted = expectation(description: "batch started")
        let batchSwept = expectation(description: "batch swept")
        let releaseBatch = DispatchSemaphore(value: 0)
        let calls = SyncCallRecorder()

        manager.runSyncBatch = { _, _ in
            batchStarted.fulfill()
            releaseBatch.wait()
            return .success(uploaded: 1, eventsSent: 0)
        }
        manager.afterBatchSweep = { batchSwept.fulfill() }
        manager.schedule = { calls.record("schedule") }

        let expire = manager.runBackgroundSync(
            isOnCellular: false,
            queue: .global(qos: .userInitiated),
            complete: { success in calls.record("complete(\(success))") },
            cancel: { calls.record("cancel") }
        )

        wait(for: [batchStarted], timeout: 15.0)
        expire()
        releaseBatch.signal()
        wait(for: [batchSwept], timeout: 15.0)
        // Give the background closure a moment to reach tracker.completeOnce after sweep().
        Thread.sleep(forTimeInterval: 0.05)

        XCTAssertEqual(calls.calls, ["cancel", "complete(false)", "schedule"])
    }

    func testBackgroundSyncMobileDataSkipCompletesTrueAndSchedulesOnce() {
        let manager = BackgroundSyncManager.shared
        manager.syncOnMobileData = false
        let savedBatch = manager.runSyncBatch
        let savedSweep = manager.afterBatchSweep
        let savedSchedule = manager.schedule
        defer {
            manager.runSyncBatch = savedBatch
            manager.afterBatchSweep = savedSweep
            manager.schedule = savedSchedule
        }

        let calls = SyncCallRecorder()
        manager.runSyncBatch = { _, _ in
            XCTFail("batch should not run when skipped on mobile data")
            return .success(uploaded: 0, eventsSent: 0)
        }
        manager.afterBatchSweep = {
            XCTFail("sweep should not run when skipped on mobile data")
        }
        manager.schedule = { calls.record("schedule") }

        manager.runBackgroundSync(
            isOnCellular: true,
            complete: { success in calls.record("complete(\(success))") },
            cancel: { calls.record("cancel") }
        )

        XCTAssertEqual(calls.calls, ["complete(true)", "schedule"])
    }

    func testBackgroundSyncNormalCompletionCompletesTrueAndSchedulesOnce() {
        let manager = BackgroundSyncManager.shared
        let savedBatch = manager.runSyncBatch
        let savedSweep = manager.afterBatchSweep
        let savedSchedule = manager.schedule
        let key = BranchDamKeys.syncAuthFailedAt.rawValue
        let savedAuthFailedAt = UserDefaults.standard.object(forKey: key)
        defer {
            manager.runSyncBatch = savedBatch
            manager.afterBatchSweep = savedSweep
            manager.schedule = savedSchedule
            if let savedAuthFailedAt {
                UserDefaults.standard.set(savedAuthFailedAt, forKey: key)
            } else {
                UserDefaults.standard.removeObject(forKey: key)
            }
        }

        let scheduled = expectation(description: "background sync scheduled")
        let calls = SyncCallRecorder()
        manager.runSyncBatch = { _, _ in .success(uploaded: 1, eventsSent: 0) }
        manager.afterBatchSweep = {}
        manager.schedule = {
            calls.record("schedule")
            scheduled.fulfill()
        }

        manager.runBackgroundSync(
            isOnCellular: false,
            queue: .global(qos: .userInitiated),
            complete: { success in calls.record("complete(\(success))") },
            cancel: { calls.record("cancel") }
        )

        wait(for: [scheduled], timeout: 15.0)
        XCTAssertEqual(calls.calls, ["complete(true)", "schedule"])
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
