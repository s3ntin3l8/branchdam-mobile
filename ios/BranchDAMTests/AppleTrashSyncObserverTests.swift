import XCTest
@testable import BranchDAM

final class AppleTrashSyncObserverTests: XCTestCase {

    func testOffloadedDeletionSuppression() {
        let offloadedId = "ph://asset-offloaded-1"
        _ = BranchDamCoreBridge.shared.setMediaOffloaded(localID: offloadedId, isOffloaded: true)

        let event = AppleTrashSyncObserver.processRemovedAsset(localId: offloadedId, nodeUuid: "node-uuid-1")
        // Offloaded items should suppress delete event dispatch
        XCTAssertNil(event)
    }
}
