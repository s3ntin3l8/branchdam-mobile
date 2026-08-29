import XCTest
@testable import BranchDAM

final class AppleUiStateTests: XCTestCase {

    func testQrParsing() {
        let uri = "branchdam://server=http://192.168.1.120:8080&key=testkey123&agent=iphone-16-pro"
        let config = AppleQrParser.parse(uriString: uri)

        XCTAssertNotNil(config)
        XCTAssertEqual(config?.serverUrl, "http://192.168.1.120:8080")
        XCTAssertEqual(config?.apiKey, "testkey123")
        XCTAssertEqual(config?.agentId, "iphone-16-pro")

        let invalid = AppleQrParser.parse(uriString: "https://google.com")
        XCTAssertNil(invalid)
    }

    func testAuditCandidateModel() {
        let candidate = AppleAuditCandidate(
            id: "edge-1",
            masterFilename: "IMG_0001.DNG",
            derivativeFilename: "IMG_0001.HEIC",
            confidence: 1.00,
            resolver: "ios_apple_proraw_pair"
        )
        XCTAssertEqual(candidate.id, "edge-1")
        XCTAssertEqual(candidate.confidence, 1.00)
    }
}
