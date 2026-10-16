import Foundation
import Photos

public struct DiscoveredAsset: Equatable, Sendable {
    public let localIdentifier: String
    public let filename: String
    public let creationDateUnix: Int64
    public let isRaw: Bool?
    public let isVideo: Bool?
    public let isLivePhoto: Bool?
    public let pixelWidth: Int
    public let pixelHeight: Int
    /// The exact PhotoKit creation date, when known (the scan cursor
    /// compares against it; `creationDateUnix` drops sub-second precision).
    public let creationDate: Date?

    public init(
        localIdentifier: String,
        filename: String,
        creationDateUnix: Int64,
        isRaw: Bool = false,
        isVideo: Bool = false,
        isLivePhoto: Bool = false,
        pixelWidth: Int = 0,
        pixelHeight: Int = 0,
        creationDate: Date? = nil
    ) {
        self.localIdentifier = localIdentifier
        self.filename = filename
        self.creationDateUnix = creationDateUnix
        self.isRaw = isRaw
        self.isVideo = isVideo
        self.isLivePhoto = isLivePhoto
        self.pixelWidth = pixelWidth
        self.pixelHeight = pixelHeight
        self.creationDate = creationDate
    }
}

public class PhotoKitObserver: NSObject, PHPhotoLibraryChangeObserver, @unchecked Sendable {
    public static let shared = PhotoKitObserver()

    /// The persisted scan position (change token, else a creation-date
    /// cursor). It used to be an in-memory date reset to "one hour ago"
    /// on every launch.
    private let cursor: CameraRollCursorStore
    private let notifier: AppleCameraRollImportNotifier
    @objc private dynamic var isObserving = false
    private let lineageQueue = DispatchQueue(label: "com.branchdam.mobile.lineage", qos: .utility)

    /// Serialises scans: PhotoKit delivers change callbacks on arbitrary
    /// queues, and two overlapping scans would race on the cursor.
    private let scanQueue = DispatchQueue(label: "com.branchdam.mobile.photokit-scan", qos: .utility)

    /// Every asset, kept current from each PHChange so assets removed
    /// during this session can be read from `changeDetails(for:)` when
    /// the persistent change history is unavailable. scanQueue only.
    private var allAssets: PHFetchResult<PHAsset>?
    /// The engine's upload statuses by local ID, read once per scan, or
    /// nil when that read failed (persistIntent then asks per asset);
    /// scanQueue only.
    private var queuedStatuses: [String: String]?

    public init(
        cursor: CameraRollCursorStore = CameraRollCursorStore(),
        notifier: AppleCameraRollImportNotifier = .shared
    ) {
        self.cursor = cursor
        self.notifier = notifier
        super.init()
    }

    public func startObserving() {
        guard !isObserving else { return }
        PHPhotoLibrary.shared().register(self)
        isObserving = true
        scanQueue.async {
            guard Self.libraryReadable else { return }
            self.allAssets = PHAsset.fetchAssets(with: nil)
            // Catch up on everything since the last scan, including what
            // was captured or deleted while the app was not running.
            self.scan(sessionRemovals: [])
        }
        lineageQueue.async {
            Self.pruneStagedMediaDirectory()
        }
    }

    public func stopObserving() {
        guard isObserving else { return }
        PHPhotoLibrary.shared().unregisterChangeObserver(self)
        isObserving = false
    }

    public func photoLibraryDidChange(_ changeInstance: PHChange) {
        scanQueue.async {
            var removed = [String]()
            if let fetch = self.allAssets, let details = changeInstance.changeDetails(for: fetch) {
                self.allAssets = details.fetchResultAfterChanges
                removed = details.removedObjects.map { $0.localIdentifier }
            }
            self.scan(sessionRemovals: removed)
        }
    }

    /// Runs a scan now (asynchronously, serialised with change callbacks).
    public func scanForChanges() {
        scanQueue.async {
            self.scan(sessionRemovals: [])
        }
    }

    // MARK: - Scanning (scanQueue only)

