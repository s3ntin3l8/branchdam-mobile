import Foundation
import BackgroundTasks

public class BackgroundSyncManager {
    public static let shared = BackgroundSyncManager()
    public static let syncTaskId = "com.branchdam.mobile.sync"
    /// Backwards-compatible alias of
    /// [BranchDamKeys.syncOnMobileData.rawValue] retained for any
    /// external callers that depended on the previous static-let
    /// shape. New code should reference `BranchDamKeys.syncOnMobileData`
    /// directly.
    public static var keySyncOnMobileData: String { BranchDamKeys.syncOnMobileData.rawValue }

    public var syncOnMobileData: Bool {
        get {
            return UserDefaults.standard.bool(forKey: BranchDamKeys.syncOnMobileData.rawValue)
        }
        set {
            UserDefaults.standard.set(newValue, forKey: BranchDamKeys.syncOnMobileData.rawValue)
            setupUrlSession()
        }
    }

    private var urlSession: URLSession?

    private static let defaultBatchSize = 10
    private static let defaultTimeoutSecs = 120
    private static let defaultIntervalMinutes = 15

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

    private func readBatchSize() -> Int {
        let stored = UserDefaults.standard.object(forKey: BranchDamKeys.uploadBatchSize.rawValue) as? Int
        return stored ?? Self.defaultBatchSize
    }

    private func readTimeoutSecs() -> Int {
        let stored = UserDefaults.standard.object(forKey: BranchDamKeys.syncTimeoutSecs.rawValue) as? Int
        return stored ?? Self.defaultTimeoutSecs
    }

    private func readIntervalMinutes() -> Int {
        let stored = UserDefaults.standard.object(forKey: BranchDamKeys.syncIntervalMinutes.rawValue) as? Int
        return stored ?? Self.defaultIntervalMinutes
    }

    private func readSyncOnBatteryOnly() -> Bool {
        return UserDefaults.standard.bool(forKey: BranchDamKeys.syncOnBatteryOnly.rawValue)
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

        let timeout = readTimeoutSecs()
        let batch = readBatchSize()
        DispatchQueue.global(qos: .userInitiated).async {
            let result = BranchDamCoreBridge.shared.syncBatch(timeoutSecs: timeout, batchSize: batch)
            completion?(result.uploaded > 0 || result.eventsSent > 0)
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
        let batteryOnly = readSyncOnBatteryOnly()
        request.requiresExternalPower = requiresExternalPower || !batteryOnly
        let intervalMinutes = readIntervalMinutes()
        request.earliestBeginDate = Date(timeIntervalSinceNow: TimeInterval(intervalMinutes * 60))

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

        let timeout = readTimeoutSecs()
        let batch = readBatchSize()
        DispatchQueue.global(qos: .background).async {
            let result = BranchDamCoreBridge.shared.syncBatch(timeoutSecs: timeout, batchSize: batch)
            completionLock.lock()
            defer { completionLock.unlock() }
            guard !completed else { return }
            completed = true
            task.setTaskCompleted(success: true)
            self.scheduleBackgroundSync()
        }
    }
}
