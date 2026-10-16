import Foundation
import Photos

public class AppleTrashSyncObserver {

    /**
     * Processes a PhotoKit asset deletion (reported by `PhotoKitObserver`
     * from the persistent change history or the session's PHChange
     * details), suppressing the remote purge if the asset was an
     * intentional offload.
     *
     * The delete event is keyed by the local ID the asset was enqueued for
     * upload under; the core resolves it to the node UUID at sync time and
     * drops it for media that was never uploaded or was reclaimed, so the
     * caller does not need to know whether the asset reached the server.
     * `nodeUuid` is accepted for source compatibility and no longer gates
     * the event.
     */
    @discardableResult
    public static func processRemovedAsset(
        localId: String,
        nodeUuid: String? = nil,
        isOffloadedHandler: ((_ localId: String) -> Bool)? = nil
    ) -> String? {
        let isOffloaded = isOffloadedHandler?(localId) ?? BranchDamCoreBridge.shared.isMediaOffloaded(localID: localId)
        if isOffloaded {
            // Intentional offload - retain remote Tier 3 master and Immich derivative
            return nil
        }

        // Emits EVENT_NODE_DELETED, keyed by the local ID.
        let eventId = BranchDamCoreBridge.shared.enqueueDeleteEvent(localID: localId)
        return eventId.isEmpty ? nil : eventId
    }
}
