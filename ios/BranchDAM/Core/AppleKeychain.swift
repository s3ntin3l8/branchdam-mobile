import Foundation
import Security

/// Security-framework wrapper for storing the API key (and any other
/// secrets that need to survive between launches without being
/// exposed via UserDefaults / file system backups).
///
/// T2-5 hardening: the API key is stored under
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` so it is
/// available after the first device unlock (background sync, etc.)
/// and is NOT migrated off-device via iCloud Keychain backups or
/// device-to-device migration. The server URL is intentionally NOT
/// stored here — it's not a secret and the QrPairingView keeps it in
/// UserDefaults.
///
/// All public methods are synchronous; the underlying Security
/// framework calls are cheap (microseconds for the simulator, a few
/// hundred microseconds on hardware) so no queue dispatch is needed.
public final class AppleKeychain {
    public static let productionService = "com.branchdam.mobile"
    public static let apiKeyAccount = "branchdam_api_key" // pragma: allowlist secret

    /// The shared instance used by the QR pairing flow and the
    /// BranchDamCoreBridge. Tests construct their own instances with
    /// a unique service name so parallel / repeated runs don't
    /// collide.
    public static let shared = AppleKeychain(service: AppleKeychain.productionService)

    public let service: String

    public init(service: String) {
        self.service = service
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
        addAttributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let addStatus = SecItemAdd(addAttributes as CFDictionary, nil)
        return addStatus == errSecSuccess
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
        guard status == errSecSuccess, let data = raw as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    @discardableResult
    public func deleteString(account: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let status = SecItemDelete(query as CFDictionary)
        // errSecItemNotFound means "nothing to delete" — treat that as
        // success so callers don't have to special-case the empty
        // state.
        return status == errSecSuccess || status == errSecItemNotFound
    }

    /// Removes every item stored under this instance's service. Used
    /// by test teardown to ensure no test data leaks between runs.
    public func deleteAll() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        _ = SecItemDelete(query as CFDictionary)
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
