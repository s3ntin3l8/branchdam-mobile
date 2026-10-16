import SwiftUI
import Photos
#if canImport(branchdam)
import branchdam
#endif

public struct AppleAuditCandidate: Identifiable, Equatable, Sendable {
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

/// The engine calls the lineage review screen needs; a protocol so the view
/// model can be tested without the gomobile framework.
public protocol LineageReviewEngine: Sendable {
    /// Queues the confirmed edge at `confidence` (never raised). Returns the
    /// event UUID, or nil when the decision could not be saved.
    func confirm(parentLocalId: String, childLocalId: String, relationshipType: String,
                 resolver: String, confidence: Double) -> String?
    /// Persists the rejection in the core database. False on failure.
    func reject(parentLocalId: String, childLocalId: String, relationshipType: String,
                resolver: String, confidence: Double) -> Bool
    /// Keys (`LineageReviewPolicy.pairKey`) of every decided pair, or nil
    /// when they can't be read.
    func decidedPairKeys() -> Set<String>?
}

/// `LineageReviewEngine` over the gomobile binding. When the engine is not
/// open every call fails: nothing is confirmed, rejected or listed behind
/// the user's back.
public struct CoreLineageReviewEngine: LineageReviewEngine {
    public init() {}

    public func confirm(parentLocalId: String, childLocalId: String, relationshipType: String,
                        resolver: String, confidence: Double) -> String? {
        #if canImport(branchdam)
        guard BranchDamCoreBridge.shared.isInitialized else { return nil }
        do {
            let id = try branchdam.bindingConfirmLineageEdge(
                parentLocalId, childLocalId, relationshipType, resolver, confidence)
            return id.isEmpty ? nil : id
        } catch {
            NSLog("confirmLineageEdge failed: %@", String(describing: error))
            return nil
        }
        #else
        return nil
        #endif
    }

    public func reject(parentLocalId: String, childLocalId: String, relationshipType: String,
                       resolver: String, confidence: Double) -> Bool {
        #if canImport(branchdam)
        guard BranchDamCoreBridge.shared.isInitialized else { return false }
        do {
            try branchdam.bindingRejectLineageEdge(
                parentLocalId, childLocalId, relationshipType, resolver, confidence)
            return true
        } catch {
            NSLog("rejectLineageEdge failed: %@", String(describing: error))
            return false
        }
        #else
        return false
        #endif
    }

    public func decidedPairKeys() -> Set<String>? {
        #if canImport(branchdam)
        guard BranchDamCoreBridge.shared.isInitialized else { return nil }
        do {
            return LineageReviewPolicy.parseDecidedKeys(try branchdam.bindingListLineageReviews())
        } catch {
            NSLog("listLineageReviews failed: %@", String(describing: error))
            return nil
        }
        #else
        return nil
        #endif
    }
}

/// The lineage review screen: pairs the device found below confidence 1.00.
/// They are never sent on their own; the user confirms or rejects each one
/// here. Both decisions are stored in the core database, so a decided pair
/// is not offered again, even after a restart. A confirmed edge keeps the
/// resolver's confidence: confirming is not new evidence about the files.
@MainActor
public class LineageViewModel: ObservableObject {
    @Published public var candidates: [AppleAuditCandidate] = []
    @Published public var isLoading = false
    @Published public var loadError: String? = nil
    @Published public var currentIndex: Int = 0

    private let engine: LineageReviewEngine

    public init(engine: LineageReviewEngine = CoreLineageReviewEngine()) {
        self.engine = engine
    }

    public func loadCandidates() async {
        isLoading = true
        loadError = nil
        let engine = self.engine
        do {
            candidates = try await Task.detached(priority: .userInitiated) {
                let found = try await LineageViewModel.fetchCandidates()
                // Without the stored decisions a rejected pair would be
                // offered again, so a failed read is an error.
                guard let decided = engine.decidedPairKeys() else {
                    throw LineageReviewError.decisionsUnavailable
                }
                return LineageViewModel.pendingReview(
                    pairs: found.pairs, edits: found.edits, filenames: found.filenames, decided: decided)
            }.value
            currentIndex = 0
        } catch {
            loadError = error.localizedDescription
        }
        isLoading = false
    }

