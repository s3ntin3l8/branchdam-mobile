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

    public init() {}

    public var body: some View {
        NavigationStack {
            Form {
                Section(header: Text("Server Connection")) {
                    TextField("Server URL", text: $serverUrl)
                    SecureField("API Key", text: $apiKey)
                }

                Section {
                    Button("Connect to Server") {
                        _ = BranchDamCoreBridge.shared.initialize(
                            dbPath: "/tmp/branchdam.db",
                            baseURL: serverUrl,
                            apiKey: apiKey,
                            agentID: "iphone-pro"
                        )
                    }
                }
            }
            .navigationTitle("Server Pairing")
        }
    }
}
