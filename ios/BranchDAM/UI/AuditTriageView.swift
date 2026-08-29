import SwiftUI

public struct AppleAuditCandidate: Identifiable, Equatable {
    public let id: String
    public let masterFilename: String
    public let derivativeFilename: String
    public let confidence: Double
    public let resolver: String

    public init(id: String, masterFilename: String, derivativeFilename: String, confidence: Double, resolver: String) {
        self.id = id
        self.masterFilename = masterFilename
        self.derivativeFilename = derivativeFilename
        self.confidence = confidence
        self.resolver = resolver
    }
}

public struct AuditTriageView: View {
    @State private var candidates: [AppleAuditCandidate] = [
        AppleAuditCandidate(id: "1", masterFilename: "IMG_3001.DNG", derivativeFilename: "IMG_3001.HEIC", confidence: 1.00, resolver: "ios_apple_proraw_pair")
    ]
    @State private var currentIndex: Int = 0

    public init() {}

    public var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                if currentIndex < candidates.count {
                    let current = candidates[currentIndex]

                    VStack(alignment: .leading, spacing: 16) {
                        Text("Candidate \(currentIndex + 1) of \(candidates.count)")
                            .font(.caption)
                            .foregroundColor(.secondary)

                        RoundedRectangle(cornerRadius: 16)
                            .fill(Color(.secondarySystemBackground))
                            .frame(height: 240)
                            .overlay(
                                VStack(alignment: .leading, spacing: 12) {
                                    Text("Master: \(current.masterFilename)")
                                        .font(.headline)
                                    Text("Derivative: \(current.derivativeFilename)")
                                        .font(.subheadline)
                                    Text("Confidence: \(Int(current.confidence * 100))%")
                                        .foregroundColor(.accentColor)
                                    Text("Resolver: \(current.resolver)")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                .padding()
                            )

                        HStack(spacing: 16) {
                            Button(action: {
                                currentIndex += 1
                            }) {
                                Label("Reject", systemImage: "xmark.circle.fill")
                                    .frame(maxWidth: .infinity)
                                    .padding()
                                    .background(Color.red.opacity(0.15))
                                    .foregroundColor(.red)
                                    .cornerRadius(12)
                            }

                            Button(action: {
                                currentIndex += 1
                            }) {
                                Label("Confirm", systemImage: "checkmark.circle.fill")
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
        }
    }
}
