import Foundation
import UserNotifications

public class AppleCameraRollImportNotifier {
    public static let shared = AppleCameraRollImportNotifier()

    public static let keyAutoImportEnabled = "branchdam_auto_import_camera_roll"
    public static let categoryIdentifier = "com.branchdam.category.cameraRollImport"

    public static let actionImportNow = "com.branchdam.action.importNow"
    public static let actionLater = "com.branchdam.action.later"
    public static let actionSkip = "com.branchdam.action.skip"

    public var autoImportEnabled: Bool {
        get {
            return UserDefaults.standard.bool(forKey: Self.keyAutoImportEnabled)
        }
        set {
            UserDefaults.standard.set(newValue, forKey: Self.keyAutoImportEnabled)
        }
    }

    private var suppressedAssetIds = Set<String>()
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
        switch actionIdentifier {
        case Self.actionImportNow:
            BackgroundSyncManager.shared.triggerImmediateSync()
        case Self.actionLater:
            BackgroundSyncManager.shared.scheduleBackgroundSync()
        case Self.actionSkip:
            suppressAssets(assetIdentifiers)
        default:
            break
        }
    }
}