    /// Finds what changed since the persisted position, records the
    /// intent for every new asset (a pending_import row) before moving
    /// the position past it, and turns removals into delete events.
    ///
    /// Prefers the persistent change history (iOS 16+), which also
    /// reports assets that arrive late from iCloud and deletions made
    /// while the app was closed. Falls back to the creation-date cursor
    /// (and to this session's PHChange removals) when there is no usable
    /// token.
    private func scan(sessionRemovals: [String]) {
        guard Self.libraryReadable else { return }
        guard BranchDamCoreBridge.shared.isInitialized else {
            NSLog("PhotoKitObserver: engine bridge uninitialized, skipping asset discovery")
            return
        }
        // One engine call for the whole scan instead of one per asset. A
        // failed read is nil, not "nothing queued": persistIntent then
        // asks per asset rather than re-staging the whole library.
        queuedStatuses = BranchDamCoreBridge.shared.loadAllMediaStatuses()

        if let token = loadToken() {
            do {
                let persisted = try scanPersistentChanges(since: token)
                finishScan(persisted: persisted)
                return
            } catch {
                // Expired or unavailable history: start over from the
                // date cursor.
                NSLog("PhotoKitObserver: persistent change history unavailable (%@); using the date cursor", String(describing: error))
                cursor.clearChangeToken()
            }
        }

        // Captured before the fetch, so changes made during this scan
        // are seen again by the next token-based scan.
        let tokenBefore = PHPhotoLibrary.shared().currentChangeToken
        processRemovals(sessionRemovals)

        let since = cursor.lastScannedDate
        let options = PHFetchOptions()
        options.predicate = NSPredicate(format: "creationDate > %@", since as NSDate)
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: true)]
        let assets = Self.discoveredAssets(in: PHAsset.fetchAssets(with: options))

        let result = CameraRollIntake.commit(assets: assets, startCursor: since, persist: persistIntent)
        if let advanced = result.advancedTo {
            cursor.advanceDate(to: advanced)
        }
        if result.complete {
            saveToken(tokenBefore)
        }
        finishScan(persisted: result.persisted)
    }

    /// Walks the persistent change history one change at a time. A
    /// change's token is saved only after all of its inserted assets
    /// were recorded and its deletions handled; the walk stops at the
    /// first change that could not be recorded, so it is retried.
    private func scanPersistentChanges(since token: PHPersistentChangeToken) throws -> [DiscoveredAsset] {
        let changes = try PHPhotoLibrary.shared().fetchPersistentChanges(since: token)
        var persisted = [DiscoveredAsset]()
        for change in changes {
            let details = try change.changeDetails(for: .asset)
            let inserted = Array(details.insertedLocalIdentifiers)
            let assets = inserted.isEmpty
                ? []
                : Self.discoveredAssets(in: PHAsset.fetchAssets(withLocalIdentifiers: inserted, options: nil))
            let result = CameraRollIntake.commit(assets: assets, startCursor: .distantPast, persist: persistIntent)
            persisted += result.persisted
            guard result.complete else { break }
            processRemovals(Array(details.deletedLocalIdentifiers))
            if let newest = assets.map({ $0.effectiveCreationDate }).max() {
                cursor.advanceDate(to: newest)
            }
            saveToken(change.changeToken)
        }
        return persisted
    }

    /// Records one asset's import intent. An asset already in the upload
    /// queue was imported before and is not offered again; one the user
    /// skipped stays skipped.
    private func persistIntent(_ asset: DiscoveredAsset) -> CameraRollIntake.Outcome {
        let status = queuedStatuses.map { $0[asset.localIdentifier] ?? "NOT_ENQUEUED" }
            ?? BranchDamCoreBridge.shared.getMediaStatus(localID: asset.localIdentifier)
        if status != "NOT_ENQUEUED" {
            return .alreadyDecided
        }
        switch notifier.stagePendingAsset(asset) {
        case .staged: return .persisted
        case .suppressed: return .alreadyDecided
        case .failed: return .failed
        }
    }

    private func finishScan(persisted: [DiscoveredAsset]) {
        if notifier.autoImportEnabled {
            // Also picks up assets a killed process left pending.
            notifier.importAllPending()
        } else if !persisted.isEmpty {
            let pendingCount = max(notifier.getPendingAssets().count, persisted.count)
            notifier.postImportNotification(count: pendingCount)
        }

        // E.6: Lineage pipeline runs for BOTH auto-import and
        // confirmation-based import. Recording edges before
        // confirmation is safe — the engine deduplicates by local ID.
        if !persisted.isEmpty {
            runLineageDetection(persisted)
        }
    }

    /// Enqueues delete events for assets removed from the library, keyed
    /// by the local ID they were uploaded under (and the Live Photo's
    /// paired-video child ID). The core drops events for media that was
    /// never uploaded or was reclaimed.
    private func processRemovals(_ localIdentifiers: [String]) {
        for id in localIdentifiers {
            AppleTrashSyncObserver.processRemovedAsset(localId: id)
            // The asset is gone, so whether it was a Live Photo is only
            // known from the queue: its paired video was enqueued under
            // this child ID.
            let pairedVideoId = AppleCameraRollImportNotifier.pairedVideoLocalID(stillLocalID: id)
            if BranchDamCoreBridge.shared.getMediaStatus(localID: pairedVideoId) != "NOT_ENQUEUED" {
                AppleTrashSyncObserver.processRemovedAsset(localId: pairedVideoId)
            }
        }
    }

    private static var libraryReadable: Bool {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        return status == .authorized || status == .limited
    }

    private func loadToken() -> PHPersistentChangeToken? {
        guard let data = cursor.changeTokenData else { return nil }
        return try? NSKeyedUnarchiver.unarchivedObject(ofClass: PHPersistentChangeToken.self, from: data)
    }

    private func saveToken(_ token: PHPersistentChangeToken) {
        guard let data = try? NSKeyedArchiver.archivedData(withRootObject: token, requiringSecureCoding: true) else { return }
        cursor.saveChangeTokenData(data)
    }

    private static func discoveredAssets(in fetchResult: PHFetchResult<PHAsset>) -> [DiscoveredAsset] {
        var out = [DiscoveredAsset]()
        fetchResult.enumerateObjects { asset, _, _ in
            out.append(Self.discoveredAsset(from: asset))
        }
        return out
    }

    private static func discoveredAsset(from asset: PHAsset) -> DiscoveredAsset {
        let resources = PHAssetResource.assetResources(for: asset)
        let primaryResource = resources.first(where: { $0.type == .photo || $0.type == .video || $0.type == .alternatePhoto }) ?? resources.first
        let filename = primaryResource?.originalFilename ?? "IMG_\(asset.localIdentifier.prefix(8)).JPG"
        let creationUnix = Int64(asset.creationDate?.timeIntervalSince1970 ?? 0)
        let isRaw = filename.uppercased().hasSuffix(".DNG") || resources.contains(where: { $0.originalFilename.uppercased().hasSuffix(".DNG") })
        return DiscoveredAsset(
            localIdentifier: asset.localIdentifier,
            filename: filename,
            creationDateUnix: creationUnix,
            isRaw: isRaw,
            isVideo: asset.mediaType == .video,
            isLivePhoto: asset.mediaSubtypes.contains(.photoLive),
            pixelWidth: asset.pixelWidth,
            pixelHeight: asset.pixelHeight,
            creationDate: asset.creationDate
        )
    }

    private func fetchRecentContextAssets() -> [DiscoveredAsset] {
        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        options.fetchLimit = 100

        let fetchResult = PHAsset.fetchAssets(with: options)
        var contextAssets = [DiscoveredAsset]()

        fetchResult.enumerateObjects { asset, _, _ in
            let resources = PHAssetResource.assetResources(for: asset)
            let primaryResource = resources.first(where: { $0.type == .photo || $0.type == .video || $0.type == .alternatePhoto }) ?? resources.first
            let filename = primaryResource?.originalFilename ?? "IMG_\(asset.localIdentifier.prefix(8)).JPG"
            let creationUnix = Int64(asset.creationDate?.timeIntervalSince1970 ?? 0)
            let isRaw = filename.uppercased().hasSuffix(".DNG") || resources.contains(where: { $0.originalFilename.uppercased().hasSuffix(".DNG") })
            let isVideo = asset.mediaType == .video
            let isLivePhoto = asset.mediaSubtypes.contains(.photoLive)

            contextAssets.append(
                DiscoveredAsset(
                    localIdentifier: asset.localIdentifier,
                    filename: filename,
                    creationDateUnix: creationUnix,
                    isRaw: isRaw,
                    isVideo: isVideo,
                    isLivePhoto: isLivePhoto
                )
            )
        }
        return contextAssets
    }

    // Lineage events use the same local ID as the upload enqueue: the bare
    // PHAsset localIdentifier (no "ph://" prefix), so the core can resolve
    // both endpoints to their server node UUIDs.
    private func runLineageDetection(_ assets: [DiscoveredAsset]) {
        lineageQueue.async {
            let contextAssets = self.fetchRecentContextAssets()
            var combinedMap = [String: DiscoveredAsset]()
            for a in contextAssets { combinedMap[a.localIdentifier] = a }
            for a in assets { combinedMap[a.localIdentifier] = a }
            let candidateAssets = Array(combinedMap.values)

            // ProRAW pair detection (DNG + HEIC/JPEG companions).
            let raws: [DiscoveredAsset] = candidateAssets.filter({ (asset: DiscoveredAsset) -> Bool in asset.isRaw == true })
            let jpegs: [DiscoveredAsset] = candidateAssets.filter({ (asset: DiscoveredAsset) -> Bool in (asset.isRaw == false) && (asset.isVideo == false) })
            if !raws.isEmpty && !jpegs.isEmpty {
                let pairs = ApplePairDetector.findProRawPairs(
                    masters: raws.map { (id: $0.localIdentifier, filename: $0.filename, dateUnix: $0.creationDateUnix) },
                    derivatives: jpegs.map { (id: $0.localIdentifier, filename: $0.filename, dateUnix: $0.creationDateUnix) }
                )
                _ = ApplePairDetector.registerPairs(pairs: pairs)
            }

            // Edit correlation (in-phone editor exports -> camera roll master).
            let editDerivatives: [DiscoveredAsset] = assets.filter({ (item: DiscoveredAsset) -> Bool in
                item.filename.lowercased().contains("edited") ||
                    item.filename.lowercased().contains("restored")
            })
            if !editDerivatives.isEmpty {
                let edits = AppleEditCorrelator.findAppEdits(
                    masters: assets.map { (id: $0.localIdentifier, filename: $0.filename) },
                    derivatives: editDerivatives.map { (id: $0.localIdentifier, filename: $0.filename, app: "ios_editor") }
                )
                _ = AppleEditCorrelator.registerEditLineage(edits: edits)
            }

            // A Live Photo's motion track is queued and linked by
            // AppleCameraRollImportNotifier when the still is imported, in
            // every import mode.
        }
    }

    // MARK: - Resource Staging Helpers

    public static func stagedMediaDirectory(customDir: URL? = nil) -> URL {
        if let customDir = customDir { return customDir }
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let dir = caches.appendingPathComponent("staged_media", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            do {
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            } catch {
                NSLog("PhotoKitObserver: failed to create stagedMediaDirectory %@: %@", dir.path, String(describing: error))
            }
        }
        return dir
    }

    /**
     * Reaps orphaned .part files and staged media older than maxAgeSeconds,
     * provided the staged file path is not currently tracked as PENDING, IN_PROGRESS, or FAILED.
     * Fails closed if the engine bridge is not yet initialized unless custom statusMap is provided.
     */
    public static func pruneStagedMediaDirectory(
        directory: URL = stagedMediaDirectory(),
        maxAgeSeconds: TimeInterval = 86400 * 7,
        statusMap: [String: String]? = nil
    ) {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.creationDateKey]) else { return }

        // Fail closed if bridge is uninitialized and no custom statusMap is supplied
        guard statusMap != nil || BranchDamCoreBridge.shared.isInitialized else {
            // Still reap orphaned .part files even if bridge is uninitialized
            for file in files where file.pathExtension == "part" {
                try? fm.removeItem(at: file)
            }
            return
        }

        let now = Date()
        let activeStatuses = statusMap ?? BranchDamCoreBridge.shared.getAllMediaStatuses()

        for file in files {
            if file.pathExtension == "part" {
                try? fm.removeItem(at: file)
            } else if let attrs = try? fm.attributesOfItem(atPath: file.path),
                      let creationDate = attrs[.creationDate] as? Date,
                      now.timeIntervalSince(creationDate) > maxAgeSeconds {
                // Core maps upload_queue.LocalPath as well as localID/filename/blake3;
                // suppress deletion if tracked as PENDING, IN_PROGRESS, or FAILED.
                let status = activeStatuses[file.path] ?? "NOT_ENQUEUED"
                if status == "COMPLETED" || status == "OFFLOADED" || status == "NOT_ENQUEUED" {
                    try? fm.removeItem(at: file)
                }
            }
        }
    }

    /**
     * Stages a PhotoKit asset resource to a local POSIX path inside Caches/staged_media/.
     * Writes to an atomic .part file first and renames upon completion to ensure
     * incomplete/interrupted writes are never enqueued. Non-blocking async callback.
     */
    public static func stageResourceAtomically(
        resource: PHAssetResource,
        filename: String,
        directory: URL = stagedMediaDirectory(),
        completion: @escaping (String?) -> Void
    ) {
        let targetURL = directory.appendingPathComponent(filename)
        if FileManager.default.fileExists(atPath: targetURL.path),
           let attr = try? FileManager.default.attributesOfItem(atPath: targetURL.path),
           (attr[.size] as? Int64 ?? 0) > 0 {
            completion(targetURL.path)
            return
        }

        let partURL = directory.appendingPathComponent("\(filename).\(UUID().uuidString).part")
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true

        PHAssetResourceManager.default().writeData(for: resource, toFile: partURL, options: options) { error in
            if let error = error {
                NSLog("stageResourceAtomically failed for %@: %@", filename, String(describing: error))
                try? FileManager.default.removeItem(at: partURL)
                completion(nil)
                return
            }

            guard FileManager.default.fileExists(atPath: partURL.path),
                  let attr = try? FileManager.default.attributesOfItem(atPath: partURL.path),
                  (attr[.size] as? Int64 ?? 0) > 0 else {
                NSLog("stageResourceAtomically wrote zero bytes for %@", filename)
                try? FileManager.default.removeItem(at: partURL)
                completion(nil)
                return
            }

            do {
                if FileManager.default.fileExists(atPath: targetURL.path) {
                    try? FileManager.default.removeItem(at: targetURL)
                }
                try FileManager.default.moveItem(at: partURL, to: targetURL)
                completion(targetURL.path)
            } catch {
                NSLog("stageResourceAtomically moveItem failed: %@", String(describing: error))
                try? FileManager.default.removeItem(at: partURL)
                completion(nil)
            }
        }
    }
}
