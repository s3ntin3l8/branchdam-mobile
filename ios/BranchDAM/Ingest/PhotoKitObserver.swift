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
    private let lineageQueue = DispatchQueue(label: "com.branchdam.mobile.lineage", qos: .utility)

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
            if AppleCameraRollImportNotifier.shared.isAssetSuppressed(identifier: asset.localIdentifier) {
                return
            }

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
        }

        if let latest = assets.lastObject?.creationDate {
            self.lastScannedDate = latest
        }

        if !discovered.isEmpty {
            if AppleCameraRollImportNotifier.shared.autoImportEnabled {
                for item in discovered {
                    _ = BranchDamCoreBridge.shared.enqueueMedia(
                        localPath: "ph://\(item.localIdentifier)",
                        filename: item.filename,
                        capturedAtUnix: item.creationDateUnix,
                        localID: item.localIdentifier
                    )
                }

                // E.6: Lineage pipeline runs for BOTH auto-import and
                // confirmation-based import. Recording edges before
                // confirmation is safe — the engine deduplicates by local ID.
                runLineageDetection(discovered)

                BackgroundSyncManager.shared.triggerImmediateSync()
            } else {
                AppleCameraRollImportNotifier.shared.stagePendingAssets(discovered)
                AppleCameraRollImportNotifier.shared.postImportNotification(
                    count: discovered.count,
                    assetIdentifiers: discovered.map { $0.localIdentifier }
                )

                // E.6: Lineage detection even for confirmation-based import.
                runLineageDetection(discovered)
            }
        }

        return discovered
    }

    private func runLineageDetection(_ assets: [DiscoveredAsset]) {
        lineageQueue.async {
            // ProRAW pair detection (DNG + HEIC/JPEG companions).
            let raws = assets.filter { $0.isRaw == true }
            let jpegs = assets.filter { ($0.isRaw == false) && ($0.isVideo == false) }
            if !raws.isEmpty && !jpegs.isEmpty {
                let pairs = ApplePairDetector.findProRawPairs(
                    masters: raws.map { (id: "ph://\($0.localIdentifier)", filename: $0.filename, dateUnix: $0.creationDateUnix) },
                    derivatives: jpegs.map { (id: "ph://\($0.localIdentifier)", filename: $0.filename, dateUnix: $0.creationDateUnix) }
                )
                _ = ApplePairDetector.registerPairs(pairs: pairs)
            }

            // Edit correlation (in-phone editor exports -> camera roll master).
            let editDerivatives = assets.filter { item in
                item.filename.contains("Edited", ignoreCase: true) ||
                    item.filename.contains("Restored", ignoreCase: true)
            }
            if !editDerivatives.isEmpty {
                let edits = AppleEditCorrelator.findAppEdits(
                    masters: assets.map { (id: "ph://\($0.localIdentifier)", filename: $0.filename) },
                    derivatives: editDerivatives.map { (id: "ph://\($0.localIdentifier)", filename: $0.filename, app: "ios_editor") }
                )
                _ = AppleEditCorrelator.registerEditLineage(edits: edits)
            }

            // Live Photo detection (still + motion video pair).
            let livePhotoAssets = assets.filter { ($0.isVideo != true) && $0.pixelWidth > 0 }
            for item in livePhotoAssets {
                let results = PHAsset.fetchAssets(withLocalIdentifiers: [item.localIdentifier], options: nil)
                guard let asset = results.firstObject else { continue }

                // Check PHAssetResource for video component (Live Photo).
                let resources = PHAssetResource.assetResources(for: asset)
                let hasVideoComponent = resources.contains { resource in
                    let rawValue = resource.type.rawValue
                    return rawValue == "public.paired-video" || rawValue == "public.alternate-video"
                }
                if hasVideoComponent {
                    let stillId = "ph://\(item.localIdentifier)"
                    let videoId = stillId // Same asset contains both
                    _ = LivePhotoExtractor.linkLivePhoto(
                        stillId: stillId,
                        videoId: videoId,
                        stillFilename: item.filename,
                        videoFilename: item.filename
                    )
                }
            }
        }
    }
}
