import SwiftUI

// MARK: - Models

public struct ApplePairingConfig: Equatable {
    public let serverUrl: String
    public let apiKey: String
    public let agentId: String

    public init(serverUrl: String, apiKey: String, agentId: String) {
        self.serverUrl = serverUrl
        self.apiKey = apiKey
        self.agentId = agentId
    }
}

public class AppleQrParser {
    public static func parse(uriString: String) -> ApplePairingConfig? {
        guard uriString.hasPrefix("branchdam://") else { return nil }
        var body = uriString.replacingOccurrences(of: "branchdam://", with: "")
        if body.hasPrefix("?") { body.removeFirst() }

        var dict = [String: String]()
        for comp in body.components(separatedBy: "&") where !comp.isEmpty {
            let pair = comp.components(separatedBy: "=")
            guard pair.count == 2,
                  let decoded = pair[1].removingPercentEncoding else {
                return nil
            }
            dict[pair[0]] = decoded
        }

        guard let server = dict["server"], !server.isEmpty else { return nil }
        let key = dict["key"] ?? ""
        let agent = dict["agent"].flatMap { $0.isEmpty ? nil : $0 } ?? "iphone-companion"

        return ApplePairingConfig(serverUrl: server, apiKey: key, agentId: agent)
    }
}

// MARK: - Root Settings View

public struct QrPairingView: View {
    @State private var showShareSheet = false

    public init() {}

    public var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        Spacer()
                        BrandMonogramView(size: 64)
                        Spacer()
                    }
                    .padding(.vertical, 8)
                }

                Section {
                    NavigationLink {
                        ConnectionSettingsView()
                    } label: {
                        Label("Connection", systemImage: "link")
                    }
                    NavigationLink {
                        SyncSettingsView()
                    } label: {
                        Label("Sync", systemImage: "arrow.triangle.2.circlepath")
                    }
                    NavigationLink {
                        ImportSettingsView()
                    } label: {
                        Label("Import", systemImage: "square.and.arrow.down")
                    }
                    NavigationLink {
                        AppearanceSettingsView()
                    } label: {
                        Label("Appearance", systemImage: "paintpalette")
                    }
                    NavigationLink {
                        AdvancedSettingsView()
                    } label: {
                        Label("Advanced", systemImage: "slider.horizontal.3")
                    }
                }

                Section(header: Text("Diagnostics")) {
                    Button("Share Diagnostic Log") {
                        if !FileManager.default.fileExists(atPath: AppleSyncLogger.shared.logFileURL.path) {
                            let placeholder = "No sync events recorded yet.\n"
                            try? placeholder.write(to: AppleSyncLogger.shared.logFileURL, atomically: true, encoding: .utf8)
                        }
                        showShareSheet = true
                    }
                }

                Section {
                    LabeledContent("Version", value: appVersion)
                        .foregroundColor(.secondary)
                }
            }
            .navigationTitle("Settings")
        }
        .sheet(isPresented: $showShareSheet) {
            ShareSheet(items: [AppleSyncLogger.shared.logFileURL])
        }
    }

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
    }

    // E.9: Documents-directory DB path. Never /tmp.
    // Exposed as `internal` so the unit test target can verify
    // the path is under the documents directory, not /tmp.
    func defaultDBPath() -> String {
        let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
        return paths[0].appendingPathComponent("branchdam_queue.db").path
    }
}

// MARK: - Connection Settings

private struct ConnectionSettingsView: View {
    @AppStorage("branchdam_server_url") private var serverUrl = ""
    @State private var apiKey = ""
    @State private var namingTemplate = ""
    @State private var isConnected = false
    @State private var showQrScanner = false

