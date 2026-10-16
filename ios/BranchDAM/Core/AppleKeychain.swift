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
public final class AppleKeychain: @unchecked Sendable {
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
    nonisolated(unsafe) public static let defaultAccessibility: CFString = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

    /// The shared instance used by the QR pairing flow and the
    /// BranchDamCoreBridge. Tests construct their own instances with
    /// a unique service name so parallel / repeated runs don't
    /// collide.
    public static let shared = AppleKeychain(service: BranchDamKeys.keychainService.rawValue)

    public let service: String
    public let accessibility: CFString

    #if DEBUG || targetEnvironment(simulator)
    /// In-memory stand-in for the keychain, compiled only into debug and
    /// simulator builds: unsigned simulator test runs
    /// (CODE_SIGNING_ALLOWED=NO) have no keychain entitlement, so
    /// SecItemAdd fails there. A release build on a device never has it,
    /// so a failed write is reported as a failure instead of being kept
    /// in memory and lost at the next launch.
    private static let lock = NSLock()
    nonisolated(unsafe) private static var fallbackStore: [String: [String: String]] = [:]
    #endif

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

        #if DEBUG || targetEnvironment(simulator)
        Self.lock.lock()
        defer { Self.lock.unlock() }

        if addStatus == errSecSuccess {
            Self.fallbackStore[service]?.removeValue(forKey: account)
            return true
        }
        // Unsigned simulator test environments (CODE_SIGNING_ALLOWED=NO)
        // reject SecItemAdd for lack of entitlements. Keep the value in
        // memory so test suites can exercise the full stack.
        NSLog("%@", "AppleKeychain: SecItemAdd failed (\(addStatus)); using the debug in-memory store")
        if Self.fallbackStore[service] == nil {
            Self.fallbackStore[service] = [:]
        }
        Self.fallbackStore[service]?[account] = value
        return true
        #else
        if addStatus != errSecSuccess {
            NSLog("%@", "AppleKeychain: SecItemAdd failed (\(addStatus))")
        }
        return addStatus == errSecSuccess
        #endif
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

        #if DEBUG || targetEnvironment(simulator)
        Self.lock.lock()
        defer { Self.lock.unlock() }
        return Self.fallbackStore[service]?[account]
        #else
        return nil
        #endif
    }

    @discardableResult
    public func deleteString(account: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let status = SecItemDelete(query as CFDictionary)

        #if DEBUG || targetEnvironment(simulator)
        Self.lock.lock()
        defer { Self.lock.unlock() }
        Self.fallbackStore[service]?.removeValue(forKey: account)
        // Without a keychain entitlement the in-memory store is the
        // store, and it no longer holds the value.
        _ = status
        return true
        #else
        // errSecItemNotFound means "nothing to delete" — treat that as
        // success so callers don't have to special-case the empty
        // state.
        if status == errSecSuccess || status == errSecItemNotFound {
            return true
        }
        NSLog("%@", "AppleKeychain: SecItemDelete failed (\(status))")
        return false
        #endif
    }

    /// Removes every item stored under this instance's service. Used
    /// by test teardown to ensure no test data leaks between runs.
    public func deleteAll() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        _ = SecItemDelete(query as CFDictionary)

        #if DEBUG || targetEnvironment(simulator)
        Self.lock.lock()
        defer { Self.lock.unlock() }
        Self.fallbackStore.removeValue(forKey: service)
        #endif
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

// MARK: - Pairing credentials

/// The string-secret surface [PairingCredentialStore] needs. [AppleKeychain]
/// is the production implementation; tests substitute an in-memory one.
public protocol SecretStore: AnyObject {
    @discardableResult
    func setString(_ value: String, account: String) -> Bool
    func getString(account: String) -> String?
    @discardableResult
    func deleteString(account: String) -> Bool
}

extension AppleKeychain: SecretStore {}

public enum PairingStoreError: Error, Equatable {
    case apiKeyNotStored
    case agentIdNotStored

    public var message: String {
        switch self {
        case .apiKeyNotStored:
            return String(localized: "Couldn't save the API key to the Keychain. The connection was not changed.")
        case .agentIdNotStored:
            return String(localized: "Couldn't save the agent ID to the Keychain. The connection was not changed.")
        }
    }
}

/// Persists what pairing produced -- the API key and the agent ID the
/// server bound it to -- so every launch reopens the engine as the same
/// agent. Without the agent ID, a relaunch fell back to a default ID the
/// server does not know, and every request was rejected.
public struct PairingCredentialStore {
    public static var apiKeyAccount: String { BranchDamKeys.apiKeyAccount.rawValue }
    public static var agentIdAccount: String { BranchDamKeys.agentIdAccount.rawValue }

    private let store: SecretStore

    public init(store: SecretStore) {
        self.store = store
    }

    /// Stores the API key (an empty key clears it, so a user can rotate it
    /// without re-pairing) and the agent ID. Fails when the Keychain
    /// refuses either write, so the caller can tell the user instead of
    /// connecting with credentials that will be gone at the next launch.
    public func save(apiKey: String, agentId: String) throws {
        let keySaved = apiKey.isEmpty
            ? store.deleteString(account: Self.apiKeyAccount)
            : store.setString(apiKey, account: Self.apiKeyAccount)
        guard keySaved else { throw PairingStoreError.apiKeyNotStored }
        guard store.setString(agentId, account: Self.agentIdAccount) else {
            throw PairingStoreError.agentIdNotStored
        }
    }

    /// The agent ID stored at pairing, if any.
    public var agentId: String? {
        guard let stored = store.getString(account: Self.agentIdAccount), !stored.isEmpty else { return nil }
        return stored
    }

    /// The agent ID to open the engine with.
    public func resolvedAgentID() -> String {
        agentId ?? BranchDamCoreBridge.defaultAgentID
    }
}
