import Foundation
import Photos
import UserNotifications

/// Copies a camera-roll asset's primary resource to a local file the Go
/// engine can read, calling back with its path (nil on failure).
public protocol AssetStager: Sendable {
    func stage(localIdentifier: String, filename: String, completion: @escaping @Sendable (String?) -> Void)
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
        afterImport: (@Sendable (Action, Int) -> Void)? = nil
    ) {
        self.engine = engine
        self.stager = stager
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
    /// is folded into a single follow-up pass (and reports 0 itself).
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

    private func importItems(_ items: [PendingImportItem], completion: @escaping @Sendable (Int) -> Void) {
        let group = DispatchGroup()
        let counter = QueuedCounter()
        for item in items {
            group.enter()
            stager.stage(localIdentifier: item.localId, filename: item.filename) { [engine] path in
                defer { group.leave() }
                guard let path = path else {
                    NSLog("AppleCameraRollImportNotifier: staging failed for %@; left pending", item.localId)
                    return
                }
                let queueId = engine.enqueueStagedMedia(
                    localPath: path,
                    filename: item.filename,
                    capturedAtUnix: item.capturedAtUnix,
                    localID: item.localId
                )
                if queueId > 0 {
                    engine.deletePendingImport(localID: item.localId)
                    counter.increment()
                }
            }
        }
        group.notify(queue: queue) {
            completion(counter.value)
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
