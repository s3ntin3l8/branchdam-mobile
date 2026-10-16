import XCTest
import UserNotifications
@testable import BranchDAM

final class ImportNotificationDelegateTests: XCTestCase {

    private let category = AppleCameraRollImportNotifier.categoryIdentifier

    func testRoutesTheThreeActionButtons() {
        XCTAssertEqual(
            ImportNotificationDelegate.route(categoryIdentifier: category, actionIdentifier: AppleCameraRollImportNotifier.actionImportNow),
            .importNow
        )
        XCTAssertEqual(
            ImportNotificationDelegate.route(categoryIdentifier: category, actionIdentifier: AppleCameraRollImportNotifier.actionLater),
            .later
        )
        XCTAssertEqual(
            ImportNotificationDelegate.route(categoryIdentifier: category, actionIdentifier: AppleCameraRollImportNotifier.actionSkip),
            .skip
        )
    }

    func testTappingTheBodyOrAnotherCategoryIsNotAnImportAction() {
        XCTAssertNil(ImportNotificationDelegate.route(categoryIdentifier: category, actionIdentifier: "com.apple.UNNotificationDefaultActionIdentifier"))
        XCTAssertNil(ImportNotificationDelegate.route(categoryIdentifier: category, actionIdentifier: "com.apple.UNNotificationDismissActionIdentifier"))
        XCTAssertNil(ImportNotificationDelegate.route(categoryIdentifier: "other", actionIdentifier: AppleCameraRollImportNotifier.actionSkip))
    }

    func testHandleDeliversTheActionAndPostingTimeToTheHandler() {
        let received = Received()
        let delegate = ImportNotificationDelegate { action, stagedBefore, done in
            received.set(action, stagedBefore)
            done()
        }
        let finished = expectation(description: "completion called")

        let routed = delegate.handle(
            categoryIdentifier: category,
            actionIdentifier: AppleCameraRollImportNotifier.actionImportNow,
            userInfo: [AppleCameraRollImportNotifier.userInfoStagedBeforeKey: NSNumber(value: Int64(1_724_000_000))],
            completion: { finished.fulfill() }
        )

        XCTAssertTrue(routed)
        wait(for: [finished], timeout: 1.0)
        XCTAssertEqual(received.action, .importNow)
        XCTAssertEqual(received.stagedBefore, 1_724_000_000)
    }

    func testUnroutedResponseDoesNotReachTheHandler() {
        let received = Received()
        let delegate = ImportNotificationDelegate { action, stagedBefore, done in
            received.set(action, stagedBefore)
            done()
        }

        let routed = delegate.handle(
            categoryIdentifier: category,
            actionIdentifier: "com.apple.UNNotificationDefaultActionIdentifier",
            userInfo: [:],
            completion: {}
        )

        XCTAssertFalse(routed)
        XCTAssertNil(received.action)
    }

    func testNotificationWithoutAPostingTimeAppliesToEverything() {
        XCTAssertEqual(ImportNotificationDelegate.stagedBefore(userInfo: [:]), .max)
    }

    func testDelegateRoutesIntoTheNotifier() {
        let engine = FakePendingImportEngine()
        let notifier = AppleCameraRollImportNotifier(engine: engine, stager: FakeAssetStager(), afterImport: { _, _ in })
        _ = notifier.stagePendingAsset(DiscoveredAsset(localIdentifier: "A/L0/001", filename: "a.heic", creationDateUnix: 1))
        let delegate = ImportNotificationDelegate { action, stagedBefore, done in
            notifier.handleAction(action, stagedBeforeUnix: stagedBefore) { _ in done() }
        }
        let finished = expectation(description: "skip applied")

        delegate.handle(
            categoryIdentifier: category,
            actionIdentifier: AppleCameraRollImportNotifier.actionSkip,
            userInfo: [:],
            completion: { finished.fulfill() }
        )

        wait(for: [finished], timeout: 2.0)
        XCTAssertTrue(engine.isImportSuppressed(localID: "A/L0/001"))
    }

    func testAppInstallsTheDelegate() {
        // BranchDAMApp sets it at launch; the hosted test bundle runs
        // inside the app.
        XCTAssertTrue(UNUserNotificationCenter.current().delegate === ImportNotificationDelegate.shared)
    }
}

private final class Received: @unchecked Sendable {
    private let lock = NSLock()
    private(set) var action: AppleCameraRollImportNotifier.Action?
    private(set) var stagedBefore: Int64 = 0

    func set(_ action: AppleCameraRollImportNotifier.Action, _ stagedBefore: Int64) {
        lock.lock()
        self.action = action
        self.stagedBefore = stagedBefore
        lock.unlock()
    }
}
