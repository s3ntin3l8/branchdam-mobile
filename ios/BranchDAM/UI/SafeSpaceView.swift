import SwiftUI

public struct SafeSpaceView: View {
    @State private var reclaimableMB: Int = 0
    @State private var verifiedCount: Int = 0
    @State private var isReclaimed = false
    @State private var isLoading = false

    public init() {}

    public var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                VStack(spacing: 8) {
                    if isLoading {
                        ProgressView()
                            .scaleEffect(1.5)
                            .padding(.top, 40)
                    } else {
                        Text("\(reclaimableMB) MB")
                            .font(.system(size: 48, weight: .bold, design: .rounded))
                            .foregroundColor(.accentColor)
                        Text("Reclaimable Storage")
                            .font(.headline)
                        Text("\(verifiedCount) items safely archived to Tier 3 NAS")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                }
                .padding(.top, 40)

                Spacer()

                Button(action: {
                    isReclaimed = true
                }) {
                    Text(isReclaimed ? "Storage Reclaimed" : "Free Up \(reclaimableMB) MB")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(isReclaimed ? Color.green : Color.accentColor)
                        .foregroundColor(.white)
                        .cornerRadius(16)
                }
                .disabled(isReclaimed || reclaimableMB == 0)
                .padding()
            }
            .navigationTitle("Safe Space")
            .onAppear { loadCandidates() }
        }
    }

    private func loadCandidates() {
        isLoading = true
        // E.7: Load real reclaimable candidates from the engine.
        // The bridge's reclaimSafeSpace handles server re-verification.
        // For now, surface the count from the bridge.
        DispatchQueue.global(qos: .userInitiated).async {
            let result = BranchDamCoreBridge.shared.syncBatch(timeoutSecs: 30, batchSize: 1)
            DispatchQueue.main.async {
                self.isLoading = false
                // Placeholder: the real implementation would call
                // engine.checkSafeSpaceCandidates and sum eligible sizes.
                // For now, show 0 until the candidates API is wired.
                self.reclaimableMB = 0
                self.verifiedCount = 0
            }
        }
    }
}
