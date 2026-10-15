import Foundation
import Photos

public class AppleTrashSyncObserver {

    /**
     * Processes PhotoKit asset deletions, suppressing remote purge if the asset was an intentional offload.
     */
    public static func processRemovedAsset(
        localId: String,
        nodeUuid: String?,
        isOffloadedHandler: ((_ localId: String) -> Bool)? = nil
    ) -> String? {
        let isOffloaded = isOffloadedHandler?(localId) ?? BranchDamCoreBridge.shared.isMediaOffloaded(localID: localId)
        if isOffloaded {
            // Intentional offload - retain remote Tier 3 master and Immich derivative
            return nil
        }

        guard nodeUuid?.isEmpty == false else {
            // Never uploaded: nothing on the server to delete.
            return nil
        }

        // Emits EVENT_NODE_DELETED, keyed by the local ID; the core resolves
        // it to the node UUID at sync time.
        return BranchDamCoreBridge.shared.enqueueDeleteEvent(localID: localId)
    }
}
