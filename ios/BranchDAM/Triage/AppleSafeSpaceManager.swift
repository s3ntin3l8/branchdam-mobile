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

    /// Executes safe space reclaim on iOS.
    ///
    /// For each candidate the engine re-queries the server for current
    /// verified + tier state and sets the offloaded flag atomically
    /// (B.2.7). The shell only deletes the local file after the engine
    /// confirms eligibility. If the local delete fails the offloaded
    /// flag is rolled back so the asset remains a future reclaim
    /// candidate.
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

            let verdict = BranchDamCoreBridge.shared.reclaimSafeSpace(localID: candidate.localId)
            guard verdict.eligible else { continue }

            let deleted: Bool
            if let customDelete = deletionHandler {
                deleted = customDelete(candidate.localId)
            } else {
                deleted = deleteLocalAsset(localIdentifier: candidate.localId)
            }

            if deleted {
                reclaimedCount += 1
                bytesFreed += candidate.sizeBytes
            } else {
                _ = BranchDamCoreBridge.shared.setMediaOffloaded(localID: candidate.localId, isOffloaded: false)
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
