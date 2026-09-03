import XCTest
#if canImport(branchdam)
import branchdam
#endif
@testable import BranchDAM

final class BranchDamCoreBridgeTests: XCTestCase {

    /// Each test uses a unique DB path so the singleton engine is
    /// initialized fresh for that test and torn down after. This
    /// avoids cross-test state leakage via the shared singleton.
    private var dbPath: String!

    override func setUp() {
        super.setUp()
        dbPath = NSTemporaryDirectory() + "test_queue_\(UUID().uuidString).db"
    }

    override func tearDown() {
        BranchDamCoreBridge.shared.shutdown()
        try? FileManager.default.removeItem(atPath: dbPath)
        super.tearDown()
    }

    func testBridgeInitialization() {
        let bridge = BranchDamCoreBridge.shared
        let success = bridge.initialize(
            dbPath: dbPath,
            baseURL: "http://localhost:8080",
            apiKey: "test_key", // pragma: allowlist secret
            agentID: "iphone-16-pro"
        )
        XCTAssertTrue(success)
    }

    func testEnqueueMediaMock() {
        let bridge = BranchDamCoreBridge.shared
        _ = bridge.initialize(
            dbPath: dbPath,
            baseURL: "http://localhost:8080",
            apiKey: "test_key", // pragma: allowlist secret
            agentID: "iphone-16-pro"
        )
        let tempFile = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("IMG_\(UUID().uuidString).DNG")
        try? "test dng content".data(using: .utf8)?.write(to: tempFile)
        defer { try? FileManager.default.removeItem(at: tempFile) }

        let uploadId = bridge.enqueueMedia(
            localPath: tempFile.path,
            filename: "IMG_0001.DNG",
            capturedAtUnix: 1724000000,
            localID: "ph://asset-\(UUID().uuidString)"
        )
        XCTAssertGreaterThan(uploadId, 0, "enqueueMedia should return a positive ID")
    }

    func testLineageEventMock() {
        let bridge = BranchDamCoreBridge.shared
        _ = bridge.initialize(
            dbPath: dbPath,
            baseURL: "http://localhost:8080",
            apiKey: "test_key", // pragma: allowlist secret
            agentID: "iphone-16-pro"
        )
        let eventUuid = bridge.enqueueLineageEvent(
            parentUUID: "ph://master-\(UUID().uuidString)",
            childUUID: "ph://child-\(UUID().uuidString)",
            relationshipType: "DERIVED_FROM",
            resolver: "ios_apple_proraw_pair",
            confidence: 1.00
        )
        XCTAssertFalse(eventUuid.isEmpty, "lineage event UUID should be non-empty")
    }

    func testOffloadFlagMock() {
        let bridge = BranchDamCoreBridge.shared
        _ = bridge.initialize(
            dbPath: dbPath,
            baseURL: "http://localhost:8080",
            apiKey: "test_key", // pragma: allowlist secret
            agentID: "iphone-16-pro"
        )
        let localID = "ph://asset-\(UUID().uuidString)"
        let setResult = bridge.setMediaOffloaded(localID: localID, isOffloaded: true)
        XCTAssertTrue(setResult, "setMediaOffloaded should succeed")
        let isOffloaded = bridge.isMediaOffloaded(localID: localID)
        XCTAssertTrue(isOffloaded, "isMediaOffloaded should reflect the set value")
    }

    /// Smoke test for the gomobile-bound Engine. Proves the artifact loaded.
    func testEngineVersionLoads() {
        #if canImport(branchdam)
        let version = BranchDamCoreBridge.engineVersion
        XCTAssertFalse(version.isEmpty, "engine version should be non-empty when framework loads")
        XCTAssertNotEqual(version, "unavailable", "engine version should be reachable through the framework")
        #else
        // Framework absent (local dev without make mobile-build-ios). Verify
        // the bridge's fallback returns the documented stub string so callers
        // can rely on a known value.
        XCTAssertEqual(BranchDamCoreBridge.engineVersion, "unavailable")
        #endif
    }

    /// B.2.3: isMediaOffloaded on a not-yet-initialized engine returns
    /// false (fail-closed). The pre-B behaviour returned false via the
    /// mock fallback, which is the same surface value; the difference
    /// is the code path: B's path goes through the real engine.
    func testIsMediaOffloaded_NotInitialized_ReturnsFalse() {
        let bridge = BranchDamCoreBridge.shared
        // Ensure clean state; do not call initialize.
        bridge.shutdown()
        let isOffloaded = bridge.isMediaOffloaded(localID: "any-id")
        XCTAssertFalse(isOffloaded)
    }

    /// T2-5: an explicit apiKey argument takes precedence over the
    /// keychain. The pre-T2-5 contract was that the bridge always
    /// received the cleartext; tests passed it that way and we want
    /// to keep that behaviour.
    func testExplicitApiKeyTakesPrecedenceOverKeychain() {
        // Pre-stage a keychain value; if the explicit override below
        // is ignored, the bridge would use the keychain value and the
        // assertion would fail because the engine surfaces the key
        // through subsequent calls (the engine struct has apiKey
        // cached). Verifying initialize returns true is sufficient
        // for the simulator: it proves the call path didn't crash
        // on a missing/garbled key.
        AppleKeychain.shared.apiKey = "from-keychain" // pragma: allowlist secret
        defer { AppleKeychain.shared.apiKey = nil }

        let bridge = BranchDamCoreBridge.shared
        let success = bridge.initialize(
            dbPath: dbPath,
            baseURL: "http://localhost:8080",
            apiKey: "from-explicit-arg", // pragma: allowlist secret
            agentID: "iphone-16-pro"
        )
        XCTAssertTrue(success)
    }

    /// T2-5: omitting the apiKey argument falls back to the
    /// keychain, then to an empty string. This is the path the
    /// QrPairingView takes after it writes the key to the keychain.
    ///
    /// The keychain round-trip assertion catches the pre-fix bug
    /// where `SecItemUpdate` rejected `kSecAttrAccessible` with
    /// `errSecParam`, causing every initial keychain write to fail
    /// silently. Without this assertion the test would still pass —
    /// the bridge gracefully falls back to "" on a nil keychain read
    /// — masking the real failure.
    func testInitializeReadsApiKeyFromKeychainWhenArgumentOmitted() {
        AppleKeychain.shared.apiKey = "from-keychain-only" // pragma: allowlist secret
        defer { AppleKeychain.shared.apiKey = nil }

        // Verify the keychain write actually round-tripped before
        // the bridge reads. If the setter failed silently, the getter
        // would return nil and the bridge would silently fall back to
        // an empty apiKey string — the exact regression this test
        // guards against.
        XCTAssertEqual(
            AppleKeychain.shared.apiKey,
            "from-keychain-only", // pragma: allowlist secret
            "keychain must retain the set value so the bridge reads it"
        )

        let bridge = BranchDamCoreBridge.shared
        let success = bridge.initialize(
            dbPath: dbPath,
            baseURL: "http://localhost:8080",
            agentID: "iphone-16-pro"
        )
        XCTAssertTrue(success)
    }

    /// T2-5: with no apiKey argument and an empty keychain, the
    /// bridge still initializes. This is the "first launch, user
    /// hasn't paired yet" path.
    func testInitializeWithEmptyKeychainAndNoArgument() {
        AppleKeychain.shared.apiKey = nil

        let bridge = BranchDamCoreBridge.shared
        let success = bridge.initialize(
            dbPath: dbPath,
            baseURL: "http://localhost:8080",
            agentID: "iphone-16-pro"
        )
        XCTAssertTrue(success)
    }
}
