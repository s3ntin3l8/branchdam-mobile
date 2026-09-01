import Foundation
import BackgroundTasks

public class BackgroundSyncManager {
    public static let shared = BackgroundSyncManager()
    public static let syncTaskId = "com.branchdam.mobile.sync"
    public static let keySyncOnMobileData = "branchdam_sync_on_mobile_data"

    public var syncOnMobileData: Bool {
        get {
            return UserDefaults.standard.bool(forKey: Self.keySyncOnMobileData)
        }
        set {
            UserDefaults.standard.set(newValue, forKey: Self.keySyncOnMobileData)
            setupUrlSession()
        }
    }

    private var urlSession: URLSession?

    private init() {
        setupUrlSession()
    }

    private func setupUrlSession() {
        let config = URLSessionConfiguration.background(withIdentifier: "com.branchdam.mobile.bg-uploader")
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        config.allowsCellularAccess = syncOnMobileData
        self.urlSession = URLSession(configuration: config)
    }

    public func shouldAllowImmediateSync(isOnCellular: Bool) -> Bool {
        if !isOnCellular {
            return true // Wi-Fi is always permitted
        }
        return syncOnMobileData // Cellular only if user opted in
    }

    public func triggerImmediateSync(isOnCellular: Bool = false, completion: ((Bool) -> Void)? = nil) {
        guard shouldAllowImmediateSync(isOnCellular: isOnCellular) else {
            completion?(false)
            return
        }

        DispatchQueue.global(qos: .userInitiated).async {
            let result = BranchDamCoreBridge.shared.syncBatch(timeoutSecs: 60, batchSize: 10)
            completion?(result.uploaded >= 0)
        }
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
        // E.4: Set the Go engine's cancel flag so in-flight HTTP transfers
        // stop promptly when iOS reclaims the background time.
        var completed = false
        let completionLock = NSLock()

        task.expirationHandler = {
            BranchDamCoreBridge.shared.setCancelFlag()
            completionLock.lock()
            defer { completionLock.unlock() }
            guard !completed else { return }
            completed = true
            task.setTaskCompleted(success: false)
        }

        DispatchQueue.global(qos: .background).async {
            let result = BranchDamCoreBridge.shared.syncBatch(timeoutSecs: 120, batchSize: 10)
            completionLock.lock()
            defer { completionLock.unlock() }
            guard !completed else { return }
            completed = true
            task.setTaskCompleted(success: result.uploaded >= 0)
            self.scheduleBackgroundSync()
        }
    }
}
