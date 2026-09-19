import SwiftUI

extension Notification.Name {
    static let branchdamSwitchToSettings = Notification.Name("com.branchdam.mobile.switchToSettings")
}

@MainActor
class SyncStatusViewModel: ObservableObject {
    @Published var isEngineReady = false
    @Published var isSyncing = false
    @Published var lastSyncTime: Date? = nil
    @Published var syncResultMessage: String? = nil
    @Published var pendingCount: Int64 = 0
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

    func refreshQueueMetrics() {
        if isEngineReady {
            pendingCount = BranchDamCoreBridge.shared.countPendingUploads()
        } else {
            pendingCount = 0
        }
    }

    func triggerSync() {
        guard !isSyncing else { return }
        isSyncing = true
        isCancelled = false
        syncResultMessage = nil
        startProgressPolling()

        BackgroundSyncManager.shared.triggerImmediateSync { [weak self] success in
            Task { @MainActor in
                guard let self = self, !self.isCancelled else { return }
                self.stopProgressPolling()
                let now = Date()
                UserDefaults.standard.set(now.timeIntervalSince1970, forKey: Self.lastSyncTimeKey)
                self.lastSyncTime = now
                self.isSyncing = false
                self.activeProgress = nil
                self.syncResultMessage = success ? "Sync completed" : "Sync batch completed"
                self.refreshQueueMetrics()
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
        let resetCount = BranchDamCoreBridge.shared.resetFailedUploads()
        syncResultMessage = "Reset \(resetCount) failed uploads to pending"
        refreshQueueMetrics()
    }

    func runConnectionDiagnostic() {
        isTestingConnection = true
        connectionDiagnosticError = nil
        DispatchQueue.global(qos: .userInitiated).async {
            let err = BranchDamCoreBridge.shared.testConnectionDetailed()
            DispatchQueue.main.async {
                self.connectionDiagnosticError = err
                self.isTestingConnection = false
            }
        }
    }

    private func startProgressPolling() {
        stopProgressPolling()
        pollingTask = Task.detached(priority: .utility) { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 500_000_000)
                if Task.isCancelled { break }
                let isReady = await MainActor.run { self?.isEngineReady ?? false }
                if isReady {
                    let progress = BranchDamCoreBridge.shared.getActiveUploadProgress()
                    let pending = BranchDamCoreBridge.shared.countPendingUploads()
                    await MainActor.run {
                        self?.activeProgress = progress
                        self?.pendingCount = pending
                    }
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

                        if viewModel.pendingCount > 0 {
                            Button {
                                viewModel.retryFailedUploads()
                            } label: {
                                Label("Retry Failed Uploads", systemImage: "arrow.clockwise.circle")
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
                                if prog.totalItems > 0 {
                                    Text("Item \(prog.itemIndex + 1) of \(prog.totalItems)")
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
                            .background(viewModel.isEngineReady ? Color.accentColor : Color.secondary.opacity(0.3))
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
