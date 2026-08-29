import XCTest
@testable import BranchDAM

final class AppleTrashSyncObserverTests: XCTestCase {

    override func setUp() {
        super.setUp()
        _ = BranchDamCoreBridge.shared.initialize(
            dbPath: NSTemporaryDirectory() + "trash_sync_test.db",
            baseURL: "http://localhost:8080"
        )
    }

    func testOffloadedDeletionSuppression() {
        let offloadedId = "ph://asset-offloaded-1"
        _ = BranchDamCoreBridge.shared.setMediaOffloaded(localID: offloadedId, isOffloaded: true)

        let event = AppleTrashSyncObserver.processRemovedAsset(localId: offloadedId, nodeUuid: "node-uuid-1")
        // Offloaded items should suppress delete event dispatch
        XCTAssertNil(event)
    }
}
