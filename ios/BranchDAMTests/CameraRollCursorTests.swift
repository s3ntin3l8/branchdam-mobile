import XCTest
@testable import BranchDAM

final class CameraRollCursorTests: XCTestCase {

    private var suiteName: String!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "CameraRollCursorTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    func testFirstLaunchLooksBackAnHourAndPersistsIt() {
        let launch = Date(timeIntervalSince1970: 1_800_000_000)
        let store = CameraRollCursorStore(defaults: defaults, now: { launch })

        XCTAssertEqual(store.lastScannedDate, launch.addingTimeInterval(-3600))

        // A later launch must resume from the persisted cursor, not
        // reset to "an hour before this launch".
        let nextLaunch = CameraRollCursorStore(defaults: defaults, now: { launch.addingTimeInterval(86_400) })
        XCTAssertEqual(nextLaunch.lastScannedDate, launch.addingTimeInterval(-3600))
    }

    func testAdvancedCursorSurvivesARelaunch() {
        let store = CameraRollCursorStore(defaults: defaults, now: { Date(timeIntervalSince1970: 1_000) })
        let scanned = Date(timeIntervalSince1970: 5_000.25)
        store.advanceDate(to: scanned)

        let relaunched = CameraRollCursorStore(defaults: defaults, now: { Date(timeIntervalSince1970: 99_999) })
        XCTAssertEqual(relaunched.lastScannedDate.timeIntervalSince1970, 5_000.25, accuracy: 0.0001)
    }

    func testCursorNeverMovesBackwards() {
        let store = CameraRollCursorStore(defaults: defaults, now: { Date(timeIntervalSince1970: 10_000) })
        store.advanceDate(to: Date(timeIntervalSince1970: 20_000))
        store.advanceDate(to: Date(timeIntervalSince1970: 15_000))
        XCTAssertEqual(store.lastScannedDate, Date(timeIntervalSince1970: 20_000))
    }

    func testChangeTokenRoundTripAndClear() {
        let store = CameraRollCursorStore(defaults: defaults)
        XCTAssertNil(store.changeTokenData)

        let data = Data([0x01, 0x02, 0x03])
        store.saveChangeTokenData(data)
        XCTAssertEqual(CameraRollCursorStore(defaults: defaults).changeTokenData, data)

        store.clearChangeToken()
        XCTAssertNil(CameraRollCursorStore(defaults: defaults).changeTokenData)
    }

    func testCursorUsesTheSharedObserverKey() {
        XCTAssertEqual(
            BranchDamKeys.cameraRollLastScannedTimestamp.rawValue,
            BranchDamKeys.Android.OBSERVER_LAST_SCANNED_TIMESTAMP
        )
    }
}

final class CameraRollIntakeTests: XCTestCase {

    private func asset(_ id: String, _ seconds: TimeInterval) -> DiscoveredAsset {
        DiscoveredAsset(
            localIdentifier: id,
            filename: "\(id).heic",
            creationDateUnix: Int64(seconds),
            creationDate: Date(timeIntervalSince1970: seconds)
        )
    }

    func testEveryAssetIsRecordedOldestFirstAndTheCursorEndsAtTheNewest() {
        var order = [String]()
        let result = CameraRollIntake.commit(
            assets: [asset("b", 1_020.5), asset("a", 1_010.5)],
            startCursor: Date(timeIntervalSince1970: 1_000)
        ) { a in
            order.append(a.localIdentifier)
            return .persisted
        }

        XCTAssertEqual(order, ["a", "b"])
        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.persisted.map(\.localIdentifier), ["a", "b"])
        XCTAssertEqual(result.advancedTo, Date(timeIntervalSince1970: 1_020.5))
    }

    func testFailureStopsThePassAndKeepsTheCursorBelowTheFailedAsset() {
        let result = CameraRollIntake.commit(
            assets: [asset("a", 1_010), asset("b", 1_020), asset("c", 1_030)],
            startCursor: Date(timeIntervalSince1970: 1_000)
        ) { a in
            a.localIdentifier == "b" ? .failed : .persisted
        }

        XCTAssertFalse(result.complete, "the change token must not advance past a failed asset")
        XCTAssertEqual(result.persisted.map(\.localIdentifier), ["a"])
        let cursor = try! XCTUnwrap(result.advancedTo)
        XCTAssertLessThan(cursor, Date(timeIntervalSince1970: 1_020), "b must be fetched again")
        XCTAssertGreaterThanOrEqual(cursor, Date(timeIntervalSince1970: 1_010))
    }

    func testNothingRecordedLeavesTheCursorUnmoved() {
        let result = CameraRollIntake.commit(
            assets: [asset("a", 1_010)],
            startCursor: Date(timeIntervalSince1970: 1_000)
        ) { _ in .failed }

        XCTAssertNil(result.advancedTo)
        XCTAssertFalse(result.complete)
    }

    func testSkippedAssetsAdvanceTheCursorWithoutBeingReoffered() {
        let result = CameraRollIntake.commit(
            assets: [asset("a", 1_010), asset("b", 1_020)],
            startCursor: Date(timeIntervalSince1970: 1_000)
        ) { a in
            a.localIdentifier == "a" ? .alreadyDecided : .persisted
        }

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.persisted.map(\.localIdentifier), ["b"])
        XCTAssertEqual(result.advancedTo, Date(timeIntervalSince1970: 1_020))
    }

    func testStagingIntoTheEngineBeforeTheCursorMoves() {
        let engine = FakePendingImportEngine()
        let notifier = AppleCameraRollImportNotifier(engine: engine, stager: FakeAssetStager(), afterImport: { _, _ in })

        let result = CameraRollIntake.commit(
            assets: [asset("a", 1_010)],
            startCursor: Date(timeIntervalSince1970: 1_000)
        ) { a in
            notifier.stagePendingAsset(a) == .staged ? .persisted : .failed
        }

        // The intent is in the (persistent) engine by the time the
        // caller gets a cursor to save.
        XCTAssertNotNil(result.advancedTo)
        XCTAssertEqual(engine.listPendingImports()?.map(\.localId), ["a"])
    }

    func testPendingImportJsonFromTheCoreDecodes() {
        let json = """
        [{"localId":"A/L0/001","localPath":"","filename":"IMG_1.HEIC","sizeBytes":0,
          "capturedAtUnix":1724000000,"state":"PENDING","createdAtUnix":1724000100,"updatedAtUnix":1724000100},
         {"filename":"no-id.jpg"}]
        """
        let items = PendingImportItem.decodeList(json)
        XCTAssertEqual(items?.map(\.localId), ["A/L0/001"])
        XCTAssertEqual(items?.first?.createdAtUnix, 1_724_000_100)
        XCTAssertEqual(items?.first?.mimeType, "")
        XCTAssertNil(PendingImportItem.decodeList("not json"))
    }
}
