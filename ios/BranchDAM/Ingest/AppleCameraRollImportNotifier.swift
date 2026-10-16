import Foundation
import Photos
import UserNotifications

/// Copies a camera-roll asset's primary resource to a local file the Go
/// engine can read, calling back with its path (nil on failure).
public protocol AssetStager: Sendable {
    func stage(localIdentifier: String, filename: String, completion: @escaping @Sendable (String?) -> Void)
    /// Stages a Live Photo's motion track.
    func stagePairedVideo(localIdentifier: String, completion: @escaping @Sendable (PairedVideoStaging) -> Void)
}

/// What staging a Live Photo's motion track produced.
public enum PairedVideoStaging: Equatable, Sendable {
    /// The asset has no motion track (not a Live Photo, or gone).
    case none
    /// Staged to a local file.
    case staged(StagedResource)
    /// It has one, but the copy failed; worth retrying later.
    case failed
}

/// A resource staged to a local file, with the name it should be uploaded
/// under.
public struct StagedResource: Equatable, Sendable {
    public let path: String
    public let filename: String

    public init(path: String, filename: String) {
        self.path = path
        self.filename = filename
    }
}

extension AssetStager {
    public func stagePairedVideo(localIdentifier: String, completion: @escaping @Sendable (PairedVideoStaging) -> Void) {
        completion(.none)
    }
}

/// Stages through `PhotoKitObserver.stageResourceAtomically`, the same
/// atomic `.part`-then-rename copy into Caches/staged_media/ that
/// auto-import has always used, under the same file name.
public struct PhotoKitAssetStager: AssetStager {
    public init() {}

    public func stage(localIdentifier: String, filename: String, completion: @escaping @Sendable (String?) -> Void) {
        let cleanId = localIdentifier.hasPrefix("ph://") ? String(localIdentifier.dropFirst(5)) : localIdentifier
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [cleanId], options: nil).firstObject else {
            NSLog("PhotoKitAssetStager: asset %@ no longer exists", cleanId)
            completion(nil)
            return
        }
        let resources = PHAssetResource.assetResources(for: asset)
        guard let resource = resources.first(where: { $0.type == .photo || $0.type == .video || $0.type == .alternatePhoto }) ?? resources.first else {
            NSLog("PhotoKitAssetStager: asset %@ has no resources", cleanId)
            completion(nil)
            return
        }
        let safeFilename = "\(cleanId.replacingOccurrences(of: "/", with: "_"))_\(filename)"
        PhotoKitObserver.stageResourceAtomically(resource: resource, filename: safeFilename) { path in
            completion(path)
        }
    }

    public func stagePairedVideo(localIdentifier: String, completion: @escaping @Sendable (PairedVideoStaging) -> Void) {
        let cleanId = localIdentifier.hasPrefix("ph://") ? String(localIdentifier.dropFirst(5)) : localIdentifier
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [cleanId], options: nil).firstObject,
              let video = PHAssetResource.assetResources(for: asset).first(where: { $0.type == .pairedVideo }) else {
            completion(.none)
            return
        }
        let videoFilename = video.originalFilename
        let safeFilename = "\(cleanId.replacingOccurrences(of: "/", with: "_"))_pairedVideo.mov"
        PhotoKitObserver.stageResourceAtomically(resource: video, filename: safeFilename) { path in
            if let path = path {
                completion(.staged(StagedResource(path: path, filename: videoFilename)))
            } else {
                completion(.failed)
            }
        }
    }
}

/// The camera-roll import flow: the "New Photos Detected" notification
/// and its actions, and the import of pending assets.
///
/// Assets awaiting a decision, and the ones the user skipped, live in the
/// core's pending_import table (`PendingImportEngine`), not in memory, so
/// an action delivered after the app was relaunched still finds them and
/// a skipped asset is never offered again. Importing always stages the
/// asset's bytes to a real file first (`AssetStager`); the old handler
/// enqueued a "ph://" path the engine cannot open.
public final class AppleCameraRollImportNotifier: @unchecked Sendable {
    public static let shared = AppleCameraRollImportNotifier()

    /// Backwards-compatible alias of
    /// [BranchDamKeys.autoImportCameraRoll.rawValue] retained for
    /// any external callers (and tests) that depended on the
    /// previous static-let shape. New code should reference
    /// `BranchDamKeys.autoImportCameraRoll` directly.
    public static var keyAutoImportEnabled: String { BranchDamKeys.autoImportCameraRoll.rawValue }
    public static let categoryIdentifier = "com.branchdam.category.cameraRollImport"

