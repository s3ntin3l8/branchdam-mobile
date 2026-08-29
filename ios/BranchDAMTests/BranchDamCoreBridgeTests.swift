import XCTest
@testable import BranchDAM

final class BranchDamCoreBridgeTests: XCTestCase {

    func testBridgeInitialization() {
        let bridge = BranchDamCoreBridge.shared
        let success = bridge.initialize(
            dbPath: "/tmp/test_queue.db",
            baseURL: "http://localhost:8080",
            apiKey: "test_key",
            agentID: "iphone-16-pro"
        )
        XCTAssertTrue(success)
    }

    func testEnqueueMediaMock() {
        let bridge = BranchDamCoreBridge.shared
        let uploadId = bridge.enqueueMedia(
            localPath: "/var/mobile/Media/DCIM/IMG_0001.DNG",
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
    }
}
