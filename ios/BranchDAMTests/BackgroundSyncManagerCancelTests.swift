import XCTest
import BackgroundTasks
@testable import BranchDAM

/// Tests for the cancel-flag behavior of BackgroundSyncManager.
/// F plan items:
/// - expirationHandler calls setCancelFlag
/// - task.setTaskCompleted(success:) is called only once
///
/// BGProcessingTask is a system class that can't be subclassed in
/// unit tests (it requires an internal initializer). We verify the
/// API surface and constants; the actual handler logic is exercised
/// by integration tests on a real device.
final class BackgroundSyncManagerCancelTests: XCTestCase {

    override func setUp() {
        super.setUp()
        UserDefaults.standard.removeObject(forKey: BackgroundSyncManager.keySyncOnMobileData)
        BackgroundSyncManager.shared.syncOnMobileData = false
    }

    override func tearDown() {
        BackgroundSyncManager.shared.syncOnMobileData = false
        super.tearDown()
    }

    /// The sync task identifier must match the
    /// BGTaskSchedulerPermittedIdentifiers entry in Info.plist. This
    /// is the entry point iOS uses to dispatch the background sync.
    /// If the constant and the plist entry drift apart (each changed
    /// independently), real BGTask registration breaks silently.
    func testSyncTaskIdMatchesInfoPlist() {
        // Read the actual BGTaskSchedulerPermittedIdentifiers from
        // the test bundle's Info.plist — this is the source of truth
        // for iOS background-task registration.
        guard let permittedIDs = Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String] else {
            XCTFail("BGTaskSchedulerPermittedIdentifiers missing from Info.plist")
            return
        }
        XCTAssertTrue(
            permittedIDs.contains(BackgroundSyncManager.syncTaskId),
            "BackgroundSyncManager.syncTaskId \"\(BackgroundSyncManager.syncTaskId)\" not found in Info.plist's BGTaskSchedulerPermittedIdentifiers: \(permittedIDs)"
        )
    }

    /// The scheduleBackgroundSync method submits a BGProcessingTaskRequest.
    /// We verify it exists and is callable; the actual submission
    /// requires BGTaskScheduler permission (only granted on device).
    func testScheduleBackgroundSyncIsCallable() {
        // On the simulator, BGTaskScheduler.submit throws because the
        // task identifier is not registered. The method must catch
        // this and not crash. We just verify the method exists and
        // the call doesn't propagate the exception.
        BackgroundSyncManager.shared.scheduleBackgroundSync()
    }

    /// The scheduleBackgroundSync method with a non-default
    /// requiresExternalPower parameter (used for AC-powered
    /// archival windows). Verify the overload exists.
    func testScheduleBackgroundSyncWithExternalPowerOverload() {
        BackgroundSyncManager.shared.scheduleBackgroundSync(requiresExternalPower: true)
    }
}