    public static let actionImportNow = "com.branchdam.action.importNow"
    public static let actionLater = "com.branchdam.action.later"
    public static let actionSkip = "com.branchdam.action.skip"

    /// userInfo key: seconds since the epoch at which the notification
    /// was posted. An action applies to the assets staged by then, so
    /// "Skip" never suppresses a photo the user was not told about.
    public static let userInfoStagedBeforeKey = "stagedBeforeUnix"

    /// One request identifier, so a newer notification replaces the
    /// previous one instead of stacking.
    public static let notificationIdentifier = "com.branchdam.notification.cameraRollImport"

    /// A notification action.
    public enum Action: Equatable, Sendable {
        case importNow
        case later
        case skip

        public init?(actionIdentifier: String) {
            switch actionIdentifier {
            case AppleCameraRollImportNotifier.actionImportNow: self = .importNow
            case AppleCameraRollImportNotifier.actionLater: self = .later
            case AppleCameraRollImportNotifier.actionSkip: self = .skip
            default: return nil
            }
        }
    }

    public var autoImportEnabled: Bool {
        get {
            return UserDefaults.standard.bool(forKey: BranchDamKeys.autoImportCameraRoll.rawValue)
        }
        set {
            UserDefaults.standard.set(newValue, forKey: BranchDamKeys.autoImportCameraRoll.rawValue)
        }
    }

    private let engine: PendingImportEngine
    private let stager: AssetStager
    private let afterImport: @Sendable (Action, Int) -> Void
    /// Records the still -> motion-track lineage edge (still ID, video
    /// ID, still name, video name).
    private let linkPairedVideo: @Sendable (String, String, String, String) -> Void
    private let queue = DispatchQueue(label: "com.branchdam.mobile.import", qos: .userInitiated)
    /// Auto-import pass state; `queue` only.
    private var autoImportRunning = false
    private var autoImportRerun = false

    /// - Parameters:
    ///   - afterImport: called with the action and the number of assets
    ///     queued; by default Import now syncs at once and Later schedules
    ///     the background sync.
    public init(
        engine: PendingImportEngine = BranchDamCoreBridge.shared,
        stager: AssetStager = PhotoKitAssetStager(),
        afterImport: (@Sendable (Action, Int) -> Void)? = nil,
        linkPairedVideo: (@Sendable (String, String, String, String) -> Void)? = nil
    ) {
        self.engine = engine
        self.stager = stager
        self.linkPairedVideo = linkPairedVideo ?? { stillID, videoID, stillName, videoName in
            _ = LivePhotoExtractor.linkLivePhoto(stillId: stillID, videoId: videoID, stillFilename: stillName, videoFilename: videoName)
        }
        self.afterImport = afterImport ?? { action, queued in
            switch action {
            case .importNow:
                if queued > 0 { BackgroundSyncManager.shared.triggerImmediateSync() }
            case .later:
                BackgroundSyncManager.shared.scheduleBackgroundSync()
            case .skip:
                break
            }
        }
    }

    // MARK: - Pending assets (blocking engine calls; call off the main thread)

    public func isAssetSuppressed(identifier: String) -> Bool {
        engine.isImportSuppressed(localID: identifier)
    }

    @discardableResult
    public func suppressAsset(identifier: String) -> Bool {
        engine.suppressPendingImport(localID: identifier)
    }

    /// Persists [asset] as awaiting the user's decision. `.suppressed`
    /// (nothing staged) when the user already skipped it.
    ///
    /// `createdAtUnix` is passed as 0: the engine stamps the staging time
    /// itself (the core's `created_at_unix`), and that stamp is what
    /// `listPendingImports` returns and the notification cutoff compares.
    public func stagePendingAsset(_ asset: DiscoveredAsset) -> PendingImportStageResult {
        engine.stagePendingImport(PendingImportItem(
            localId: asset.localIdentifier,
            filename: asset.filename,
            capturedAtUnix: asset.creationDateUnix,
            createdAtUnix: 0
        ))
    }

    /// Assets awaiting a decision; empty when they can't be read.
    public func getPendingAssets() -> [PendingImportItem] {
        engine.listPendingImports() ?? []
    }

    // MARK: - Notification

    public func registerNotificationCategories() {
        let importAction = UNNotificationAction(
            identifier: Self.actionImportNow,
            title: "Import now",
            options: [.foreground]
        )
        let laterAction = UNNotificationAction(
            identifier: Self.actionLater,
            title: "Later",
            options: []
        )
        let skipAction = UNNotificationAction(
            identifier: Self.actionSkip,
            title: "Skip",
            options: [.destructive]
        )

        let category = UNNotificationCategory(
            identifier: Self.categoryIdentifier,
            actions: [importAction, laterAction, skipAction],
            intentIdentifiers: [],
            options: []
        )

        UNUserNotificationCenter.current().setNotificationCategories([category])
    }

