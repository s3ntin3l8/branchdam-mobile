import XCTest
#if canImport(BranchDam)
import BranchDam
#endif
@testable import BranchDAM

final class BranchDamCoreBridgeTests: XCTestCase {

    func testBridgeInitialization() {
        let bridge = BranchDamCoreBridge.shared
        let success = bridge.initialize(
            dbPath: NSTemporaryDirectory() + "test_queue_a.db",
            baseURL: "http://localhost:8080",
            apiKey: "test_key", // pragma: allowlist secret
            agentID: "iphone-16-pro"
        )
        XCTAssertTrue(success)
    }

    func testEnqueueMediaMock() {
        let bridge = BranchDamCoreBridge.shared
        _ = bridge.initialize(
            dbPath: NSTemporaryDirectory() + "test_queue_a.db",
            baseURL: "http://localhost:8080",
            apiKey: "test_key", // pragma: allowlist secret
            agentID: "iphone-16-pro"
        )
        let tempFile = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("IMG_0001.DNG")
        try? "test dng content".data(using: .utf8)?.write(to: tempFile)
        defer { try? FileManager.default.removeItem(at: tempFile) }

        let uploadId = bridge.enqueueMedia(
            localPath: tempFile.path,
            filename: "IMG_0001.DNG",
            capturedAtUnix: 1724000000,
            localID: "ph://asset-001"
        )
        XCTAssertGreaterThan(uploadId, 0)
    }

    func testLineageEventMock() {
        let bridge = BranchDamCoreBridge.shared
        let eventUuid = bridge.enqueueLineageEvent(
            parentUUID: "ph://master-001",
            childUUID: "ph://child-001",
            relationshipType: "DERIVED_FROM",
            resolver: "ios_apple_proraw_pair",
            confidence: 1.00
        )
        XCTAssertFalse(eventUuid.isEmpty)
    }

    func testOffloadFlagMock() {
        let bridge = BranchDamCoreBridge.shared
        let setResult = bridge.setMediaOffloaded(localID: "ph://asset-001", isOffloaded: true)
        XCTAssertTrue(setResult)
        let isOffloaded = bridge.isMediaOffloaded(localID: "ph://asset-001")
        XCTAssertTrue(isOffloaded)
    }

    /// Smoke test for the gomobile-bound Engine. Proves the artifact loaded.
    /// Sub-issue A only; B expands the API surface.
    func testEngineVersionLoads() {
        #if canImport(BranchDam)
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
}
