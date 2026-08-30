import Foundation

public struct AppleInPhoneEdit: Equatable {
    public let originalMasterId: String
    public let editedDerivativeId: String
    public let editorApp: String
    public let confidence: Double
}

public class AppleEditCorrelator {

    public static func findAppEdits(
        masters: [(id: String, filename: String)],
        derivatives: [(id: String, filename: String, app: String)]
    ) -> [AppleInPhoneEdit] {
        var edits = [AppleInPhoneEdit]()

        for deriv in derivatives {
            let derivStem = extractEditedBaseStem(deriv.filename)
            if let matched = masters.first(where: {
                extractBaseStem($0.filename) == derivStem || $0.filename.contains(derivStem)
            }) {
                edits.append(
                    AppleInPhoneEdit(
                        originalMasterId: matched.id,
                        editedDerivativeId: deriv.id,
                        editorApp: deriv.app,
                        confidence: 0.95
                    )
                )
            }
        }

        return edits
    }

    @discardableResult
    public static func registerEditLineage(edits: [AppleInPhoneEdit]) -> Int {
        var count = 0
        for edit in edits {
            let resolver = "in_phone_\(edit.editorApp.lowercased().replacingOccurrences(of: " ", with: "_"))"
            let eventId = BranchDamCoreBridge.shared.enqueueLineageEvent(
                parentUUID: edit.originalMasterId,
                childUUID: edit.editedDerivativeId,
                relationshipType: "DERIVED_FROM",
                resolver: resolver,
                confidence: edit.confidence
            )
            if !eventId.isEmpty {
                count += 1
            }
        }
        return count
    }

    private static func extractBaseStem(_ filename: String) -> String {
        return (filename as NSString).deletingPathExtension.replacingOccurrences(of: "IMG_", with: "")
    }

    private static func extractEditedBaseStem(_ filename: String) -> String {
        return extractBaseStem(filename)
            .replacingOccurrences(of: "_edited", with: "")
            .replacingOccurrences(of: "_Photomator", with: "")
            .replacingOccurrences(of: "_Darkroom", with: "")
    }
}