    public func postImportNotification(count: Int, stagedBeforeUnix: Int64 = Int64(Date().timeIntervalSince1970)) {
        guard count > 0 else { return }

        let content = UNMutableNotificationContent()
        content.title = "New Photos Detected"
        content.body = "\(count) new photo(s) ready to import to branchDAM"
        content.categoryIdentifier = Self.categoryIdentifier
        content.userInfo = [Self.userInfoStagedBeforeKey: NSNumber(value: stagedBeforeUnix)]

        let request = UNNotificationRequest(
            identifier: Self.notificationIdentifier,
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }

    // MARK: - Actions

    /// Applies a notification action to the pending assets staged at or
    /// before [stagedBeforeUnix], on a background queue:
    ///
    /// - Import now / Later: stage each asset to a file, queue it for
    ///   upload, and forget its pending row once queued (an asset whose
    ///   staging or enqueue failed stays pending).
    /// - Skip: suppress each asset so no later scan offers it again.
    ///
    /// [completion] gets false, with nothing changed, when the pending
    /// assets can't be read (engine not open).
    public func handleAction(
        _ action: Action,
        stagedBeforeUnix: Int64 = .max,
        completion: (@Sendable (Bool) -> Void)? = nil
    ) {
        queue.async {
            guard let pending = self.engine.listPendingImports() else {
                completion?(false)
                return
            }
            let targets = pending.filter { $0.createdAtUnix <= stagedBeforeUnix }
            switch action {
            case .skip:
                for item in targets {
                    self.engine.suppressPendingImport(localID: item.localId)
                }
                completion?(true)
            case .importNow, .later:
                self.importItems(targets) { queued in
                    self.afterImport(action, queued)
                    completion?(true)
                }
            }
        }
    }

    /// String-identifier form of [handleAction]; unknown identifiers are
    /// ignored (completion gets false).
    public func handleAction(
        actionIdentifier: String,
        stagedBeforeUnix: Int64 = .max,
        completion: (@Sendable (Bool) -> Void)? = nil
    ) {
        guard let action = Action(actionIdentifier: actionIdentifier) else {
            completion?(false)
            return
        }
        handleAction(action, stagedBeforeUnix: stagedBeforeUnix, completion: completion)
    }

    /// Imports every pending asset, as auto-import does after a scan (and
    /// on launch, for assets a killed process left pending).
    ///
    /// Runs one import at a time: a request made while one is running
    /// is folded into a single follow-up pass. Its completion then gets 0
    /// -- "this call queued nothing itself", not "nothing is queued": the
    /// follow-up pass imports its assets. (The gallery uses importAssets,
    /// which always reports its own count.)
    public func importAllPending(completion: (@Sendable (Int) -> Void)? = nil) {
        queue.async {
            if self.autoImportRunning {
                self.autoImportRerun = true
                completion?(0)
                return
            }
            self.runAutoImport(completion)
        }
    }

    /// queue only.
    private func runAutoImport(_ completion: (@Sendable (Int) -> Void)?) {
        guard let pending = engine.listPendingImports(), !pending.isEmpty else {
            completion?(0)
            return
        }
        autoImportRunning = true
        importItems(pending) { queued in
            // On `queue` (importItems notifies there).
            self.afterImport(.importNow, queued)
            completion?(queued)
            self.autoImportRunning = false
            if self.autoImportRerun {
                self.autoImportRerun = false
                self.runAutoImport(nil)
            }
        }
    }

    /// How many assets are staged at once. Each is a full-size copy into
    /// Caches, so a large backlog (a first scan, or rows a killed process
    /// left) is worked through a few at a time rather than all at once.
    static let importChunkSize = 4

    /// Imports [items] a chunk at a time; [completion] (on `queue`) gets
    /// how many of them are in the upload queue afterwards (newly queued
    /// or already there). Call on `queue`.
    private func importItems(_ items: [PendingImportItem], completion: @escaping @Sendable (Int) -> Void) {
        importChunk(items[...], queuedSoFar: 0, completion: completion)
    }

    private func importChunk(
        _ remaining: ArraySlice<PendingImportItem>,
        queuedSoFar: Int,
        completion: @escaping @Sendable (Int) -> Void
    ) {
        guard !remaining.isEmpty else {
            completion(queuedSoFar)
            return
        }
        let chunk = remaining.prefix(Self.importChunkSize)
        let rest = remaining.dropFirst(chunk.count)
        let group = DispatchGroup()
        let counter = QueuedCounter()
        for item in chunk {
            group.enter()
            importItem(item) { inQueue in
                if inQueue { counter.increment() }
                group.leave()
            }
        }
        group.notify(queue: queue) {
            self.importChunk(rest, queuedSoFar: queuedSoFar + counter.value, completion: completion)
        }
    }

    /// Imports one asset: stages and queues the still unless the engine
    /// already has it, then its Live Photo motion track, if any. The
    /// pending row is forgotten only once both are queued (or there is no
    /// motion track), so a failed video copy -- or a process killed
    /// between the two -- leaves the row for a later pass, which skips the
    /// already-queued still and retries just the video. [done] gets
    /// whether the still is in the upload queue afterwards, newly or
    /// already (a gallery re-tap of a queued asset is not a failure). A
    /// FAILED upload is staged and enqueued again, which is what resets
    /// it for a retry.
    private func importItem(_ item: PendingImportItem, done: @escaping @Sendable (Bool) -> Void) {
        if UploadStatus.isQueued(engine.mediaStatus(localID: item.localId)) {
            importPairedVideo(of: item, done: done)
            return
        }
        stager.stage(localIdentifier: item.localId, filename: item.filename) { [engine] path in
            guard let path = path else {
                NSLog("AppleCameraRollImportNotifier: staging failed for %@; left pending", item.localId)
                done(false)
                return
            }
            let queueId = engine.enqueueStagedMedia(
                localPath: path,
                filename: item.filename,
                capturedAtUnix: item.capturedAtUnix,
                localID: item.localId
            )
            guard queueId > 0 else {
                NSLog("AppleCameraRollImportNotifier: enqueue failed for %@; left pending", item.localId)
                done(false)
                return
            }
            self.importPairedVideo(of: item, done: done)
        }
    }

    /// Imports camera-roll assets picked in the gallery, through the same
    /// stage-then-queue path as the import notification (never a "ph://"
    /// path the engine can't read). Returns how many are in the upload
    /// queue afterwards, newly or already (a FAILED upload counts once it
    /// has been re-queued).
    public func importAssets(_ items: [PendingImportItem]) async -> Int {
        await withCheckedContinuation { continuation in
            queue.async {
                self.importItems(items) { queued in
                    continuation.resume(returning: queued)
                }
            }
        }
    }
}

extension AppleCameraRollImportNotifier {
    /// The local ID of a Live Photo's motion track: the still's ID plus
    /// "/pairedVideo" (what removal handling looks up, too).
    public static func pairedVideoLocalID(stillLocalID: String) -> String {
        "\(stillLocalID)/pairedVideo"
    }

