import SwiftUI

extension Notification.Name {
    static let branchdamSwitchToSettings = Notification.Name("com.branchdam.mobile.switchToSettings")
}

class SyncStatusViewModel: ObservableObject {
    @Published var isEngineReady = false
    @Published var isSyncing = false
    @Published var lastSyncTime: Date? = nil
    @Published var syncResultMessage: String? = nil

    private static let lastSyncTimeKey = "branchdam_last_sync_time"

    init() {
        refresh()
    }

    func refresh() {
        isEngineReady = BranchDamCoreBridge.shared.isInitialized
        let ts = UserDefaults.standard.double(forKey: Self.lastSyncTimeKey)
        lastSyncTime = ts > 0 ? Date(timeIntervalSince1970: ts) : nil
    }

    func triggerSync() {
        guard !isSyncing else { return }
        isSyncing = true
        syncResultMessage = nil
        BackgroundSyncManager.shared.triggerImmediateSync { [weak self] success in
            DispatchQueue.main.async {
                let now = Date()
                UserDefaults.standard.set(now.timeIntervalSince1970, forKey: Self.lastSyncTimeKey)
                self?.lastSyncTime = now
                self?.isSyncing = false
                self?.syncResultMessage = success ? "Sync completed" : "Sync completed (no new items)"
            }
        }
    }
}

public struct SyncStatusView: View {
    @StateObject private var viewModel = SyncStatusViewModel()

    public init() {}

    public var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                // Engine / connection card
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 8) {
                        Circle()
                            .fill(viewModel.isEngineReady ? Color.green : Color.red)
                            .frame(width: 10, height: 10)
                        Text(viewModel.isEngineReady ? "Engine ready" : "Engine not initialized")
                            .font(.body)
                        Spacer()
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
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemBackground))
                .cornerRadius(12)

                // Sync state card
                VStack(alignment: .leading, spacing: 10) {
                    Label("Sync State", systemImage: "arrow.triangle.2.circlepath")
                        .font(.caption.bold())
                        .foregroundColor(.secondary)
                    Text(viewModel.isSyncing ? "Running" : "Idle")
                        .font(.body)

                    Divider()

                    Label("Last Sync", systemImage: "clock")
                        .font(.caption.bold())
                        .foregroundColor(.secondary)
                    Text(lastSyncLabel)
                        .font(.body)

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

                Button(action: { viewModel.triggerSync() }) {
                    HStack {
                        if viewModel.isSyncing {
                            ProgressView()
                                .scaleEffect(0.8)
                                .tint(.white)
                        }
                        Text(viewModel.isSyncing ? "Syncing..." : "Sync Now")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                    }
                    .padding()
                    .background(viewModel.isEngineReady && !viewModel.isSyncing
                        ? Color.accentColor
                        : Color.secondary.opacity(0.3))
                    .foregroundColor(.white)
                    .cornerRadius(12)
                }
                .disabled(!viewModel.isEngineReady || viewModel.isSyncing)

                Button(action: { viewModel.refresh() }) {
                    Text("Refresh")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color(.secondarySystemBackground))
                        .foregroundColor(.primary)
                        .cornerRadius(12)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(Color.secondary.opacity(0.3), lineWidth: 1)
                        )
                }

                Spacer()
            }
            .padding()
            .navigationTitle("Sync Status")
        }
    }

    private var lastSyncLabel: String {
        guard let date = viewModel.lastSyncTime else { return "Never synced" }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}
