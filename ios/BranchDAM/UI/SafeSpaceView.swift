import Photos
import SwiftUI

public struct SafeSpaceView: View {
    @State private var reclaimableMB: Int = 0
    @State private var verifiedCount: Int = 0
    @State private var isReclaimed = false
    @State private var isProcessing = false
    @State private var candidateList: [SafeSpaceCandidate] = []

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
                    reclaim()
                }) {
                    Text(isReclaimed ? "Storage Reclaimed" : isProcessing ? "Reclaiming…" : "Free Up \(reclaimableMB) MB")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(isReclaimed ? Color.green : Color.accentColor)
                        .foregroundColor(.white)
                        .cornerRadius(16)
                }
                .disabled(isReclaimed || isProcessing || reclaimableMB == 0)
                .padding()
            }
            .navigationTitle("Safe Space")
            .onAppear { loadCandidates() }
        }
    }

    private func reclaim() {
        guard !candidateList.isEmpty else { return }
        isProcessing = true
        let candidates = candidateList
        Task.detached(priority: .userInitiated) {
            let report = AppleSafeSpaceManager.reclaimSafeSpace(candidates: candidates)
            await MainActor.run {
                self.isProcessing = false
                if report.reclaimedCount > 0 {
                    self.isReclaimed = true
                }
                self.loadCandidates()
            }
        }
    }

    private func loadCandidates() {
        Task.detached(priority: .userInitiated) {
            let (foundCandidates, verifiedNum, resultMB) = fetchCandidates()
            await MainActor.run {
                self.candidateList = foundCandidates
                self.verifiedCount = verifiedNum
                self.reclaimableMB = resultMB
            }
        }
    }

    nonisolated private func fetchCandidates() -> (candidates: [SafeSpaceCandidate], verifiedNum: Int, resultMB: Int) {
        var foundCandidates = [SafeSpaceCandidate]()
        let fetchResult = PHAsset.fetchAssets(with: nil)
        var totalBytes: Int64 = 0
        var verifiedNum = 0

        fetchResult.enumerateObjects { asset, _, _ in
            let id = "ph://\(asset.localIdentifier)"
            if BranchDamCoreBridge.shared.isMediaOffloaded(localID: id) {
                let estimatedSize = Int64(asset.pixelWidth * asset.pixelHeight * 3 / 4)
                let size = estimatedSize > 0 ? estimatedSize : Int64(5 * 1024 * 1024)
                foundCandidates.append(SafeSpaceCandidate(localId: id, sizeBytes: size, isVerified: true))
                totalBytes += size
                verifiedNum += 1
            }
        }

        let resultMB = Int(totalBytes / (1024 * 1024))
        return (foundCandidates, verifiedNum, resultMB)
    }
}
