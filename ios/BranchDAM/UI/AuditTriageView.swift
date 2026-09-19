import SwiftUI
import Photos

public struct AppleAuditCandidate: Identifiable, Equatable {
    public let id: String
    public let masterLocalId: String
    public let derivativeLocalId: String
    public let masterFilename: String
    public let derivativeFilename: String
    public let confidence: Double
    public let resolver: String

    public init(
        id: String,
        masterLocalId: String = "",
        derivativeLocalId: String = "",
        masterFilename: String,
        derivativeFilename: String,
        confidence: Double,
        resolver: String
    ) {
        self.id = id
        self.masterLocalId = masterLocalId
        self.derivativeLocalId = derivativeLocalId
        self.masterFilename = masterFilename
        self.derivativeFilename = derivativeFilename
        self.confidence = confidence
        self.resolver = resolver
    }
}

@MainActor
public class LineageViewModel: ObservableObject {
    @Published public var candidates: [AppleAuditCandidate] = []
    @Published public var isLoading = false
    @Published public var loadError: String? = nil
    @Published public var currentIndex: Int = 0

    public init() {}

    public func loadCandidates() async {
        isLoading = true
        loadError = nil
        do {
            candidates = try await Task.detached(priority: .userInitiated) {
                try await LineageViewModel.fetchCandidates()
            }.value
            currentIndex = 0
        } catch {
            loadError = error.localizedDescription
        }
        isLoading = false
    }

    public func confirmCandidate(_ candidate: AppleAuditCandidate) {
        _ = BranchDamCoreBridge.shared.enqueueLineageEvent(
            parentUUID: candidate.masterLocalId,
            childUUID: candidate.derivativeLocalId,
            relationshipType: "DERIVED_FROM",
            resolver: candidate.resolver,
            confidence: 1.00
        )
        advanceQueue()
    }

    public func rejectCandidate(_ candidate: AppleAuditCandidate) {
        advanceQueue()
    }

    private func advanceQueue() {
        if currentIndex < candidates.count {
            currentIndex += 1
        }
    }

    private static func fetchCandidates() async throws -> [AppleAuditCandidate] {
        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        options.fetchLimit = 200

        let assets = PHAsset.fetchAssets(with: options)
        var assetMetas: [(id: String, filename: String, dateUnix: Int64, isRaw: Bool)] = []

        assets.enumerateObjects { asset, _, _ in
            let resources = PHAssetResource.assetResources(for: asset)
            let primary = resources.first(where: { $0.type == .photo || $0.type == .video || $0.type == .alternatePhoto }) ?? resources.first
            let filename = primary?.originalFilename ?? "IMG_\(asset.localIdentifier.prefix(8)).JPG"
            let dateUnix = Int64(asset.creationDate?.timeIntervalSince1970 ?? 0)
            let isRaw = filename.uppercased().hasSuffix(".DNG") || resources.contains(where: { $0.originalFilename.uppercased().hasSuffix(".DNG") })
            assetMetas.append((id: asset.localIdentifier, filename: filename, dateUnix: dateUnix, isRaw: isRaw))
        }

        let raws = assetMetas.filter { $0.isRaw }.map { (id: $0.id, filename: $0.filename, dateUnix: $0.dateUnix) }
        let jpegs = assetMetas.filter { !$0.isRaw }.map { (id: $0.id, filename: $0.filename, dateUnix: $0.dateUnix) }

        let pairs = ApplePairDetector.findProRawPairs(
            masters: raws,
            derivatives: jpegs
        )

        var list: [AppleAuditCandidate] = []
        for (index, pair) in pairs.enumerated() {
            list.append(
                AppleAuditCandidate(
                    id: "\(index)-\(pair.masterLocalId)",
                    masterLocalId: pair.masterLocalId,
                    derivativeLocalId: pair.derivativeLocalId,
                    masterFilename: pair.masterFilename,
                    derivativeFilename: pair.derivativeFilename,
                    confidence: pair.confidence,
                    resolver: pair.resolver
                )
            )
        }
        return list
    }
}

public struct AuditTriageView: View {
    @StateObject private var viewModel = LineageViewModel()

    public init() {}

    public var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                if viewModel.isLoading {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if viewModel.currentIndex < viewModel.candidates.count {
                    let current = viewModel.candidates[viewModel.currentIndex]

                    VStack(alignment: .leading, spacing: 16) {
                        Text("Candidate \(viewModel.currentIndex + 1) of \(viewModel.candidates.count)")
                            .font(.caption)
                            .foregroundColor(.secondary)

                        RoundedRectangle(cornerRadius: 16)
                            .fill(Color(.secondarySystemBackground))
                            .frame(height: 220)
                            .overlay(
                                VStack(alignment: .leading, spacing: 12) {
                                    HStack(spacing: 6) {
                                        Image(systemName: "camera.fill")
                                            .foregroundColor(.accentColor)
                                            .font(.subheadline)
                                        Text("RAW + JPEG Pair")
                                            .font(.caption.bold())
                                            .foregroundColor(.accentColor)
                                        Spacer()
                                        Text("\(Int(current.confidence * 100))%")
                                            .font(.caption.bold())
                                            .padding(.horizontal, 8)
                                            .padding(.vertical, 3)
                                            .background(confidenceColor(current.confidence))
                                            .foregroundColor(.white)
                                            .clipShape(Capsule())
                                    }

                                    Divider()

                                    VStack(alignment: .leading, spacing: 8) {
                                        Label(current.masterFilename, systemImage: "doc.fill")
                                            .font(.subheadline.bold())
                                            .lineLimit(1)
                                        Label(current.derivativeFilename, systemImage: "photo.fill")
                                            .font(.subheadline)
                                            .foregroundColor(.secondary)
                                            .lineLimit(1)
                                    }

                                    Spacer()

                                    Text("via \(current.resolver)")
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                                .padding()
                            )

                        HStack(spacing: 16) {
                            Button(action: {
                                viewModel.rejectCandidate(current)
                            }) {
                                Label("Reject", systemImage: "xmark.circle.fill")
                                    .font(.headline)
                                    .frame(maxWidth: .infinity)
                                    .padding()
                                    .background(Color.red.opacity(0.15))
                                    .foregroundColor(.red)
                                    .cornerRadius(12)
                            }

                            Button(action: {
                                viewModel.confirmCandidate(current)
                            }) {
                                Label("Confirm", systemImage: "checkmark.circle.fill")
                                    .font(.headline)
                                    .frame(maxWidth: .infinity)
                                    .padding()
                                    .background(Color.green.opacity(0.15))
                                    .foregroundColor(.green)
                                    .cornerRadius(12)
                            }
                        }
                    }
                    .padding()
                } else {
                    ContentUnavailableView(
                        "Lineage Triage Complete",
                        systemImage: "checkmark.seal.fill",
                        description: Text("All detected RAW+JPEG pairs and edits verified.")
                    )
                }
            }
            .navigationTitle("Lineage Audit")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        Task { await viewModel.loadCandidates() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
            .task {
                await viewModel.loadCandidates()
            }
        }
    }

    private func confidenceColor(_ value: Double) -> Color {
        if value >= 0.95 { return .green }
        if value >= 0.80 { return .orange }
        return .red
    }
}
