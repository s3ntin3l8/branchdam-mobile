import SwiftUI

extension Notification.Name {
    static let branchdamSwitchToSettings = Notification.Name("com.branchdam.mobile.switchToSettings")
}

@MainActor
final class SyncStatusViewModel: ObservableObject {
    @Published var isEngineReady = false
    @Published var isSyncing = false
    @Published var lastSyncTime: Date? = nil
    @Published var syncResultMessage: String? = nil
    @Published var pendingCount: Int64 = 0
    @Published var failedCount: Int64 = 0
    @Published var activeProgress: ActiveUploadProgress? = nil
    @Published var connectionDiagnosticError: String? = nil
    @Published var isTestingConnection = false

    private static let lastSyncTimeKey = "branchdam_last_sync_time"
    private var pollingTask: Task<Void, Never>? = nil
    private var isCancelled = false

    init() {
        refresh()
    }

    func refresh() {
        isEngineReady = BranchDamCoreBridge.shared.isInitialized
        let ts = UserDefaults.standard.double(forKey: Self.lastSyncTimeKey)
        lastSyncTime = ts > 0 ? Date(timeIntervalSince1970: ts) : nil
        refreshQueueMetrics()
    }

    /// Refreshes the queue counters without blocking the main actor.
    func refreshQueueMetrics() {
        Task { await reloadQueueMetrics() }
    }

    /// Reads the queue counters from the engine on a background thread.
    /// The bridge calls block on the engine, so they are awaited rather
    /// than called from the main actor.
    func reloadQueueMetrics() async {
        guard isEngineReady else {
            pendingCount = 0
            failedCount = 0
            return
        }
        let pending = await BranchDamCoreBridge.shared.countPendingUploadsAsync() ?? 0
        let statuses = await BranchDamCoreBridge.shared.getAllMediaStatusesAsync()
        pendingCount = pending
        failedCount = Int64(statuses.values.filter { $0 == "FAILED" }.count)
    }

    func triggerSync() {
        guard !isSyncing else { return }
        let syncManager = BackgroundSyncManager.shared
        guard syncManager.shouldAllowImmediateSync(isOnCellular: syncManager.isOnCellular) else {
            syncResultMessage = "On mobile data. Turn on Sync on Mobile Data in Settings to sync now."
            return
        }
        isSyncing = true
        isCancelled = false
        syncResultMessage = nil
        startProgressPolling()

        BackgroundSyncManager.shared.triggerImmediateSync { [weak self] success in
            guard let self = self else { return }
            Task { @MainActor in
                guard !self.isCancelled else { return }
                self.stopProgressPolling()
                let now = Date()
                UserDefaults.standard.set(now.timeIntervalSince1970, forKey: Self.lastSyncTimeKey)
                self.lastSyncTime = now
                self.isSyncing = false
                self.activeProgress = nil

                await self.reloadQueueMetrics()
                guard !self.isCancelled, !self.isSyncing else { return }
                if success {
                    self.syncResultMessage = "Sync completed"
                } else if self.pendingCount > 0 {
                    self.syncResultMessage = "Sync attempt failed (check connection or server)"
                } else {
                    self.syncResultMessage = "Sync batch completed (no items uploaded)"
                }
            }
        }
    }

    func cancelSync() {
        isCancelled = true
        BranchDamCoreBridge.shared.setCancelFlag()
        stopProgressPolling()
        isSyncing = false
        activeProgress = nil
        syncResultMessage = "Sync cancelled by user"
        refreshQueueMetrics()
    }

    func retryFailedUploads() {
        Task {
            let resetCount = await BranchDamCoreBridge.shared.resetFailedUploadsAsync() ?? 0
            syncResultMessage = "Reset \(resetCount) failed uploads to pending"
            await reloadQueueMetrics()
        }
    }

    func runConnectionDiagnostic() {
        isTestingConnection = true
        connectionDiagnosticError = nil
        Task {
            let err = await BranchDamCoreBridge.shared.testConnectionDetailedAsync()
            connectionDiagnosticError = err
            isTestingConnection = false
        }
    }

    /// "Item N of M" for the active upload. The engine's itemIndex is
    /// already 1-based (engine.go passes i+1), so it is shown as is.
    nonisolated static func progressItemLabel(itemIndex: Int, totalItems: Int) -> String? {
        guard totalItems > 0 else { return nil }
        return "Item \(min(max(itemIndex, 1), totalItems)) of \(totalItems)"
    }

    private func startProgressPolling() {
        stopProgressPolling()
        pollingTask = Task.detached(priority: .utility) {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 500_000_000)
                if Task.isCancelled { break }
                let progress = BranchDamCoreBridge.shared.getActiveUploadProgress()
                let pending = BranchDamCoreBridge.shared.countPendingUploads() ?? 0
                await MainActor.run { [weak self] in
                    guard let self = self else { return }
                    self.activeProgress = progress
                    self.pendingCount = pending
                }
            }
        }
    }

    private func stopProgressPolling() {
        pollingTask?.cancel()
        pollingTask = nil
    }

    func formatSpeed(_ speedBytesPerSec: Double) -> String {
        if speedBytesPerSec <= 0 { return "0 KB/s" }
        let kb = speedBytesPerSec / 1024.0
        if kb < 1024 {
            return String(format: "%.1f KB/s", kb)
        }
        let mb = kb / 1024.0
        return String(format: "%.1f MB/s", mb)
    }

    func formatBytes(_ bytes: Int64) -> String {
        if bytes <= 0 { return "0 MB" }
        let mb = Double(bytes) / (1024.0 * 1024.0)
        return String(format: "%.1f MB", mb)
    }
}

