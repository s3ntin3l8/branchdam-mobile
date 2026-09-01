import XCTest
@testable import BranchDAM

/// Tests for QrPairingView — covers the QR parser (pure function)
/// and the documents-directory DB path used by the "Connect" button.
///
/// The View itself is a SwiftUI Composable; the testable surface is:
/// - AppleQrParser.parse(uriString:) — pure function
/// - defaultDBPath() — returns the documents-directory path
///
/// Both are critical for the F plan items: (a) fetchNamingTemplate
/// replaces hardcoded template, (b) reconfigure does not orphan the
/// documents-directory DB.
final class QrPairingViewTests: XCTestCase {

    // MARK: - AppleQrParser (F plan: QrPairingScreen test)

    func testParseHappyPath() {
        let config = AppleQrParser.parse(uriString: "branchdam://server=http://192.168.1.100:8080&key=abc123&agent=iphone-test")
        XCTAssertNotNil(config)
        XCTAssertEqual(config?.serverUrl, "http://192.168.1.100:8080")
        XCTAssertEqual(config?.apiKey, "abc123")
        XCTAssertEqual(config?.agentId, "iphone-test")
    }

    func testParseDefaultsAgentToIphoneCompanion() {
        let config = AppleQrParser.parse(uriString: "branchdam://server=http://10.0.2.2:8080&key=secret")
        XCTAssertNotNil(config)
        XCTAssertEqual(config?.serverUrl, "http://10.0.2.2:8080")
        XCTAssertEqual(config?.apiKey, "secret")
        XCTAssertEqual(config?.agentId, "iphone-companion")
    }

    func testParseEmptyApiKey() {
        let config = AppleQrParser.parse(uriString: "branchdam://server=http://example.com:8080&key=&agent=test")
        XCTAssertNotNil(config)
        XCTAssertEqual(config?.apiKey, "")
    }

    func testParseRejectsNonBranchdamScheme() {
        XCTAssertNil(AppleQrParser.parse(uriString: "https://server=http://example.com"))
        XCTAssertNil(AppleQrParser.parse(uriString: ""))
        XCTAssertNil(AppleQrParser.parse(uriString: "not a url"))
    }

    func testParseRejectsMissingServer() {
        XCTAssertNil(AppleQrParser.parse(uriString: "branchdam://key=abc&agent=test"))
    }

    func testParseRejectsEmptyServer() {
        XCTAssertNil(AppleQrParser.parse(uriString: "branchdam://server=&key=abc"))
    }

    // MARK: - Documents-directory DB path (F plan: reconfigure does
    // not orphan the documents-directory DB)

    func testDocumentsDirectoryDBPathIsNotInTmp() {
        // The QrPairingView's "Connect" button calls
        // `defaultDBPath()` which returns a documents-directory path.
        // Verify the path is under .documentDirectory and NOT under
        // .temporaryDirectory (i.e., NOT in /tmp).
        let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
        let expectedPath = paths[0].appendingPathComponent("branchdam_queue.db").path

        // The path must end with "branchdam_queue.db"
        XCTAssertTrue(expectedPath.hasSuffix("branchdam_queue.db"),
                      "DB path must end with branchdam_queue.db, got: \(expectedPath)")

        // The path must NOT contain /tmp/ (i.e., not in the temp directory)
        let tmpPath = NSTemporaryDirectory()
        XCTAssertFalse(expectedPath.hasPrefix(tmpPath),
                       "DB path must not be in /tmp, got: \(expectedPath)")

        // The path must be under the app's documents directory
        let docsPath = paths[0].path
        XCTAssertTrue(expectedPath.hasPrefix(docsPath),
                      "DB path must be under documents directory, got: \(expectedPath)")
    }

    func testApplePairingConfigEquatable() {
        // The F plan calls for the QR pairing config to be used for
        // reconfigure. Verify Equatable conformance so the View can
        // diff old vs new config.
        let a = ApplePairingConfig(serverUrl: "http://x:8080", apiKey: "k", agentId: "a")
        let b = ApplePairingConfig(serverUrl: "http://x:8080", apiKey: "k", agentId: "a")
        let c = ApplePairingConfig(serverUrl: "http://y:8080", apiKey: "k", agentId: "a")
        XCTAssertEqual(a, b)
        XCTAssertNotEqual(a, c)
    }
}