    /// Queues a Live Photo's motion track once its still is queued, links
    /// the two, and only then forgets the pending row. Runs for every
    /// import path (auto-import, Import now, Later, the gallery), so the
    /// video never depends on the import mode. The still is already in
    /// the upload queue, so [done] always gets true; a video that could
    /// not be queued keeps the row for a later pass.
    fileprivate func importPairedVideo(
        of item: PendingImportItem,
        done: @escaping @Sendable (Bool) -> Void
    ) {
        let videoID = Self.pairedVideoLocalID(stillLocalID: item.localId)
        if UploadStatus.isQueued(engine.mediaStatus(localID: videoID)) {
            engine.deletePendingImport(localID: item.localId)
            done(true)
            return
        }
        stager.stagePairedVideo(localIdentifier: item.localId) { [engine, linkPairedVideo] result in
            switch result {
            case .none:
                engine.deletePendingImport(localID: item.localId)
                done(true)
            case .failed:
                NSLog("AppleCameraRollImportNotifier: staging the motion track of %@ failed; left pending", item.localId)
                done(true)
            case .staged(let staged):
                let queueId = engine.enqueueStagedMedia(
                    localPath: staged.path,
                    filename: staged.filename,
                    capturedAtUnix: item.capturedAtUnix,
                    localID: videoID
                )
                guard queueId > 0 else {
                    NSLog("AppleCameraRollImportNotifier: enqueue of the motion track of %@ failed; left pending", item.localId)
                    done(true)
                    return
                }
                linkPairedVideo(item.localId, videoID, item.filename, staged.filename)
                engine.deletePendingImport(localID: item.localId)
                done(true)
            }
        }
    }
}

private final class QueuedCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    func increment() {
        lock.lock()
        count += 1
        lock.unlock()
    }

    var value: Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }
}
