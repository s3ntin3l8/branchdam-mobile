import SwiftUI

public struct SafeSpaceView: View {
    @State private var reclaimableMB: Int = 0
    @State private var verifiedCount: Int = 0
    @State private var isReclaimed = false

    public init() {}

    public var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                VStack(spacing: 8) {
                    Text("\(reclaimableMB) MB")
                        .font(.system(size: 48, weight: .bold, design: .rounded))
                        .foregroundColor(.accentColor)
                    Text("Reclaimable Storage")
                        .font(.headline)
                    Text("\(verifiedCount) items safely archived to Tier 3 NAS")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
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
        // E.7: Show 0 until the real candidates API is wired.
        // A UI onAppear must not trigger syncBatch (which uploads media).
        // The bridge's reclaimSafeSpace handles server re-verification.
        reclaimableMB = 0
        verifiedCount = 0
    }
}
