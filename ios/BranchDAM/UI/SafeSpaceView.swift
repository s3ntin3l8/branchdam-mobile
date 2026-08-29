import SwiftUI

public struct SafeSpaceView: View {
    @State private var estimatedSavingsMB: Int = 1450
    @State private var verifiedCount: Int = 34
    @State private var isReclaimed = false

    public init() {}

    public var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                VStack(spacing: 8) {
                    Text("\(estimatedSavingsMB) MB")
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
                    Text(isReclaimed ? "Storage Reclaimed ✓" : "Free Up \(estimatedSavingsMB) MB")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(isReclaimed ? Color.green : Color.accentColor)
                        .foregroundColor(.white)
                        .cornerRadius(16)
                }
                .disabled(isReclaimed)
                .padding()
            }
            .navigationTitle("Safe Space")
        }
    }
}
