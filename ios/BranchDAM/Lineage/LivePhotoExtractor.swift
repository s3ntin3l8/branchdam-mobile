import Foundation

public struct LivePhotoPair: Equatable {
    public let stillAssetId: String
    public let videoAssetId: String
    public let stillFilename: String
    public let videoFilename: String

    public init(stillAssetId: String, videoAssetId: String, stillFilename: String, videoFilename: String) {
        self.stillAssetId = stillAssetId
        self.videoAssetId = videoAssetId
        self.stillFilename = stillFilename
        self.videoFilename = videoFilename
    }
}

public class LivePhotoExtractor {

    /**
     * Resolves companion paired video (.MOV) for Live Photos and generates lineage link.
     */
    public static func linkLivePhoto(stillId: String, videoId: String, stillFilename: String, videoFilename: String) -> String {
        return BranchDamCoreBridge.shared.enqueueLineageEvent(
            parentUUID: stillId,
            childUUID: videoId,
            relationshipType: "DERIVED_FROM",
            resolver: "ios_apple_live_photo_video",
            confidence: 1.00
        )
    }
}
