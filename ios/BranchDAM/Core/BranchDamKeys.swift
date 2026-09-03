import Foundation

/// Canonical preference-key registry for the iOS shell.
///
/// The `branchdam_` prefix matches the Android shell's SharedPreferences
/// keys (see `android/app/src/main/java/com/branchdam/mobile/BranchDamKeys.kt`)
/// so a future migration tool or shared preferences inspector can
/// target the same string on both platforms without having to learn
/// two spellings.
///
/// iOS parity:
///   `BranchDamKeys.syncOnMobileData.rawValue` ==
///   `BranchDamKeys.Android.SYNC_ON_MOBILE_DATA`
///   `BranchDamKeys.autoImportCameraRoll.rawValue` ==
///   `BranchDamKeys.Android.AUTO_IMPORT_CAMERA_ROLL`
///
/// T2-10b hardening: pre-T2-10 iOS keys lacked the `branchdam_` prefix
/// in some callers and were defined as scattered string literals in
/// `BackgroundSyncManager` and `AppleCameraRollImportNotifier`. This
/// file collects the canonical keys so the iOS shell matches the
/// Android canonical keys object added in PR #89.
///
/// The constant names use Swift lowerCamelCase for the public
/// platform-facing API; the `rawValue` is the snake_case string that
/// must remain byte-identical to the Android value (any divergence
/// will surface in the `BranchDamKeysTests` suite).
public enum BranchDamKeys: String {
    /// Sync scheduler: whether to use mobile (cellular) data for
    /// one-off sync requests. Read by `BackgroundSyncManager` when
    /// configuring the background URL session's `allowsCellularAccess`.
    case syncOnMobileData = "branchdam_sync_on_mobile_data"

    /// Camera roll import: whether to auto-enqueue newly-detected
    /// photos for upload. Read by `AppleCameraRollImportNotifier`
    /// when deciding whether to show a confirmation notification.
    case autoImportCameraRoll = "branchdam_auto_import_camera_roll"

    /// Keychain service identifier for production storage of the
    /// branchDAM API key. The companion keychain account identifier
    /// lives at `apiKeyAccount`.
    case keychainService = "com.branchdam.mobile"

    /// Keychain account identifier under which the branchDAM API key
    /// is stored. The companion service identifier lives at
    /// `keychainService`.
    case apiKeyAccount = "branchdam_api_key" // pragma: allowlist secret
}

extension BranchDamKeys {
    /// Mirror of the Android `BranchDamKeys` string constants, kept
    /// here so the cross-platform assertion test can compare the two
    /// surfaces side-by-side without round-tripping through JSON.
    public enum Android {
        public static let SYNC_ON_MOBILE_DATA = "branchdam_sync_on_mobile_data"
        public static let AUTO_IMPORT_CAMERA_ROLL = "branchdam_auto_import_camera_roll"
    }
}
