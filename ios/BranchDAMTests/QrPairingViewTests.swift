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

    func testParseDecodesPercentEncodedServer() {
        // Mirrors the exact payload the server emits via url.Values.Encode():
        // `://` becomes `%3A%2F%2F`. Without decoding the parser used to
        // hand the raw encoded string to the engine, which failed to parse it.
        let config = AppleQrParser.parse(uriString: "branchdam://?server=https%3A%2F%2Fdam.example.com&key=abc123&agent=iphone-test")
        XCTAssertNotNil(config)
        XCTAssertEqual(config?.serverUrl, "https://dam.example.com")
        XCTAssertEqual(config?.apiKey, "abc123")
        XCTAssertEqual(config?.agentId, "iphone-test")
    }

    func testParseDecodesServerWithPort() {
        // Port-bearing host: the `:` after the host is encoded to `%3A`
        // by the server. The parser must decode it back.
        let config = AppleQrParser.parse(uriString: "branchdam://?server=https%3A%2F%2Fdam.example.com%3A8443&key=secret&agent=phone")
        XCTAssertNotNil(config)
        XCTAssertEqual(config?.serverUrl, "https://dam.example.com:8443")
        XCTAssertEqual(config?.apiKey, "secret")
        XCTAssertEqual(config?.agentId, "phone")
    }

    func testParseRejectsMalformedPercentEncoding() {
        // Stray `%` not followed by two hex digits → removingPercentEncoding
        // returns nil → parser must return null rather than silently
        // passing through a malformed string.
        XCTAssertNil(AppleQrParser.parse(uriString: "branchdam://?server=https%ZZdam.example.com&key=abc&agent=test"))
        XCTAssertNil(AppleQrParser.parse(uriString: "branchdam://?server=https%&key=abc&agent=test"))
        XCTAssertNil(AppleQrParser.parse(uriString: "branchdam://?server=https%2&key=abc&agent=test"))
    }

    func testParseHandlesApiKeyContainingEquals() {
        // Regression guard: an `=` inside a percent-encoded API key
        // stays part of the value.
        let config = AppleQrParser.parse(uriString: "branchdam://?server=https%3A%2F%2Fx&key=abc%3Ddef&agent=test")
        XCTAssertNotNil(config)
        XCTAssertEqual(config?.serverUrl, "https://x")
        XCTAssertEqual(config?.apiKey, "abc=def")
    }

    func testParseEncodedAndUnencodedFormsAgree() {
        // The two payload shapes (encoded by the server vs. unencoded in
        // some test fixtures) must yield identical ApplePairingConfig values.
        let encoded = AppleQrParser.parse(uriString: "branchdam://?server=https%3A%2F%2Fdam.example.com&key=abc&agent=phone")
        let unencoded = AppleQrParser.parse(uriString: "branchdam://?server=https://dam.example.com&key=abc&agent=phone")
        XCTAssertNotNil(encoded)
        XCTAssertNotNil(unencoded)
        XCTAssertEqual(unencoded?.serverUrl, encoded?.serverUrl)
        XCTAssertEqual(unencoded?.apiKey, encoded?.apiKey)
        XCTAssertEqual(unencoded?.agentId, encoded?.agentId)
    }

    // MARK: - Documents-directory DB path (F plan: reconfigure does
    // not orphan the documents-directory DB)

    func testDocumentsDirectoryDBPathIsNotInTmp() {
        // Call the real SUT function and assert on its output. If
        // QrPairingView.defaultDBPath() regressed to the temp
        // directory, this test would fail.
        let view = QrPairingView()
        let path = view.defaultDBPath()

        // The path must end with "branchdam_queue.db"
        XCTAssertTrue(path.hasSuffix("branchdam_queue.db"),
                      "DB path must end with branchdam_queue.db, got: \(path)")

        // The path must NOT contain /tmp/ (i.e., not in the temp directory)
        let tmpPath = NSTemporaryDirectory()
        XCTAssertFalse(path.hasPrefix(tmpPath),
                       "DB path must not be in /tmp, got: \(path)")

        // The path must be under the app's documents directory
        let docsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].path
        XCTAssertTrue(path.hasPrefix(docsPath),
                      "DB path must be under documents directory, got: \(path)")
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