public struct SyncStatusView: View {
    @StateObject private var viewModel = SyncStatusViewModel()

    public init() {}

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // Engine / Connection Status Card
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(spacing: 8) {
                            Circle()
                                .fill(viewModel.isEngineReady ? Color.green : Color.red)
                                .frame(width: 10, height: 10)
                            Text(viewModel.isEngineReady ? "Engine Ready" : "Engine Not Initialized")
                                .font(.body.bold())
                            Spacer()
                            Button {
                                viewModel.runConnectionDiagnostic()
                            } label: {
                                if viewModel.isTestingConnection {
                                    ProgressView()
                                        .scaleEffect(0.7)
                                } else {
                                    Text("Test Connection")
                                        .font(.caption.bold())
                                }
                            }
                            .disabled(!viewModel.isEngineReady || viewModel.isTestingConnection)
                        }

                        if !viewModel.isEngineReady {
                            Text("Configure a server in Settings to initialize the engine.")
                                .font(.caption)
                                .foregroundColor(.secondary)
                            Button("Go to Settings") {
                                NotificationCenter.default.post(name: .branchdamSwitchToSettings, object: nil)
                            }
                            .font(.caption.bold())
                            .padding(.top, 2)
                        }

                        if let diagErr = viewModel.connectionDiagnosticError {
                            Divider()
                            HStack(spacing: 6) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .foregroundColor(.orange)
                                Text(diagErr)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(.secondarySystemBackground))
                    .cornerRadius(12)

                    // Queue Metrics Card
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Label("Pending Queue", systemImage: "tray.full.fill")
                                .font(.caption.bold())
                                .foregroundColor(.secondary)
                            Spacer()
                            Text("\(viewModel.pendingCount) items")
                                .font(.headline.bold())
                                .foregroundColor(viewModel.pendingCount > 0 ? .accentColor : .primary)
                        }

                        Divider()

                        HStack {
                            Label("Last Sync", systemImage: "clock.fill")
                                .font(.caption.bold())
                                .foregroundColor(.secondary)
                            Spacer()
                            Text(lastSyncLabel)
                                .font(.subheadline)
                        }

                        if viewModel.failedCount > 0 {
                            Button {
                                viewModel.retryFailedUploads()
                            } label: {
                                Label("Retry Failed Uploads (\(viewModel.failedCount))", systemImage: "arrow.clockwise.circle")
                                    .font(.caption.bold())
                            }
                            .padding(.top, 4)
                        }

                        if let message = viewModel.syncResultMessage {
                            Divider()
                            Text(message)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(.secondarySystemBackground))
                    .cornerRadius(12)

                    // Active Upload Card (Live Progress)
                    if let prog = viewModel.activeProgress, viewModel.isSyncing {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack {
                                Label("Active Upload", systemImage: "arrow.up.circle.fill")
                                    .font(.caption.bold())
                                    .foregroundColor(.blue)
                                Spacer()
                                Text(viewModel.formatSpeed(prog.speedBytesPerSec))
                                    .font(.caption.bold())
                                    .foregroundColor(.secondary)
                            }

                            Text(prog.filename)
                                .font(.subheadline.bold())
                                .lineLimit(1)

                            ProgressView(
                                value: Double(prog.bytesSent),
                                total: Double(max(prog.totalBytes, 1))
                            )
                            .tint(.blue)

                            HStack {
                                Text("\(viewModel.formatBytes(prog.bytesSent)) / \(viewModel.formatBytes(prog.totalBytes))")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                Spacer()
                                if let itemLabel = SyncStatusViewModel.progressItemLabel(
                                    itemIndex: prog.itemIndex,
                                    totalItems: prog.totalItems
                                ) {
                                    Text(itemLabel)
                                        .font(.caption.bold())
                                        .foregroundColor(.secondary)
                                }
                            }
                        }
                        .padding()
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color(.secondarySystemBackground))
                        .cornerRadius(12)
                    }

                    // Sync Control Actions
                    if viewModel.isSyncing {
                        Button(role: .destructive, action: { viewModel.cancelSync() }) {
                            HStack {
                                Image(systemName: "xmark.circle.fill")
                                Text("Abort / Cancel Sync")
                                    .font(.headline)
                            }
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color.red.opacity(0.15))
                            .foregroundColor(.red)
                            .cornerRadius(12)
                        }
                    } else {
                        Button(action: { viewModel.triggerSync() }) {
                            HStack {
                                Image(systemName: "arrow.triangle.2.circlepath")
                                Text("Sync Now")
                                    .font(.headline)
                            }
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(viewModel.isEngineReady ? Color.accentColor : Color.accentColor.opacity(0.8))
                            .foregroundColor(.white)
                            .cornerRadius(12)
                        }
                        .disabled(!viewModel.isEngineReady)
                    }

                    Button(action: { viewModel.refresh() }) {
                        Label("Refresh Metrics", systemImage: "arrow.clockwise")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color(.secondarySystemBackground))
                            .foregroundColor(.primary)
                            .cornerRadius(12)
                    }
                }
                .padding()
            }
            .navigationTitle("Sync Status")
        }
    }

    private var lastSyncLabel: String {
        guard let date = viewModel.lastSyncTime else { return "Never" }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}
