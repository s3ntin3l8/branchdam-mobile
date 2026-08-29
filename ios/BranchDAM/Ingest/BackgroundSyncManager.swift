import Foundation
import BackgroundTasks

public class BackgroundSyncManager {
    public static let shared = BackgroundSyncManager()
    public static let syncTaskId = "com.branchdam.mobile.sync"

    private var urlSession: URLSession?

    private init() {
        let config = URLSessionConfiguration.background(withIdentifier: "com.branchdam.mobile.bg-uploader")
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        self.urlSession = URLSession(configuration: config)
    }

    public func registerBackgroundTasks() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.syncTaskId, using: nil) { task in
            guard let processingTask = task as? BGProcessingTask else { return }
            self.handleBackgroundSync(task: processingTask)
        }
    }

    public func scheduleBackgroundSync(requiresExternalPower: Bool = false) {
        let request = BGProcessingTaskRequest(identifier: Self.syncTaskId)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = requiresExternalPower
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60) // 15 min interval

        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Task scheduling logging
        }
    }

    private func handleBackgroundSync(task: BGProcessingTask) {
        task.expirationHandler = {
            // Cancel long transfers gracefully on task expiry
        }

        DispatchQueue.global(qos: .background).async {
            let result = BranchDamCoreBridge.shared.syncBatch(timeoutSecs: 120, batchSize: 10)
            task.setTaskCompleted(success: result.uploaded >= 0)
            self.scheduleBackgroundSync()
        }
    }
}