    public func confirmCandidate(_ candidate: AppleAuditCandidate) {
        let eventUuid = engine.confirm(
            parentLocalId: candidate.masterLocalId,
            childLocalId: candidate.derivativeLocalId,
            relationshipType: "DERIVED_FROM",
            resolver: candidate.resolver,
            confidence: candidate.confidence
        )
        if eventUuid != nil {
            loadError = nil
            advanceQueue()
        } else {
            loadError = String(localized: "Failed to confirm lineage pair (engine unavailable)")
        }
    }

    public func rejectCandidate(_ candidate: AppleAuditCandidate) {
        let saved = engine.reject(
            parentLocalId: candidate.masterLocalId,
            childLocalId: candidate.derivativeLocalId,
            relationshipType: "DERIVED_FROM",
            resolver: candidate.resolver,
            confidence: candidate.confidence
        )
        if saved {
            loadError = nil
            advanceQueue()
        } else {
            loadError = String(localized: "Failed to reject lineage pair (engine unavailable)")
        }
    }

    private func advanceQueue() {
        if currentIndex < candidates.count {
            currentIndex += 1
        }
    }

    /// The candidates the user still has to decide: ProRAW pairs and
    /// in-phone edits below confidence 1.00 (exact ones are sent
    /// automatically) that are not already confirmed or rejected. An edit
    /// found by filename (0.95) has no other way to reach the server.
    /// `filenames` maps local IDs to the names shown for an edit.
    nonisolated static func pendingReview(
        pairs: [AppleLineagePair],
        edits: [AppleInPhoneEdit] = [],
        filenames: [String: String] = [:],
        decided: Set<String>
    ) -> [AppleAuditCandidate] {
        let pairCandidates = pairs
            .filter { !LineageReviewPolicy.isAutomatic($0.confidence) }
            .map { pair in
                AppleAuditCandidate(
                    id: LineageReviewPolicy.pairKey(parent: pair.masterLocalId, child: pair.derivativeLocalId),
                    masterLocalId: pair.masterLocalId,
                    derivativeLocalId: pair.derivativeLocalId,
                    masterFilename: pair.masterFilename,
                    derivativeFilename: pair.derivativeFilename,
                    confidence: pair.confidence,
                    resolver: pair.resolver
                )
            }
        let editCandidates = edits
            .filter { !LineageReviewPolicy.isAutomatic($0.confidence) }
            .map { edit in
                AppleAuditCandidate(
                    id: LineageReviewPolicy.pairKey(parent: edit.originalMasterId, child: edit.editedDerivativeId),
                    masterLocalId: edit.originalMasterId,
                    derivativeLocalId: edit.editedDerivativeId,
                    masterFilename: filenames[edit.originalMasterId] ?? edit.originalMasterId,
                    derivativeFilename: filenames[edit.editedDerivativeId] ?? edit.editedDerivativeId,
                    confidence: edit.confidence,
                    resolver: AppleEditCorrelator.resolver(for: edit)
                )
            }
        var seen = Set<String>()
        return (pairCandidates + editCandidates).filter { candidate in
            !decided.contains(candidate.id) && seen.insert(candidate.id).inserted
        }
    }

    nonisolated private static func fetchCandidates() async throws
        -> (pairs: [AppleLineagePair], edits: [AppleInPhoneEdit], filenames: [String: String]) {
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
        let edits = AppleEditCorrelator.findEdits(in: assetMetas.map { (id: $0.id, filename: $0.filename) })
        var filenames = [String: String]()
        for meta in assetMetas { filenames[meta.id] = meta.filename }
        return (pairs: pairs, edits: edits, filenames: filenames)
    }
}

enum LineageReviewError: LocalizedError {
    case decisionsUnavailable

    var errorDescription: String? {
        String(localized: "Lineage review is unavailable: the engine is not open.")
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
