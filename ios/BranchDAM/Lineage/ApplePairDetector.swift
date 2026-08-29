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

    public static func registerPairs(pairs: [AppleLineagePair]) -> Int {
        var registered = 0
        for pair in pairs {
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

    private static func extractStem(_ filename: String) -> String {
        return (filename as NSString).deletingPathExtension
    }
}
