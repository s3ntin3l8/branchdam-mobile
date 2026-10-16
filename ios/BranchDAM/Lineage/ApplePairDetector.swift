import Foundation

public struct AppleLineagePair: Equatable {
    public let masterLocalId: String
    public let derivativeLocalId: String
    public let masterFilename: String
    public let derivativeFilename: String
    public let confidence: Double
    public let resolver: String

    public init(
        masterLocalId: String,
        derivativeLocalId: String,
        masterFilename: String,
        derivativeFilename: String,
        confidence: Double = 1.00,
        resolver: String = "ios_apple_proraw_pair"
    ) {
        self.masterLocalId = masterLocalId
        self.derivativeLocalId = derivativeLocalId
        self.masterFilename = masterFilename
        self.derivativeFilename = derivativeFilename
        self.confidence = confidence
        self.resolver = resolver
    }
}

/// Who decides a lineage pair. Only exact pairs (confidence 1.00: a ProRAW
/// DNG and its HEIC/JPEG sharing a stem, a Live Photo and its paired video)
/// are sent on the device's own say-so (AGENTS.md invariant 4). Anything
/// weaker is a guess that waits in the lineage review screen for the user to
/// confirm or reject it; the Go core enforces the same rule
/// (`bindingEnqueueLineageEvent` answers REVIEW_REQUIRED below 1.00) and
/// stores the user's decisions, so a rejected pair stays rejected after a
/// restart.
public enum LineageReviewPolicy {
    /// The confidence at which a pair is sent without asking the user.
    public static let autoConfidence = 1.00

    public static func isAutomatic(_ confidence: Double) -> Bool {
        confidence >= autoConfidence
    }

    /// Key of a proposed pair, parent first.
    public static func pairKey(parent: String, child: String) -> String {
        "\(parent)|\(child)"
    }

    /// Parses `bindingListLineageReviews`' JSON array into the keys of every
    /// pair the user already decided (confirmed or rejected). Nil when the
    /// JSON is malformed.
    public static func parseDecidedKeys(_ json: String) -> Set<String>? {
        struct Review: Decodable {
            let parentLocalId: String
            let childLocalId: String
        }
        if json.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return [] }
        guard let data = json.data(using: .utf8),
              let reviews = try? JSONDecoder().decode([Review].self, from: data) else {
            return nil
        }
        return Set(reviews
            .filter { !$0.parentLocalId.isEmpty && !$0.childLocalId.isEmpty }
            .map { pairKey(parent: $0.parentLocalId, child: $0.childLocalId) })
    }
}

public class ApplePairDetector {

    /**
     * Finds companion Apple ProRAW (DNG) and Tone-Mapped HEIC/JPEG derivatives sharing stem or timestamp proximity.
     */
    public static func findProRawPairs(
        masters: [(id: String, filename: String, dateUnix: Int64)],
        derivatives: [(id: String, filename: String, dateUnix: Int64)]
    ) -> [AppleLineagePair] {
        var pairs = [AppleLineagePair]()
        var matchedDerivatives = Set<String>()

        for master in masters {
            let masterStem = extractStem(master.filename)

            // Exact stem match
            if let exactMatch = derivatives.first(where: {
                !matchedDerivatives.contains($0.id) && extractStem($0.filename) == masterStem
            }) {
                pairs.append(
                    AppleLineagePair(
                        masterLocalId: master.id,
                        derivativeLocalId: exactMatch.id,
                        masterFilename: master.filename,
                        derivativeFilename: exactMatch.filename,
                        confidence: 1.00
                    )
                )
                matchedDerivatives.insert(exactMatch.id)
                continue
            }

            // Proximity match within 2 seconds
            if let timeMatch = derivatives.first(where: {
                !matchedDerivatives.contains($0.id) && abs($0.dateUnix - master.dateUnix) <= 2
            }) {
                pairs.append(
                    AppleLineagePair(
                        masterLocalId: master.id,
                        derivativeLocalId: timeMatch.id,
                        masterFilename: master.filename,
                        derivativeFilename: timeMatch.filename,
                        confidence: 0.95
                    )
                )
                matchedDerivatives.insert(timeMatch.id)
            }
        }

        return pairs
    }

    /// Enqueues the pairs the device may send on its own: exact (1.00)
    /// stem matches. A timestamp-proximity pair (0.95) is only a guess and
    /// is NOT sent here; it waits in the lineage review screen. Returns the
    /// number of pairs enqueued.
    public static func registerPairs(pairs: [AppleLineagePair]) -> Int {
        var registered = 0
        for pair in pairs where LineageReviewPolicy.isAutomatic(pair.confidence) {
            _ = BranchDamCoreBridge.shared.enqueueLineageEvent(
                parentUUID: pair.masterLocalId,
                childUUID: pair.derivativeLocalId,
                relationshipType: "DERIVED_FROM",
                resolver: pair.resolver,
                confidence: pair.confidence
            )
            registered += 1
        }
        return registered
    }

    internal static func extractStem(_ filename: String) -> String {
        var stem = (filename as NSString).deletingPathExtension
        if let regex = try? NSRegularExpression(pattern: "(\\.(RAW(-\\d+)?|ORIGINAL|COVER|MP|ACTION|PORTRAIT|NIGHT|BURST\\d*|TS(-\\d+)*))+$", options: .caseInsensitive) {
            let range = NSRange(location: 0, length: stem.utf16.count)
            stem = regex.stringByReplacingMatches(in: stem, options: [], range: range, withTemplate: "")
        }
        return stem.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
