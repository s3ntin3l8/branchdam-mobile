import Foundation
import Photos

public class AppleTrashSyncObserver {

    /**
     * Processes PhotoKit asset deletions, suppressing remote purge if the asset was an intentional offload.
     */
    public static func processRemovedAsset(localId: String, nodeUuid: String?) -> String? {
        let isOffloaded = BranchDamCoreBridge.shared.isMediaOffloaded(localID: localId)
        if isOffloaded {
            // Intentional offload - retain remote Tier 3 master and Immich derivative
            return nil
        }

        guard let uuid = nodeUuid, !uuid.isEmpty else {
            return nil
        }

        // Emits EVENT_NODE_DELETED
        return BranchDamCoreBridge.shared.enqueueDeleteEvent(nodeUUID: uuid)
    }
}
