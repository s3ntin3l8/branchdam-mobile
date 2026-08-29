import Foundation
import Photos

public struct DiscoveredAsset: Equatable {
    public let localIdentifier: String
    public let filename: String
    public let creationDateUnix: Int64
    public let isRaw: Bool?
    public let isVideo: Bool?
    public let pixelWidth: Int
    public let pixelHeight: Int

    public init(
        localIdentifier: String,
        filename: String,
        creationDateUnix: Int64,
        isRaw: Bool = false,
        isVideo: Bool = false,
        pixelWidth: Int = 0,
        pixelHeight: Int = 0
    ) {
        self.localIdentifier = localIdentifier
        self.filename = filename
        self.creationDateUnix = creationDateUnix
        self.isRaw = isRaw
        self.isVideo = isVideo
        self.pixelWidth = pixelWidth
        self.pixelHeight = pixelHeight
    }
}

public class PhotoKitObserver: NSObject, PHPhotoLibraryChangeObserver {
    public static let shared = PhotoKitObserver()

    private var lastScannedDate: Date = Date().addingTimeInterval(-3600)
    private var isObserving = false

    public func startObserving() {
        guard !isObserving else { return }
        PHPhotoLibrary.shared().register(self)
        isObserving = true
    }

    public func stopObserving() {
        guard isObserving else { return }
        PHPhotoLibrary.shared().unregisterChangeObserver(self)
        isObserving = false
    }

    public func photoLibraryDidChange(_ changeInstance: PHChange) {
        // Trigger incremental fetch for newly added photos/videos
        _ = fetchAndEnqueueRecentAssets()
    }

    public func fetchAndEnqueueRecentAssets(minDate: Date? = nil) -> [DiscoveredAsset] {
        let since = minDate ?? lastScannedDate
        let options = PHFetchOptions()
        options.predicate = NSPredicate(format: "creationDate > %@", since as NSDate)
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: true)]

        let assets = PHAsset.fetchAssets(with: options)
        var discovered = [DiscoveredAsset]()

        assets.enumerateObjects { asset, _, _ in
            let resources = PHAssetResource.assetResources(for: asset)
            let primaryResource = resources.first(where: { $0.type == .photo || $0.type == .video || $0.type == .alternatePhoto }) ?? resources.first

            let filename = primaryResource?.originalFilename ?? "IMG_\(asset.localIdentifier.prefix(8)).JPG"
            let creationUnix = Int64(asset.creationDate?.timeIntervalSince1970 ?? 0)
            let isRaw = (asset.mediaSubtypes.rawValue & PHAssetMediaSubtype.photoHDR.rawValue) != 0 || filename.uppercased().hasSuffix(".DNG")
            let isVideo = asset.mediaType == .video

            let item = DiscoveredAsset(
                localIdentifier: asset.localIdentifier,
                filename: filename,
                creationDateUnix: creationUnix,
                isRaw: isRaw,
                isVideo: isVideo,
                pixelWidth: asset.pixelWidth,
                pixelHeight: asset.pixelHeight
            )
            discovered.append(item)

            // Enqueue into core engine bridge
            _ = BranchDamCoreBridge.shared.enqueueMedia(
                localPath: "ph://\(asset.localIdentifier)",
                filename: filename,
                capturedAtUnix: creationUnix,
                localID: asset.localIdentifier
            )
        }

        if let latest = assets.lastObject?.creationDate {
            self.lastScannedDate = latest
        }

        if !discovered.isEmpty {
            BackgroundSyncManager.shared.scheduleBackgroundSync()
        }

        return discovered
    }
}
