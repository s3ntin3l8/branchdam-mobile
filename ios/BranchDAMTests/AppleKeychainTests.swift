import XCTest
import Security
@testable import BranchDAM

/// Tests for [AppleKeychain] — the Security-framework wrapper that
/// stores the API key (and only the API key) in the iOS keychain.
///
/// Each test uses a unique service name so parallel test runs and
/// repeated runs don't see stale entries. The AppleKeychain singleton
/// uses a fixed service name, so the tests exercise an AppleKeychain
/// instance constructed with a custom service rather than the shared
/// one — except for the round-trip test that explicitly cleans up.
final class AppleKeychainTests: XCTestCase {

    private var testService: String!
    private var keychain: AppleKeychain!

    override func setUp() {
        super.setUp()
        testService = "com.branchdam.mobile.tests.\(UUID().uuidString)"
        keychain = AppleKeychain(service: testService)
    }

    override func tearDown() {
        // Remove any items we may have left in the keychain under our
        // test service. Without this, repeated runs accumulate entries.
        keychain.deleteAll()
        keychain = nil
        testService = nil
        super.tearDown()
    }

    // MARK: - String round-trip

    func testSetAndGetApiKeyRoundTrip() {
        let testKey = "test-secret-key-\(UUID().uuidString)" // pragma: allowlist secret
        XCTAssertTrue(keychain.setString(testKey, account: AppleKeychain.apiKeyAccount))
        XCTAssertEqual(keychain.getString(account: AppleKeychain.apiKeyAccount), testKey)
    }

    func testGetReturnsNilWhenNotSet() {
        // Fresh service, nothing written — getString must return nil
        // so the bridge can fall back to its "no api key" path.
        XCTAssertNil(keychain.getString(account: AppleKeychain.apiKeyAccount))
    }

    func testSetOverwritesExistingValue() {
        let first = "first-key" // pragma: allowlist secret
        let second = "second-key" // pragma: allowlist secret
        XCTAssertTrue(keychain.setString(first, account: AppleKeychain.apiKeyAccount))
        XCTAssertEqual(keychain.getString(account: AppleKeychain.apiKeyAccount), first)
        XCTAssertTrue(keychain.setString(second, account: AppleKeychain.apiKeyAccount))
        XCTAssertEqual(keychain.getString(account: AppleKeychain.apiKeyAccount), second)
    }

    func testDeleteRemovesValue() {
        let testKey = "delete-me" // pragma: allowlist secret
        XCTAssertTrue(keychain.setString(testKey, account: AppleKeychain.apiKeyAccount))
        XCTAssertEqual(keychain.getString(account: AppleKeychain.apiKeyAccount), testKey)
        XCTAssertTrue(keychain.deleteString(account: AppleKeychain.apiKeyAccount))
        XCTAssertNil(keychain.getString(account: AppleKeychain.apiKeyAccount))
    }

    func testDeleteReturnsTrueEvenWhenNothingToDelete() {
        // Deleting a non-existent item should not surface an error
        // to callers — the bridge treats "no key configured" as the
        // success condition, not as a failure.
        XCTAssertTrue(keychain.deleteString(account: AppleKeychain.apiKeyAccount))
    }

    // MARK: - apiKey convenience accessors

    func testApiKeyConvenienceGetterSetter() {
        let testKey = "convenience-key" // pragma: allowlist secret
        keychain.apiKey = testKey
        XCTAssertEqual(keychain.apiKey, testKey)
    }

    func testApiKeyConvenienceSetterNilDeletes() {
        let testKey = "will-be-deleted" // pragma: allowlist secret
        keychain.apiKey = testKey
        XCTAssertEqual(keychain.apiKey, testKey)
        keychain.apiKey = nil
        XCTAssertNil(keychain.apiKey)
    }

    func testApiKeyConvenienceGetterReturnsNilWhenEmpty() {
        XCTAssertNil(keychain.apiKey)
    }

    // MARK: - Isolation: shared service vs. test service

    func testSharedInstanceUsesProductionService() {
        // The shared instance must use the production service name
        // (com.branchdam.mobile) so the QR pairing flow's writes are
        // visible to the bridge's reads. If the service is wrong, the
        // pairing handshake breaks silently.
        XCTAssertEqual(AppleKeychain.shared.service, AppleKeychain.productionService)
        XCTAssertNotEqual(AppleKeychain.shared.service, testService)
    }

    // MARK: - Accessibility attribute

    func testItemsAreStoredAfterFirstUnlockThisDeviceOnly() {
        // The T2-5 spec calls for kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        // so the API key is available after first unlock and is NOT
        // migrated off-device via encrypted backups. Verify by
        // reading back the raw item attributes.
        let testKey = "accessibility-test" // pragma: allowlist secret
        keychain.setString(testKey, account: AppleKeychain.apiKeyAccount)

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: testService as String,
            kSecAttrAccount as String: AppleKeychain.apiKeyAccount,
            kSecReturnAttributes as String: true,
            kSecReturnData as String: false,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var raw: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &raw)
        XCTAssertEqual(status, errSecSuccess, "expected to find the stored keychain item")
        guard let attrs = raw as? [String: Any] else {
            XCTFail("keychain query did not return attributes")
            return
        }
        XCTAssertEqual(attrs[kSecAttrAccessible as String] as? String,
                       kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
    }
}
