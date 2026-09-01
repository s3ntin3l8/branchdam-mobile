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
}
