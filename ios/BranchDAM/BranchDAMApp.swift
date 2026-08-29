import SwiftUI

@main
struct BranchDAMApp: App {

    init() {
        // Initialize Core Engine and PhotoKit observer
        let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
        let dbPath = paths[0].appendingPathComponent("branchdam_queue.db").path

        _ = BranchDamCoreBridge.shared.initialize(
            dbPath: dbPath,
            baseURL: "http://10.0.2.2:8080",
            agentID: "iphone-pro",
            version: "0.1.0"
        )

        PhotoKitObserver.shared.startObserving()
        BackgroundSyncManager.shared.registerBackgroundTasks()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
