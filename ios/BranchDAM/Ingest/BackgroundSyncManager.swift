import Foundation
import Network
@preconcurrency import BackgroundTasks

public class BackgroundSyncManager: @unchecked Sendable {
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
        }
    }

    /// Runs one sync batch through the engine. A seam (internal, so only
    /// `@testable` tests can swap it) so a test can run a batch without
    /// the native engine. Read once per sync, on the calling thread.
    var runSyncBatch: @Sendable (_ timeoutSecs: Int32, _ batchSize: Int32) -> (uploaded: Int32, eventsSent: Int32) = {
        timeoutSecs, batchSize in
        BranchDamCoreBridge.shared.syncBatch(timeoutSecs: timeoutSecs, batchSize: batchSize)
    }

    /// Runs after every sync batch, immediate or background: reaps the
    /// staged card copies whose uploads have completed, as Android does
    /// after each batch. Injectable so a test can assert the call site,
    /// not just the helper. Read once per sync, on the calling thread.
    var afterBatchSweep: @Sendable () -> Void = {
        _ = AppleOtgStaging.sweepAfterSync()
    }

    private static let defaultBatchSize = 10
    private static let defaultTimeoutSecs = 120
    private static let defaultIntervalMinutes = 15

    /// Watches the current network path so the "Sync on Mobile Data"
    /// setting is enforced on what the device is actually using.
    private let pathMonitor = NWPathMonitor()
    private let pathMonitorQueue = DispatchQueue(label: "com.branchdam.mobile.path-monitor", qos: .utility)
    private let pathLock = NSLock()
    private var pathIsExpensive = false
    private var pathUsesCellular = false

    // Uploads run inside the Go engine (its own net/http client, driven
    // by syncBatch); there is no background URLSession. The one this
    // class used to create was never used for any transfer.
    private init() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let expensive = path.isExpensive
            let cellular = path.usesInterfaceType(.cellular)
            self?.updatePath(isExpensive: expensive, usesCellular: cellular)
        }
        pathMonitor.start(queue: pathMonitorQueue)
    }

    private func updatePath(isExpensive: Bool, usesCellular: Bool) {
        pathLock.lock()
        pathIsExpensive = isExpensive
        pathUsesCellular = usesCellular
        pathLock.unlock()
    }

    /// Pure decision: whether a network path counts as mobile data. An
    /// expensive path covers cellular and a Wi-Fi hotspot shared from a
    /// phone, both of which spend the user's data plan.
    public static func isMobileData(isExpensive: Bool, usesCellular: Bool) -> Bool {
        isExpensive || usesCellular
    }

    /// Whether the device is on mobile data right now, as last reported by
    /// NWPathMonitor. Before the first report this is false.
    public var isOnCellular: Bool {
        pathLock.lock()
        defer { pathLock.unlock() }
        return Self.isMobileData(isExpensive: pathIsExpensive, usesCellular: pathUsesCellular)
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

    /// Runs one sync batch now. [isOnCellular] defaults to the measured
    /// network path; tests pass it explicitly.
    public func triggerImmediateSync(isOnCellular: Bool? = nil, completion: (@Sendable (Bool) -> Void)? = nil) {
        guard shouldAllowImmediateSync(isOnCellular: isOnCellular ?? self.isOnCellular) else {
            AppleSyncLogger.shared.log("Sync skipped: on mobile data and Sync on Mobile Data is off")
            completion?(false)
            return
        }

        let timeout = Int32(readTimeoutSecs())
        let batch = Int32(readBatchSize())
        let runBatch = runSyncBatch
        let sweep = afterBatchSweep
        DispatchQueue.global(qos: .userInitiated).async {
            let result = runBatch(timeout, batch)
            AppleSyncLogger.shared.logSync(uploaded: result.uploaded, events: result.eventsSent)
            sweep()
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
        let tracker = SyncTaskTracker()

        task.expirationHandler = {
            BranchDamCoreBridge.shared.setCancelFlag()
            tracker.completeOnce {
                task.setTaskCompleted(success: false)
            }
        }

        // BGProcessingTaskRequest can only ask for "any network"; enforce
        // the mobile-data setting here and try again at the next window.
        guard shouldAllowImmediateSync(isOnCellular: isOnCellular) else {
            AppleSyncLogger.shared.log("Background sync skipped: on mobile data and Sync on Mobile Data is off")
            tracker.completeOnce {
                task.setTaskCompleted(success: true)
                self.scheduleBackgroundSync()
            }
            return
        }

        let timeout = Int32(readTimeoutSecs())
        let batch = Int32(readBatchSize())
        let runBatch = runSyncBatch
        let sweep = afterBatchSweep
        DispatchQueue.global(qos: .background).async {
            let result = runBatch(timeout, batch)
            AppleSyncLogger.shared.logSync(uploaded: result.uploaded, events: result.eventsSent)
            sweep()
            tracker.completeOnce {
                task.setTaskCompleted(success: true)
                self.scheduleBackgroundSync()
            }
        }
    }
}

private final class SyncTaskTracker: @unchecked Sendable {
    private let lock = NSLock()
    private var completed = false

    func completeOnce(action: () -> Void) {
        lock.lock()
        defer { lock.unlock() }
        guard !completed else { return }
        completed = true
        action()
    }
}
