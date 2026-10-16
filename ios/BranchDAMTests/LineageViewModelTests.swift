import XCTest
@testable import BranchDAM

/// Records review decisions in memory, standing in for the Go core.
final class FakeLineageReviewEngine: LineageReviewEngine, @unchecked Sendable {
    struct Call: Equatable {
        let parent: String
        let child: String
        let resolver: String
        let confidence: Double
    }

    var confirmed: [Call] = []
    var rejected: [Call] = []
    var decided: Set<String>? = []
    var failWrites = false

    func confirm(parentLocalId: String, childLocalId: String, relationshipType: String,
                 resolver: String, confidence: Double) -> String? {
        if failWrites { return nil }
        confirmed.append(Call(parent: parentLocalId, child: childLocalId, resolver: resolver, confidence: confidence))
        return UUID().uuidString
    }

    func reject(parentLocalId: String, childLocalId: String, relationshipType: String,
                resolver: String, confidence: Double) -> Bool {
        if failWrites { return false }
        rejected.append(Call(parent: parentLocalId, child: childLocalId, resolver: resolver, confidence: confidence))
        return true
    }

    func decidedPairKeys() -> Set<String>? { decided }
}

final class LineageViewModelTests: XCTestCase {

    private func candidate(_ n: Int, confidence: Double = 0.95) -> AppleAuditCandidate {
        AppleAuditCandidate(
            id: "cand-\(n)",
            masterLocalId: "ph://master-\(n)",
            derivativeLocalId: "ph://deriv-\(n)",
            masterFilename: "IMG_000\(n).DNG",
            derivativeFilename: "IMG_000\(n).JPG",
            confidence: confidence,
            resolver: "ios_apple_proraw_pair"
        )
    }

    @MainActor
    func testRejectPersistsTheDecisionAndAdvances() {
        let engine = FakeLineageReviewEngine()
        let vm = LineageViewModel(engine: engine)
        vm.candidates = [candidate(1), candidate(2)]

        vm.rejectCandidate(vm.candidates[0])
        XCTAssertEqual(vm.currentIndex, 1)
        vm.rejectCandidate(vm.candidates[1])
        XCTAssertEqual(vm.currentIndex, 2)

        XCTAssertEqual(engine.rejected.map(\.parent), ["ph://master-1", "ph://master-2"])
        XCTAssertTrue(engine.confirmed.isEmpty)
    }

    @MainActor
    func testConfirmKeepsTheResolverConfidence() {
        let engine = FakeLineageReviewEngine()
        let vm = LineageViewModel(engine: engine)
        vm.candidates = [candidate(1, confidence: 0.95)]

        vm.confirmCandidate(vm.candidates[0])

        XCTAssertEqual(vm.currentIndex, 1)
        XCTAssertEqual(engine.confirmed, [
            FakeLineageReviewEngine.Call(parent: "ph://master-1", child: "ph://deriv-1",
                                         resolver: "ios_apple_proraw_pair", confidence: 0.95)
        ], "Confirm must send the pair at its own confidence, never bumped to 1.00")
    }

    @MainActor
    func testFailedDecisionKeepsTheCandidate() {
        let engine = FakeLineageReviewEngine()
        engine.failWrites = true
        let vm = LineageViewModel(engine: engine)
        vm.candidates = [candidate(1)]

        vm.rejectCandidate(vm.candidates[0])
        XCTAssertEqual(vm.currentIndex, 0, "an unsaved rejection must not drop the candidate")
        XCTAssertNotNil(vm.loadError)

        vm.confirmCandidate(vm.candidates[0])
        XCTAssertEqual(vm.currentIndex, 0)
    }

    func testPendingReviewOffersOnlyUndecidedGuesses() {
        let exact = AppleLineagePair(masterLocalId: "m1", derivativeLocalId: "d1",
                                     masterFilename: "IMG_1.DNG", derivativeFilename: "IMG_1.HEIC", confidence: 1.00)
        let guess = AppleLineagePair(masterLocalId: "m2", derivativeLocalId: "d2",
                                     masterFilename: "IMG_2.DNG", derivativeFilename: "IMG_9.HEIC", confidence: 0.95)
        let rejected = AppleLineagePair(masterLocalId: "m3", derivativeLocalId: "d3",
                                        masterFilename: "IMG_3.DNG", derivativeFilename: "IMG_8.HEIC", confidence: 0.95)

        let pending = LineageViewModel.pendingReview(
            pairs: [exact, guess, rejected],
            decided: [LineageReviewPolicy.pairKey(parent: "m3", child: "d3")]
        )
        XCTAssertEqual(pending.map(\.masterLocalId), ["m2"])
        XCTAssertEqual(pending.first?.confidence, 0.95)
    }

    func testInPhoneEditsAreOfferedForReview() {
        // findAppEdits reports every filename-matched edit at 0.95; with no
        // automatic path left for it, the review screen is the only way
        // such an edge can reach the server.
        let edits = AppleEditCorrelator.findEdits(in: [
            (id: "m1", filename: "IMG_0001.HEIC"),
            (id: "e1", filename: "IMG_0001_edited.JPG"),
            (id: "m2", filename: "IMG_0002.HEIC"),
            (id: "e2", filename: "IMG_0002_edited.JPG"),
        ])
        XCTAssertEqual(edits.count, 2)

        let pending = LineageViewModel.pendingReview(
            pairs: [],
            edits: edits,
            filenames: ["m1": "IMG_0001.HEIC", "e1": "IMG_0001_edited.JPG"],
            decided: [LineageReviewPolicy.pairKey(parent: "m2", child: "e2")]
        )
        XCTAssertEqual(pending.map(\.id), [LineageReviewPolicy.pairKey(parent: "m1", child: "e1")])
        XCTAssertEqual(pending.first?.masterFilename, "IMG_0001.HEIC")
        XCTAssertEqual(pending.first?.derivativeFilename, "IMG_0001_edited.JPG")
        XCTAssertEqual(pending.first?.confidence, 0.95)
        XCTAssertEqual(pending.first?.resolver, "in_phone_ios_editor")
    }

    func testParseDecidedKeys() {
        let json = """
        [{"parentLocalId":"a","childLocalId":"b","decision":"REJECTED","confidence":0.95,"decidedAtUnix":1},
         {"parentLocalId":"c","childLocalId":"d","decision":"CONFIRMED","confidence":0.95,"eventUuid":"e","decidedAtUnix":2}]
        """
        XCTAssertEqual(LineageReviewPolicy.parseDecidedKeys(json), Set(["a|b", "c|d"]))
        XCTAssertEqual(LineageReviewPolicy.parseDecidedKeys("[]"), Set<String>())
        XCTAssertNil(LineageReviewPolicy.parseDecidedKeys("not json"))
    }
}
