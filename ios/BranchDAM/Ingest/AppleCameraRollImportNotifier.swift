import Foundation
import UserNotifications

public class AppleCameraRollImportNotifier {
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

    public var autoImportEnabled: Bool {
        get {
            return UserDefaults.standard.bool(forKey: BranchDamKeys.autoImportCameraRoll.rawValue)
        }
        set {
            UserDefaults.standard.set(newValue, forKey: BranchDamKeys.autoImportCameraRoll.rawValue)
        }
    }

    private var suppressedAssetIds = Set<String>()
    private var pendingAssetsMap = [String: DiscoveredAsset]()
    private let lock = NSLock()

    public init() {}

    public func isAssetSuppressed(identifier: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return suppressedAssetIds.contains(identifier)
    }

    public func suppressAsset(identifier: String) {
        lock.lock()
        defer { lock.unlock() }
        suppressedAssetIds.insert(identifier)
    }

    public func suppressAssets(_ identifiers: [String]) {
        lock.lock()
        defer { lock.unlock() }
        suppressedAssetIds.formUnion(identifiers)
    }

    public func clearSuppressed() {
        lock.lock()
        defer { lock.unlock() }
        suppressedAssetIds.removeAll()
    }

    public func stagePendingAssets(_ assets: [DiscoveredAsset]) {
        lock.lock()
        defer { lock.unlock() }
        for asset in assets {
            pendingAssetsMap[asset.localIdentifier] = asset
        }
    }

    public func getPendingAssets() -> [DiscoveredAsset] {
        lock.lock()
        defer { lock.unlock() }
        return Array(pendingAssetsMap.values)
    }

    public func clearPendingAssets() {
        lock.lock()
        defer { lock.unlock() }
        pendingAssetsMap.removeAll()
    }

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

    public func postImportNotification(count: Int, assetIdentifiers: [String] = []) {
        guard count > 0 else { return }

        let content = UNMutableNotificationContent()
        content.title = "New Photos Detected"
        content.body = "\(count) new photo(s) ready to import to branchDAM"
        content.categoryIdentifier = Self.categoryIdentifier
        content.userInfo = ["assetIdentifiers": assetIdentifiers]

        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)
        let request = UNNotificationRequest(
            identifier: UUID().uuidString,
            content: content,
            trigger: trigger
        )

        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }

    public func handleAction(actionIdentifier: String, assetIdentifiers: [String] = []) {
        lock.lock()
        let targetIds = assetIdentifiers.isEmpty ? Array(pendingAssetsMap.keys) : assetIdentifiers
        lock.unlock()

        switch actionIdentifier {
        case Self.actionImportNow:
            lock.lock()
            for id in targetIds {
                if let item = pendingAssetsMap.removeValue(forKey: id), !suppressedAssetIds.contains(id) {
                    _ = BranchDamCoreBridge.shared.enqueueMedia(
                        localPath: "ph://\(item.localIdentifier)",
                        filename: item.filename,
                        capturedAtUnix: item.creationDateUnix,
                        localID: item.localIdentifier
                    )
                }
            }
            lock.unlock()
            BackgroundSyncManager.shared.triggerImmediateSync()

        case Self.actionLater:
            lock.lock()
            for id in targetIds {
                if let item = pendingAssetsMap.removeValue(forKey: id), !suppressedAssetIds.contains(id) {
                    _ = BranchDamCoreBridge.shared.enqueueMedia(
                        localPath: "ph://\(item.localIdentifier)",
                        filename: item.filename,
                        capturedAtUnix: item.creationDateUnix,
                        localID: item.localIdentifier
                    )
                }
            }
            lock.unlock()
            BackgroundSyncManager.shared.scheduleBackgroundSync()

        case Self.actionSkip:
            suppressAssets(targetIds)
            lock.lock()
            for id in targetIds {
                pendingAssetsMap.removeValue(forKey: id)
            }
            lock.unlock()

        default:
            break
        }
    }
}
