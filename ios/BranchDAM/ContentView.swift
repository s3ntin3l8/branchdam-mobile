import SwiftUI

public struct ContentView: View {
    @ObservedObject private var otgManager = AppleOtgIngestManager.shared

    public init() {}

    public var body: some View {
        ZStack {
            TabView {
                AuditTriageView()
                    .tabItem {
                        Label("Lineage", systemImage: "point.3.filled.connected.trianglepath.dotted")
                    }

                SafeSpaceView()
                    .tabItem {
                        Label("Safe Space", systemImage: "sparkles.rectangle.stack")
                    }

                QrPairingView()
                    .tabItem {
                        Label("Settings", systemImage: "gearshape")
                    }
            }

            if case .awaitingConfirmation(let scanResult) = otgManager.state {
                Color.black.opacity(0.4)
                    .edgesIgnoringSafeArea(.all)

                AppleOtgImportConfirmationView(
                    scanResult: scanResult,
                    onConfirm: { otgManager.confirmImport(scanResult: scanResult) },
                    onDismiss: { otgManager.cancelImport() }
                )
                .background(Color(UIColor.systemBackground))
                .cornerRadius(20)
                .shadow(radius: 12)
                .padding()
            } else if case .ingesting(let progress) = otgManager.state {
                Color.black.opacity(0.4)
                    .edgesIgnoringSafeArea(.all)

                AppleOtgProgressView(
                    progress: progress,
                    onCancel: { otgManager.cancelImport() }
                )
            }
        }
    }
}

#Preview {
    ContentView()
}
