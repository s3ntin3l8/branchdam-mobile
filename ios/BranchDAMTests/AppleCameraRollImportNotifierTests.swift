import XCTest
@testable import BranchDAM

final class AppleCameraRollImportNotifierTests: XCTestCase {

    override func setUp() {
        super.setUp()
        AppleCameraRollImportNotifier.shared.clearSuppressed()
        UserDefaults.standard.removeObject(forKey: AppleCameraRollImportNotifier.keyAutoImportEnabled)
    }

    override func tearDown() {
        AppleCameraRollImportNotifier.shared.clearSuppressed()
        UserDefaults.standard.removeObject(forKey: AppleCameraRollImportNotifier.keyAutoImportEnabled)
        super.tearDown()
    }

    func testConstants() {
        XCTAssertEqual(AppleCameraRollImportNotifier.keyAutoImportEnabled, "branchdam_auto_import_camera_roll")
        XCTAssertEqual(AppleCameraRollImportNotifier.categoryIdentifier, "com.branchdam.category.cameraRollImport")
        XCTAssertEqual(AppleCameraRollImportNotifier.actionImportNow, "com.branchdam.action.importNow")
        XCTAssertEqual(AppleCameraRollImportNotifier.actionLater, "com.branchdam.action.later")
        XCTAssertEqual(AppleCameraRollImportNotifier.actionSkip, "com.branchdam.action.skip")
    }

    func testAutoImportPreferencePersistence() {
        let notifier = AppleCameraRollImportNotifier.shared
        XCTAssertFalse(notifier.autoImportEnabled)

        notifier.autoImportEnabled = true
        XCTAssertTrue(notifier.autoImportEnabled)
        XCTAssertTrue(UserDefaults.standard.bool(forKey: AppleCameraRollImportNotifier.keyAutoImportEnabled))

        notifier.autoImportEnabled = false
        XCTAssertFalse(notifier.autoImportEnabled)
    }

    func testAssetSuppression() {
        let notifier = AppleCameraRollImportNotifier.shared
        let assetId = "ED3B7488-86C7-4186-9A16-86F0DC1D0A3D/L0/001"

        XCTAssertFalse(notifier.isAssetSuppressed(identifier: assetId))

        notifier.suppressAsset(identifier: assetId)
        XCTAssertTrue(notifier.isAssetSuppressed(identifier: assetId))

        notifier.clearSuppressed()
        XCTAssertFalse(notifier.isAssetSuppressed(identifier: assetId))
    }

    func testSkipActionSuppressesAssets() {
        let notifier = AppleCameraRollImportNotifier.shared
        let id1 = "asset-1"
        let id2 = "asset-2"

        notifier.handleAction(actionIdentifier: AppleCameraRollImportNotifier.actionSkip, assetIdentifiers: [id1, id2])

        XCTAssertTrue(notifier.isAssetSuppressed(identifier: id1))
        XCTAssertTrue(notifier.isAssetSuppressed(identifier: id2))
        XCTAssertFalse(notifier.isAssetSuppressed(identifier: "asset-3"))
    }
}
