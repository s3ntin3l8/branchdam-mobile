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
