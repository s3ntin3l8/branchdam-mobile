import SwiftUI
import UserNotifications
import Photos

@main
struct BranchDAMApp: App {

    init() {
        let authorizationStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)

        // Only initialize engine and start observing if camera-roll
        // access has already been granted. WelcomeView handles the
        // first-launch permission request flow.
        if authorizationStatus == .authorized || authorizationStatus == .limited {
            Self.startEngineIfNeeded()
        }

        BackgroundSyncManager.shared.registerBackgroundTasks()
        AppleCameraRollImportNotifier.shared.registerNotificationCategories()
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            if settings.authorizationStatus == .notDetermined {
                UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { _, _ in }
            }
        }
    }

    var body: some Scene {
        WindowGroup {
            WelcomeView()
        }
    }

    /// E.1: Idempotent engine + observer startup. Safe to call from both
    /// the app init (pre-authorized path) and the WelcomeView grant path.
    static func startEngineIfNeeded() {
        guard !BranchDamCoreBridge.shared.isInitialized else { return }
        let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
        let dbPath = paths[0].appendingPathComponent("branchdam_queue.db").path

        _ = BranchDamCoreBridge.shared.initialize(
            dbPath: dbPath,
            baseURL: "",
            agentID: "iphone-pro",
            version: "0.1.0"
        )

        PhotoKitObserver.shared.startObserving()
    }
}
