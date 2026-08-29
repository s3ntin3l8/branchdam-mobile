import SwiftUI

public struct ContentView: View {
    public init() {}

    public var body: some View {
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
    }
}

#Preview {
    ContentView()
}
