import Foundation
import Photos

public struct AppleSafeSpaceReport: Equatable {
    public let totalCandidates: Int
    public let verifiedCount: Int
    public let reclaimedCount: Int
    public let estimatedBytesFreed: Int64

    public init(totalCandidates: Int, verifiedCount: Int, reclaimedCount: Int, estimatedBytesFreed: Int64) {
        self.totalCandidates = totalCandidates
        self.verifiedCount = verifiedCount
        self.reclaimedCount = reclaimedCount
        self.estimatedBytesFreed = estimatedBytesFreed
    }
}

public class AppleSafeSpaceManager {

    /**
     * Executes safe space reclaim on iOS:
     * 1. Confirms node verification on Tier 3 NAS.
     * 2. Flags is_offloaded = 1 in SQLite queue.db.
     * 3. Deletes local full-res asset copy via PHPhotoLibrary or deletionHandler.
     */
    public static func reclaimSafeSpace(
        candidates: [(localId: String, sizeBytes: Int64, isVerified: Bool)],
        deletionHandler: ((_ localId: String) -> Bool)? = nil
    ) -> AppleSafeSpaceReport {
        var verifiedCount = 0
        var reclaimedCount = 0
        var bytesFreed: Int64 = 0

        for candidate in candidates {
            guard candidate.isVerified else { continue }
            verifiedCount += 1

            let deleted: Bool
            if let customDelete = deletionHandler {
                deleted = customDelete(candidate.localId)
            } else {
                deleted = deleteLocalAsset(localIdentifier: candidate.localId)
            }

            if deleted {
                _ = BranchDamCoreBridge.shared.setMediaOffloaded(localID: candidate.localId, isOffloaded: true)
                reclaimedCount += 1
                bytesFreed += candidate.sizeBytes
            }
        }

        return AppleSafeSpaceReport(
            totalCandidates: candidates.count,
            verifiedCount: verifiedCount,
            reclaimedCount: reclaimedCount,
            estimatedBytesFreed: bytesFreed
        )
    }

    private static func deleteLocalAsset(localIdentifier: String) -> Bool {
        let cleanId = localIdentifier.hasPrefix("ph://") ? String(localIdentifier.dropFirst(5)) : localIdentifier
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: [cleanId], options: nil)
        guard assets.count > 0 else { return true }
        var success = true
        do {
            try PHPhotoLibrary.shared().performChangesAndWait {
                PHAssetChangeRequest.deleteAssets(assets)
            }
        } catch {
            success = false
        }
        return success
    }
}
