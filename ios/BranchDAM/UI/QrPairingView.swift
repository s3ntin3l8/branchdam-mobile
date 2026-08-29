import SwiftUI

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
        let clean = uriString.replacingOccurrences(of: "branchdam://", with: "")
        let components = clean.components(separatedBy: "&")

        var dict = [String: String]()
        for comp in components {
            let pair = comp.components(separatedBy: "=")
            if pair.count == 2 {
                dict[pair[0]] = pair[1]
            }
        }

        guard let server = dict["server"], !server.isEmpty else { return nil }
        let key = dict["key"] ?? ""
        let agent = dict["agent"] ?? "iphone-companion"

        return ApplePairingConfig(serverUrl: server, apiKey: key, agentId: agent)
    }
}

public struct QrPairingView: View {
    @State private var serverUrl: String = "http://192.168.1.100:8080"
    @State private var apiKey: String = ""
    @State private var namingTemplate: String = "{yyyy}/{yyyy}-{mm}-{dd}_{camera_model}/{original_name}"
    @State private var syncOnMobileData: Bool = BackgroundSyncManager.shared.syncOnMobileData
    @State private var autoImportEnabled: Bool = AppleCameraRollImportNotifier.shared.autoImportEnabled

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

                Section(header: Text("Server Connection")) {
                    TextField("Server URL", text: $serverUrl)
                    SecureField("API Key", text: $apiKey)
                }

                Section(header: Text("Network & Ingest Constraints")) {
                    Toggle("Sync on Mobile Data / Cellular", isOn: $syncOnMobileData)
                        .onChange(of: syncOnMobileData) { newValue in
                            BackgroundSyncManager.shared.syncOnMobileData = newValue
                        }
                    Text("When disabled, sync only runs on unmetered Wi-Fi.")
                        .font(.caption)
                        .foregroundColor(.secondary)

                    Toggle("Auto-import Camera Roll", isOn: $autoImportEnabled)
                        .onChange(of: autoImportEnabled) { newValue in
                            AppleCameraRollImportNotifier.shared.autoImportEnabled = newValue
                        }
                    Text("When disabled, a notification prompts before camera roll sync.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Section(header: Text("Server Ingest Scheme")) {
                    LabeledContent("Naming Template", value: namingTemplate)
                    Text("Synchronized automatically via server handshake for POST /api/v1/agent/upload.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Section {
                    Button("Connect to Server") {
                        _ = BranchDamCoreBridge.shared.initialize(
                            dbPath: "/tmp/branchdam.db",
                            baseURL: serverUrl,
                            apiKey: apiKey,
                            agentID: "iphone-pro"
                        )
                        namingTemplate = BranchDamCoreBridge.shared.fetchNamingTemplate()
                    }
                }
            }
            .navigationTitle("Server Settings")
        }
    }
}
