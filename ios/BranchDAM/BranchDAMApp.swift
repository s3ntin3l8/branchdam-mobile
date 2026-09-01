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
            BranchDamCoreBridge.shared.startEngineIfNeeded()
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
}
