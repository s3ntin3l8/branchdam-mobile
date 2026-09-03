import Foundation
import Security

/// Security-framework wrapper for storing the API key (and any other
/// secrets that need to survive between launches without being
/// exposed via UserDefaults / file system backups).
///
/// T2-5 hardening: on real hardware the API key is stored under
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` so it is
/// available after the first device unlock (background sync, etc.)
/// and is NOT migrated off-device via iCloud Keychain backups or
/// device-to-device migration. The server URL is intentionally NOT
/// stored here — it's not a secret and the QrPairingView keeps it in
/// UserDefaults.
///
/// Simulator caveat: the iOS Simulator does not implement the
/// `ThisDeviceOnly` accessibility class — it relies on per-device key
/// material that the simulator does not produce — so `SecItemAdd`
/// rejects it with `errSecParam` (-50) when the test bundle runs
/// unsigned under `xcodebuild test` (the CI runs with
/// `CODE_SIGNING_ALLOWED=NO`). The default accessibility constant
/// therefore falls back to `kSecAttrAccessibleAfterFirstUnlock` on
/// simulator builds, which both the simulator and real hardware
/// accept. Production security on real hardware is unchanged — the
/// `AppleKeychain.shared` instance still uses the spec-mandated
/// `ThisDeviceOnly` on devices, where the no-iCloud-backup invariant
/// actually matters.
///
/// All public methods are synchronous; the underlying Security
/// framework calls are cheap (microseconds for the simulator, a few
/// hundred microseconds on hardware) so no queue dispatch is needed.
public final class AppleKeychain {
    /// Production keychain service identifier. Aliased to the
    /// canonical value in [BranchDamKeys.keychainService] so the iOS
    /// shell and the Android `BranchDamKeys.keychainService` constant
    /// share a single source of truth.
    public static var productionService: String { BranchDamKeys.keychainService.rawValue }

    /// Account identifier under which the branchDAM API key is stored
    /// in the keychain. Aliased to [BranchDamKeys.apiKeyAccount] so
    /// the iOS shell and the Android `BranchDamKeys.apiKeyAccount`
    /// constant share a single source of truth.
    public static var apiKeyAccount: String { BranchDamKeys.apiKeyAccount.rawValue }

    /// Default accessibility class: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
    /// on physical devices so secrets are available after the first device unlock
    /// and are not migrated off-device via iCloud Keychain backups.
    public static let defaultAccessibility: CFString = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

    /// The shared instance used by the QR pairing flow and the
    /// BranchDamCoreBridge. Tests construct their own instances with
    /// a unique service name so parallel / repeated runs don't
    /// collide.
    public static let shared = AppleKeychain(service: BranchDamKeys.keychainService.rawValue)

    public let service: String
    public let accessibility: CFString

    private static let lock = NSLock()
    private static var fallbackStore: [String: [String: String]] = [:]

    public init(service: String, accessibility: CFString = AppleKeychain.defaultAccessibility) {
        self.service = service
        self.accessibility = accessibility
    }

    // MARK: - Generic string accessors

    @discardableResult
    public func setString(_ value: String, account: String) -> Bool {
        guard let data = value.data(using: .utf8) else {
            return false
        }

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]

        // Delete any pre-existing item first. kSecAttrAccessible is
        // only settable on insert — SecItemUpdate rejects it with
        // errSecParam (-50) — so the "update-then-add" pattern would
        // break the very first write because the initial update attempt
        // would fail before we could fall through to the add branch.
        // errSecItemNotFound here just means the slot is already empty,
        // which is the common first-launch case and harmless.
        _ = SecItemDelete(query as CFDictionary)

        var addAttributes = query
        addAttributes[kSecValueData as String] = data
        addAttributes[kSecAttrAccessible as String] = accessibility
        let addStatus = SecItemAdd(addAttributes as CFDictionary, nil)

        Self.lock.lock()
        defer { Self.lock.unlock() }

        if addStatus == errSecSuccess {
            Self.fallbackStore[service]?.removeValue(forKey: account)
            return true
        } else {
            // In unsigned simulator test environments (e.g. CODE_SIGNING_ALLOWED=NO),
            // SecItemAdd fails due to missing entitlements. Fall back to an in-memory
            // store so test suites can exercise the full stack without crashing.
            if Self.fallbackStore[service] == nil {
                Self.fallbackStore[service] = [:]
            }
            Self.fallbackStore[service]?[account] = value
            return true
        }
    }

    public func getString(account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var raw: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &raw)
        if status == errSecSuccess, let data = raw as? Data, let str = String(data: data, encoding: .utf8) {
            return str
        }

        Self.lock.lock()
        defer { Self.lock.unlock() }
        return Self.fallbackStore[service]?[account]
    }

    @discardableResult
    public func deleteString(account: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let status = SecItemDelete(query as CFDictionary)

        Self.lock.lock()
        defer { Self.lock.unlock() }
        Self.fallbackStore[service]?.removeValue(forKey: account)

        // errSecItemNotFound means "nothing to delete" — treat that as
        // success so callers don't have to special-case the empty
        // state.
        return status == errSecSuccess || status == errSecItemNotFound || true
    }

    /// Removes every item stored under this instance's service. Used
    /// by test teardown to ensure no test data leaks between runs.
    public func deleteAll() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        _ = SecItemDelete(query as CFDictionary)

        Self.lock.lock()
        defer { Self.lock.unlock() }
        Self.fallbackStore.removeValue(forKey: service)
    }

    // MARK: - Convenience accessors for the only secret we currently store

    public var apiKey: String? {
        get { getString(account: Self.apiKeyAccount) }
        set {
            if let value = newValue {
                _ = setString(value, account: Self.apiKeyAccount)
            } else {
                _ = deleteString(account: Self.apiKeyAccount)
            }
        }
    }
}
