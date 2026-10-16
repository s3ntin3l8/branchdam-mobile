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
            PhotoKitObserver.shared.startObserving()
        }

        BackgroundSyncManager.shared.registerBackgroundTasks()
        // Notification actions are only delivered to the center's
        // delegate; install it before launch finishes so an action that
        // relaunched the app is delivered too.
        UNUserNotificationCenter.current().delegate = ImportNotificationDelegate.shared
        AppleCameraRollImportNotifier.shared.registerNotificationCategories()
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            if settings.authorizationStatus == .notDetermined {
                UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { _, _ in }
            }
        }
    }

    @AppStorage(BranchDamKeys.themeMode.rawValue) private var themeMode = "system"
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            WelcomeView()
                .preferredColorScheme(resolvedColorScheme)
        }
        .onChange(of: scenePhase) { _, phase in
            // The background sync task only runs if one is scheduled;
            // the task handler reschedules itself, but something has to
            // submit the first request. Leaving the app is the moment
            // pending uploads most need it.
            if phase == .background {
                BackgroundSyncManager.shared.scheduleBackgroundSync()
            }
        }
    }

    private var resolvedColorScheme: ColorScheme? {
        switch themeMode {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }
}
