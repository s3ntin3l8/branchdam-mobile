import XCTest
@testable import BranchDAM

final class AppleEditCorrelatorTests: XCTestCase {

    override func setUp() {
        super.setUp()
        _ = BranchDamCoreBridge.shared.initialize(
            dbPath: NSTemporaryDirectory() + "edit_correlator_test.db",
            baseURL: "http://localhost:8080"
        )
    }

    func testInPhoneEditCorrelationAndLineage() {
        let masters = [
            (id: "ph://master-1", filename: "IMG_4500.JPG"),
            (id: "ph://master-2", filename: "IMG_4501.DNG")
        ]

        let derivatives = [
            (id: "ph://deriv-1", filename: "IMG_4500_Photomator.JPG", app: "Photomator"),
            (id: "ph://deriv-2", filename: "IMG_4500_edited.JPG", app: "Apple Photos Editor"),
            (id: "ph://deriv-3", filename: "IMG_4501_Darkroom.JPG", app: "Darkroom")
        ]

        let edits = AppleEditCorrelator.findAppEdits(masters: masters, derivatives: derivatives)
        XCTAssertEqual(edits.count, 3)

        XCTAssertEqual(edits[0].originalMasterId, "ph://master-1")
        XCTAssertEqual(edits[0].editedDerivativeId, "ph://deriv-1")
        XCTAssertEqual(edits[0].editorApp, "Photomator")

        XCTAssertEqual(edits[1].originalMasterId, "ph://master-1")
        XCTAssertEqual(edits[1].editedDerivativeId, "ph://deriv-2")
        XCTAssertEqual(edits[1].editorApp, "Apple Photos Editor")

        XCTAssertEqual(edits[2].originalMasterId, "ph://master-2")
        XCTAssertEqual(edits[2].editedDerivativeId, "ph://deriv-3")
        XCTAssertEqual(edits[2].editorApp, "Darkroom")

        let registered = AppleEditCorrelator.registerEditLineage(edits: edits)
        XCTAssertEqual(registered, 3)
    }

    func testNonMatchingDerivatives() {
        let masters = [
            (id: "ph://master-1", filename: "IMG_1000.JPG")
        ]

        let derivatives = [
            (id: "ph://deriv-99", filename: "IMG_9999_Photomator.JPG", app: "Photomator")
        ]

        let edits = AppleEditCorrelator.findAppEdits(masters: masters, derivatives: derivatives)
        XCTAssertEqual(edits.count, 0)

        let registered = AppleEditCorrelator.registerEditLineage(edits: edits)
        XCTAssertEqual(registered, 0)
    }
}