    var body: some View {
        Form {
            Section(header: Text("Server")) {
                TextField("Server URL", text: $serverUrl)
                    .keyboardType(.URL)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                SecureField("API Key", text: $apiKey)
                Button("Scan QR Code") { showQrScanner = true }
            }

            Section(header: Text("Status")) {
                HStack(spacing: 8) {
                    Circle()
                        .fill(isConnected ? Color.green : Color.red)
                        .frame(width: 8, height: 8)
                    Text(isConnected ? "Connected" : "Not connected")
                }
                if !namingTemplate.isEmpty {
                    LabeledContent("Naming Template", value: namingTemplate)
                        .font(.caption)
                }
            }

            Section {
                Button("Save and Connect") {
                    connectToServer(url: serverUrl, key: apiKey, agentId: "iphone-pro")
                }
                .disabled(serverUrl.isEmpty)
            }
        }
        .navigationTitle("Connection")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            isConnected = BranchDamCoreBridge.shared.isInitialized
            apiKey = AppleKeychain.shared.apiKey ?? ""
            if isConnected {
                namingTemplate = BranchDamCoreBridge.shared.fetchNamingTemplate()
            }
        }
        .sheet(isPresented: $showQrScanner) {
            QrScanSheet { config in
                serverUrl = config.serverUrl
                apiKey = config.apiKey
                connectToServer(url: config.serverUrl, key: config.apiKey, agentId: config.agentId)
            }
        }
    }

    // T2-5: persist the API key into the iOS keychain before the bridge
    // reads it. An empty field clears any previously-stored key so the
    // user can rotate without re-pairing.
    private func connectToServer(url: String, key: String, agentId: String) {
        guard !url.isEmpty else { return }
        if key.isEmpty {
            AppleKeychain.shared.apiKey = nil
        } else {
            AppleKeychain.shared.apiKey = key
        }
        let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
        let dbPath = paths[0].appendingPathComponent("branchdam_queue.db").path
        _ = BranchDamCoreBridge.shared.initialize(
            dbPath: dbPath,
            baseURL: url,
            agentID: agentId
        )
        isConnected = BranchDamCoreBridge.shared.isInitialized
        namingTemplate = BranchDamCoreBridge.shared.fetchNamingTemplate()
    }
}

// MARK: - Sync Settings

private struct SyncSettingsView: View {
    @State private var syncOnMobileData = BackgroundSyncManager.shared.syncOnMobileData
    @AppStorage(BranchDamKeys.syncIntervalMinutes.rawValue) private var syncIntervalMinutes = 15
    @AppStorage(BranchDamKeys.syncOnBatteryOnly.rawValue) private var syncOnBatteryOnly = false

    var body: some View {
        Form {
            Section {
                Toggle("Sync on Mobile Data / Cellular", isOn: $syncOnMobileData)
                    .onChange(of: syncOnMobileData) { _, newValue in
                        BackgroundSyncManager.shared.syncOnMobileData = newValue
                    }
                Text("Allow uploads over cellular connection.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Section {
                Picker("Background Sync Interval", selection: $syncIntervalMinutes) {
                    Text("15 min").tag(15)
                    Text("30 min").tag(30)
                    Text("1 hour").tag(60)
                    Text("2 hours").tag(120)
                }
                Text("How often to check for new media.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Section {
                Toggle("Sync on Low Battery", isOn: $syncOnBatteryOnly)
                Text("Allow background sync even when battery is low.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .navigationTitle("Sync")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Import Settings

private struct ImportSettingsView: View {
    @State private var autoImportEnabled = AppleCameraRollImportNotifier.shared.autoImportEnabled

    var body: some View {
        Form {
            Section {
                Toggle("Auto-import Camera Roll", isOn: $autoImportEnabled)
                    .onChange(of: autoImportEnabled) { _, newValue in
                        AppleCameraRollImportNotifier.shared.autoImportEnabled = newValue
                    }
                Text("Automatically enqueue new photos for upload.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .navigationTitle("Import")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Appearance Settings

private struct AppearanceSettingsView: View {
    @AppStorage(BranchDamKeys.themeMode.rawValue) private var themeMode = "system"

    var body: some View {
        Form {
            Section(header: Text("Color Scheme")) {
                Picker("Theme", selection: $themeMode) {
                    Text("Follow System").tag("system")
                    Text("Light").tag("light")
                    Text("Dark").tag("dark")
                }
                .pickerStyle(.inline)
                .labelsHidden()
            }
        }
        .navigationTitle("Appearance")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Advanced Settings

private struct AdvancedSettingsView: View {
    @AppStorage(BranchDamKeys.uploadBatchSize.rawValue) private var uploadBatchSize = 10
    @AppStorage(BranchDamKeys.syncTimeoutSecs.rawValue) private var syncTimeoutSecs = 120
    @AppStorage(BranchDamKeys.observerDebounceMs.rawValue) private var observerDebounceMs = 500

    var body: some View {
        Form {
            Section {
                Picker("Upload Batch Size", selection: $uploadBatchSize) {
                    Text("5 items").tag(5)
                    Text("10 items").tag(10)
                    Text("20 items").tag(20)
                    Text("50 items").tag(50)
                }
                Text("Items processed per sync cycle.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Section {
                Picker("Sync Timeout", selection: $syncTimeoutSecs) {
                    Text("60 s").tag(60)
                    Text("120 s").tag(120)
                    Text("300 s").tag(300)
                }
                Text("Max seconds to wait for a sync batch.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Section {
                Picker("Observer Debounce", selection: $observerDebounceMs) {
                    Text("250 ms").tag(250)
                    Text("500 ms").tag(500)
                    Text("1000 ms").tag(1000)
                }
                Text("Delay before processing camera roll changes.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .navigationTitle("Advanced")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - ShareSheet

private struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
