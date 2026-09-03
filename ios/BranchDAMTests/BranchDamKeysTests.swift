import XCTest
@testable import BranchDAM

/// Tests for [BranchDamKeys] — the iOS-side canonical preference-key
/// registry that mirrors the Android `BranchDamKeys` object added in
/// PR #89. The point of these tests is to **lock the rawValues** so
/// any future rename surfaces here, and to assert byte-level parity
/// with the Android side so the cross-platform guarantee is
/// enforceable from a single CI run.
final class BranchDamKeysTests: XCTestCase {

    func testRawValuesMatchTheCanonicalStrings() {
        XCTAssertEqual(
            BranchDamKeys.syncOnMobileData.rawValue,
            "branchdam_sync_on_mobile_data",
            "syncOnMobileData rawValue must remain the spec'd 'branchdam_sync_on_mobile_data' — any rename will break shared preferences migrations"
        )
        XCTAssertEqual(
            BranchDamKeys.autoImportCameraRoll.rawValue,
            "branchdam_auto_import_camera_roll",
            "autoImportCameraRoll rawValue must remain the spec'd 'branchdam_auto_import_camera_roll'"
        )
        XCTAssertEqual(
            BranchDamKeys.keychainService.rawValue,
            "com.branchdam.mobile",
            "keychainService rawValue must remain the spec'd 'com.branchdam.mobile' so AppleKeychain's shared instance uses the production service"
        )
        XCTAssertEqual(
            BranchDamKeys.apiKeyAccount.rawValue,
            "branchdam_api_key",
            "apiKeyAccount rawValue must remain 'branchdam_api_key' so previously-stored API keys are still findable after the upgrade"
        )
    }

    func testIosRawValuesMatchAndroidMirror() {
        // Internal-parity guard. The iOS `BranchDamKeys` rawValues and
        // the `BranchDamKeys.Android` mirror constants are both defined
        // in the same Swift file, so this test only catches a divergence
        // *between the two sides of this file* — not a divergence with
        // the actual Android source tree. The real cross-platform
        // guarantee is a CI-level check that diffs this file against
        // android/app/src/main/java/com/branchdam/mobile/BranchDamKeys.kt;
        // a future PR could add that as a pre-commit hook.
        XCTAssertEqual(
            BranchDamKeys.syncOnMobileData.rawValue,
            BranchDamKeys.Android.SYNC_ON_MOBILE_DATA
        )
        XCTAssertEqual(
            BranchDamKeys.autoImportCameraRoll.rawValue,
            BranchDamKeys.Android.AUTO_IMPORT_CAMERA_ROLL
        )
    }

    override func tearDown() {
        // Reset the BackgroundSyncManager singleton state this suite
        // mutates, so subsequent tests / repeated runs see the same
        // `syncOnMobileData == false` they started with. Also wipes the
        // canonical UserDefaults key the round-trip test writes through
        // so it doesn't leak between tests.
        BackgroundSyncManager.shared.syncOnMobileData = false
        UserDefaults.standard.removeObject(forKey: BranchDamKeys.syncOnMobileData.rawValue)
        super.tearDown()
    }

    func testUserDefaultsRoundTripsThroughCanonicalKey() {
        // The accepted cross-platform canonical UserDefaults key is
        // the one the BackgroundSyncManager / AppleCameraRollImportNotifier
        // code paths now read and write through. This test asserts
        // that the literal branchdam_-prefixed string is the active
        // key, not a legacy un-prefixed spelling.
        UserDefaults.standard.removeObject(forKey: BranchDamKeys.syncOnMobileData.rawValue)

        let manager = BackgroundSyncManager.shared
        manager.syncOnMobileData = true
        XCTAssertTrue(
            UserDefaults.standard.bool(forKey: BranchDamKeys.syncOnMobileData.rawValue),
            "BackgroundSyncManager must persist through the canonical branchdam_sync_on_mobile_data key"
        )
        manager.syncOnMobileData = false
        XCTAssertFalse(
            UserDefaults.standard.bool(forKey: BranchDamKeys.syncOnMobileData.rawValue)
        )
        UserDefaults.standard.removeObject(forKey: BranchDamKeys.syncOnMobileData.rawValue)
    }

    func testBackwardsCompatibleAliasesResolveToCanonicalValues() {
        // External callers (and pre-T2-10b callers inside the module)
        // may still reference BackgroundSyncManager.keySyncOnMobileData
        // and AppleCameraRollImportNotifier.keyAutoImportEnabled. Both
        // are now computed properties that resolve to the same
        // canonical BranchDamKeys value, so a "rename" can never
        // happen silently behind the alias.
        XCTAssertEqual(
            BackgroundSyncManager.keySyncOnMobileData,
            BranchDamKeys.syncOnMobileData.rawValue
        )
        XCTAssertEqual(
            AppleCameraRollImportNotifier.keyAutoImportEnabled,
            BranchDamKeys.autoImportCameraRoll.rawValue
        )
    }
}
