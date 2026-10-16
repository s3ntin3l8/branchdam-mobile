import Foundation
import UserNotifications

/// Delivers the camera-roll notification's actions ("Import now", "Later",
/// "Skip") to `AppleCameraRollImportNotifier`.
///
/// iOS only reports a notification action to the
/// `UNUserNotificationCenter` delegate, and the app never set one, so
/// every tap on an action was silently dropped. `BranchDAMApp` installs
/// [shared] at launch (before launch finishes, so an action that
/// relaunched the app is delivered too). The center holds its delegate
/// weakly; [shared] keeps it alive.
public final class ImportNotificationDelegate: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    public static let shared = ImportNotificationDelegate()

    public typealias Handler = @Sendable (
        _ action: AppleCameraRollImportNotifier.Action,
        _ stagedBeforeUnix: Int64,
        _ done: @escaping @Sendable () -> Void
    ) -> Void

    private let handler: Handler
    private let completionDeadline: TimeInterval

    /// How long a response may hold the system's completion handler. iOS
    /// gives an action response only a short background window, and an
    /// import of many (or iCloud-only) assets can outlast it; the import
    /// is resumable (its pending_import rows survive), so the handler is
    /// released at this deadline even while the import keeps running.
    public static let defaultCompletionDeadline: TimeInterval = 20

    /// - Parameter handler: applies a routed action and calls `done` when
    ///   finished; defaults to `AppleCameraRollImportNotifier.shared`.
    public init(handler: Handler? = nil, completionDeadline: TimeInterval = ImportNotificationDelegate.defaultCompletionDeadline) {
        self.completionDeadline = completionDeadline
        self.handler = handler ?? { action, stagedBefore, done in
            AppleCameraRollImportNotifier.shared.handleAction(action, stagedBeforeUnix: stagedBefore) { _ in
                done()
            }
        }
        super.init()
    }

    /// Which import action a notification response means: one of our
    /// three action buttons on a camera-roll import notification. Tapping
    /// the notification body (or dismissing it) is not an action.
    public static func route(categoryIdentifier: String, actionIdentifier: String) -> AppleCameraRollImportNotifier.Action? {
        guard categoryIdentifier == AppleCameraRollImportNotifier.categoryIdentifier else { return nil }
        return AppleCameraRollImportNotifier.Action(actionIdentifier: actionIdentifier)
    }

    /// The posting time stored in the notification, or "no limit" for a
    /// notification posted before it carried one.
    public static func stagedBefore(userInfo: [AnyHashable: Any]) -> Int64 {
        if let number = userInfo[AppleCameraRollImportNotifier.userInfoStagedBeforeKey] as? NSNumber {
            return number.int64Value
        }
        return .max
    }

    /// Routes one response. Returns false (and does not call [completion])
    /// when it is not an import action. [completion] runs once: when the
    /// handler finishes or at [completionDeadline], whichever is first.
    @discardableResult
    public func handle(
        categoryIdentifier: String,
        actionIdentifier: String,
        userInfo: [AnyHashable: Any],
        completion: @escaping @Sendable () -> Void
    ) -> Bool {
        guard let action = Self.route(categoryIdentifier: categoryIdentifier, actionIdentifier: actionIdentifier) else {
            return false
        }
        let once = CompletionBox(completion)
        handler(action, Self.stagedBefore(userInfo: userInfo)) { once.call() }
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + completionDeadline) { once.call() }
        return true
    }

    // MARK: - UNUserNotificationCenterDelegate

    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let content = response.notification.request.content
        let finish = CompletionBox(completionHandler)
        let routed = handle(
            categoryIdentifier: content.categoryIdentifier,
            actionIdentifier: response.actionIdentifier,
            userInfo: content.userInfo,
            completion: { finish.call() }
        )
        if !routed {
            finish.call()
        }
    }

    /// Shows the banner while the app is in the foreground too, so the
    /// user can still answer it.
    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }
}

/// Carries the system's completion handler across queues and makes sure
/// it runs once, on the main queue.
private final class CompletionBox: @unchecked Sendable {
    private let lock = NSLock()
    private var handler: (() -> Void)?

    init(_ handler: @escaping () -> Void) {
        self.handler = handler
    }

    func call() {
        if Thread.isMainThread {
            take()?()
        } else {
            // Capture only the (Sendable) box, never the non-Sendable
            // handler itself; take() still guarantees a single call.
            DispatchQueue.main.async { self.take()?() }
        }
    }

    private func take() -> (() -> Void)? {
        lock.lock()
        defer { lock.unlock() }
        let h = handler
        handler = nil
        return h
    }
}
