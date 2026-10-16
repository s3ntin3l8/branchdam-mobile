import XCTest
@testable import BranchDAM

final class AppleCameraRollImportNotifierTests: XCTestCase {

    private var engine: FakePendingImportEngine!
    private var recorder: AfterImportRecorder!
    private var afterImport: [(AppleCameraRollImportNotifier.Action, Int)] { recorder.calls }

    override func setUp() {
        super.setUp()
        engine = FakePendingImportEngine()
        recorder = AfterImportRecorder()
        UserDefaults.standard.removeObject(forKey: AppleCameraRollImportNotifier.keyAutoImportEnabled)
    }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: AppleCameraRollImportNotifier.keyAutoImportEnabled)
        super.tearDown()
    }

    private func makeNotifier(failing: Set<String> = []) -> AppleCameraRollImportNotifier {
        AppleCameraRollImportNotifier(
            engine: engine,
            stager: FakeAssetStager(failing: failing),
            afterImport: { [recorder = self.recorder!] action, queued in
                recorder.record(action, queued)
            }
        )
    }

    private func asset(_ id: String, at unix: Int64 = 1_724_000_000) -> DiscoveredAsset {
        DiscoveredAsset(localIdentifier: id, filename: "IMG_\(id.prefix(4)).HEIC", creationDateUnix: unix)
    }

    private func run(
        _ notifier: AppleCameraRollImportNotifier,
        _ action: AppleCameraRollImportNotifier.Action,
        stagedBefore: Int64 = .max,
        timeout: TimeInterval = 2.0
    ) -> Bool {
        let done = expectation(description: "action handled")
        let handled = HandledFlag()
        notifier.handleAction(action, stagedBeforeUnix: stagedBefore) { ok in
            handled.value = ok
            done.fulfill()
        }
        wait(for: [done], timeout: timeout)
        return handled.value
    }

    func testConstants() {
        XCTAssertEqual(AppleCameraRollImportNotifier.keyAutoImportEnabled, "branchdam_auto_import_camera_roll")
        XCTAssertEqual(AppleCameraRollImportNotifier.categoryIdentifier, "com.branchdam.category.cameraRollImport")
        XCTAssertEqual(AppleCameraRollImportNotifier.actionImportNow, "com.branchdam.action.importNow")
        XCTAssertEqual(AppleCameraRollImportNotifier.actionLater, "com.branchdam.action.later")
        XCTAssertEqual(AppleCameraRollImportNotifier.actionSkip, "com.branchdam.action.skip")
    }

    func testAutoImportPreferencePersistence() {
        let notifier = makeNotifier()
        XCTAssertFalse(notifier.autoImportEnabled)

        notifier.autoImportEnabled = true
        XCTAssertTrue(notifier.autoImportEnabled)
        XCTAssertTrue(UserDefaults.standard.bool(forKey: AppleCameraRollImportNotifier.keyAutoImportEnabled))

        notifier.autoImportEnabled = false
        XCTAssertFalse(notifier.autoImportEnabled)
    }

    func testStagedAssetsArePersistedInTheEngine() {
        let notifier = makeNotifier()
        XCTAssertEqual(notifier.stagePendingAsset(asset("A/L0/001")), .staged)

        let pending = notifier.getPendingAssets()
        XCTAssertEqual(pending.map(\.localId), ["A/L0/001"])
        XCTAssertEqual(pending.first?.filename, "IMG_A/L0.HEIC")
        XCTAssertEqual(pending.first?.capturedAtUnix, 1_724_000_000)
    }

    func testImportNowStagesFilesAndForgetsQueuedAssets() {
        let notifier = makeNotifier()
        _ = notifier.stagePendingAsset(asset("A/L0/001"))
        _ = notifier.stagePendingAsset(asset("B/L0/001"))

        XCTAssertTrue(run(notifier, .importNow))

        XCTAssertEqual(Set(engine.enqueued.map { $0.localID }), ["A/L0/001", "B/L0/001"])
        for entry in engine.enqueued {
            XCTAssertFalse(entry.localPath.hasPrefix("ph://"), "the engine can't read a ph:// path: \(entry.localPath)")
            XCTAssertTrue(entry.localPath.hasPrefix("/staged/"))
        }
        XCTAssertTrue(notifier.getPendingAssets().isEmpty, "queued assets must leave pending_import")
        XCTAssertEqual(afterImport.first?.0, .importNow)
        XCTAssertEqual(afterImport.first?.1, 2)
    }

    func testLivePhotoVideoIsQueuedAndLinkedInConfirmationMode() {
        let links = LinkRecorder()
        let notifier = AppleCameraRollImportNotifier(
            engine: engine,
            stager: FakeAssetStager(livePhotos: ["A/L0/001"]),
            afterImport: { _, _ in },
            linkPairedVideo: { still, video, stillName, videoName in
                links.record("\(still) -> \(video) (\(stillName), \(videoName))")
            }
        )
        XCTAssertFalse(notifier.autoImportEnabled, "confirmation mode")
        _ = notifier.stagePendingAsset(asset("A/L0/001"))
        _ = notifier.stagePendingAsset(asset("B/L0/001"))

        XCTAssertTrue(run(notifier, .importNow))

        let videoID = AppleCameraRollImportNotifier.pairedVideoLocalID(stillLocalID: "A/L0/001")
        XCTAssertEqual(videoID, "A/L0/001/pairedVideo")
        XCTAssertEqual(Set(engine.enqueued.map { $0.localID }), ["A/L0/001", "B/L0/001", videoID])
        XCTAssertEqual(links.entries, ["A/L0/001 -> A/L0/001/pairedVideo (IMG_A/L0.HEIC, IMG_LIVE.MOV)"])
    }

    func testFailedMotionTrackKeepsTheRowAndALaterPassQueuesOnlyTheVideo() {
        _ = engine.stagePendingImport(PendingImportItem(localId: "A/L0/001", filename: "a.heic", capturedAtUnix: 1, createdAtUnix: 0))
        let failing = AppleCameraRollImportNotifier(
            engine: engine,
            stager: FakeAssetStager(livePhotos: ["A/L0/001"], failingVideos: ["A/L0/001"]),
            afterImport: { _, _ in },
            linkPairedVideo: { _, _, _, _ in }
        )

        XCTAssertTrue(run(failing, .importNow))
        XCTAssertEqual(engine.enqueued.map { $0.localID }, ["A/L0/001"])
        XCTAssertEqual(failing.getPendingAssets().map(\.localId), ["A/L0/001"],
                       "the row must survive until the motion track is queued")

        let working = AppleCameraRollImportNotifier(
            engine: engine,
            stager: FakeAssetStager(livePhotos: ["A/L0/001"]),
            afterImport: { _, _ in },
            linkPairedVideo: { _, _, _, _ in }
        )
        XCTAssertTrue(run(working, .importNow))
        XCTAssertEqual(engine.enqueued.map { $0.localID }, ["A/L0/001", "A/L0/001/pairedVideo"],
                       "the already-queued still is not staged or queued again")
        XCTAssertTrue(working.getPendingAssets().isEmpty)
    }

    func testImportStagesAFewAssetsAtATime() {
        let stager = ConcurrencyProbeStager()
        let notifier = AppleCameraRollImportNotifier(engine: engine, stager: stager, afterImport: { _, _ in })
        for i in 0..<10 {
            _ = notifier.stagePendingAsset(asset("A/L0/\(i)"))
        }

        XCTAssertTrue(run(notifier, .importNow, timeout: 10))

        XCTAssertEqual(engine.enqueued.count, 10)
        XCTAssertLessThanOrEqual(stager.maxInFlight, AppleCameraRollImportNotifier.importChunkSize)
        XCTAssertGreaterThan(stager.maxInFlight, 0)
    }

    func testGalleryImportStagesInsteadOfEnqueueingAPhotoKitPath() async {
        let notifier = makeNotifier()
        let queued = await notifier.importAssets([
            PendingImportItem(localId: "G/L0/001", filename: "g.heic", capturedAtUnix: 1, createdAtUnix: 0)
        ])

        XCTAssertEqual(queued, 1)
        XCTAssertEqual(engine.enqueued.map { $0.localID }, ["G/L0/001"])
        XCTAssertTrue(engine.enqueued.allSatisfy { $0.localPath.hasPrefix("/staged/") })
    }

    func testGalleryReimportOfAQueuedAssetCountsAsQueued() async {
        let notifier = makeNotifier()
        let item = PendingImportItem(localId: "G/L0/002", filename: "g.heic", capturedAtUnix: 1, createdAtUnix: 0)

        let first = await notifier.importAssets([item])
        let again = await notifier.importAssets([item])

        XCTAssertEqual(first, 1)
        XCTAssertEqual(again, 1, "an asset already in the upload queue is not a failure")
        XCTAssertEqual(engine.enqueued.map { $0.localID }, ["G/L0/002"], "and is not queued twice")
    }

    func testGalleryReimportOfAFailedUploadQueuesItAgain() async {
        let notifier = makeNotifier()
        let item = PendingImportItem(localId: "G/L0/003", filename: "g.heic", capturedAtUnix: 1, createdAtUnix: 0)
        engine.statusOverrides["G/L0/003"] = "FAILED"

        let queued = await notifier.importAssets([item])

        XCTAssertEqual(queued, 1)
        XCTAssertEqual(engine.enqueued.map { $0.localID }, ["G/L0/003"],
                       "a FAILED upload is never picked again on its own, so it must be enqueued again")
        XCTAssertEqual(engine.mediaStatus(localID: "G/L0/003"), "PENDING")
    }

    func testUnreadableStatusStillQueuesTheAsset() async {
        let notifier = makeNotifier()
        engine.failStatusReads = true
        let item = PendingImportItem(localId: "G/L0/004", filename: "g.heic", capturedAtUnix: 1, createdAtUnix: 0)

        let queued = await notifier.importAssets([item])

        XCTAssertEqual(queued, 1)
        XCTAssertEqual(engine.enqueued.map { $0.localID }, ["G/L0/004"])
        XCTAssertNil(notifier.uploadStatus(localID: "G/L0/004"))
    }

    func testUploadStatusCountsOnlyRowsTheUploaderWillPick() {
        for status in ["PENDING", "IN_PROGRESS", "COMPLETED", "OFFLOADED"] {
            XCTAssertTrue(UploadStatus.isQueued(status), status)
        }
        for status in ["FAILED", "NOT_ENQUEUED", ""] {
            XCTAssertFalse(UploadStatus.isQueued(status), status)
        }
    }

    func testAssetWhoseStagingFailsStaysPending() {
        let notifier = makeNotifier(failing: ["B/L0/001"])
        _ = notifier.stagePendingAsset(asset("A/L0/001"))
        _ = notifier.stagePendingAsset(asset("B/L0/001"))

        XCTAssertTrue(run(notifier, .later))

        XCTAssertEqual(engine.enqueued.map { $0.localID }, ["A/L0/001"])
        XCTAssertEqual(notifier.getPendingAssets().map(\.localId), ["B/L0/001"])
        XCTAssertEqual(afterImport.first?.0, .later)
    }

    func testSkipSuppressesAndScansDoNotReofferTheAsset() {
        let notifier = makeNotifier()
        _ = notifier.stagePendingAsset(asset("A/L0/001"))

        XCTAssertTrue(run(notifier, .skip))

        XCTAssertTrue(notifier.isAssetSuppressed(identifier: "A/L0/001"))
        XCTAssertTrue(notifier.getPendingAssets().isEmpty)
        XCTAssertEqual(notifier.stagePendingAsset(asset("A/L0/001")), .suppressed,
                       "a skipped asset must not be staged again by a later scan")
        XCTAssertTrue(engine.enqueued.isEmpty)
    }

    func testActionsOnlyApplyToAssetsStagedBeforeTheNotification() {
        let notifier = makeNotifier()
        engine.clock = 100
        _ = notifier.stagePendingAsset(asset("A/L0/001"))
        engine.clock = 200
        _ = notifier.stagePendingAsset(asset("B/L0/001"))

        XCTAssertTrue(run(notifier, .skip, stagedBefore: 150))

        XCTAssertTrue(notifier.isAssetSuppressed(identifier: "A/L0/001"))
        XCTAssertFalse(notifier.isAssetSuppressed(identifier: "B/L0/001"))
        XCTAssertEqual(notifier.getPendingAssets().map(\.localId), ["B/L0/001"])
    }

    func testUnreadablePendingListLeavesEverythingAlone() {
        let notifier = makeNotifier()
        _ = notifier.stagePendingAsset(asset("A/L0/001"))
        engine.failListing = true

        XCTAssertFalse(run(notifier, .importNow))
        XCTAssertTrue(engine.enqueued.isEmpty)
    }

    func testImportAllPendingResumesAssetsLeftByAnEarlierProcess() {
        // Staged by a process that died before importing them.
        _ = engine.stagePendingImport(PendingImportItem(localId: "A/L0/001", filename: "a.heic", capturedAtUnix: 1, createdAtUnix: 1))
        let notifier = makeNotifier()

        let done = expectation(description: "imported")
        let count = HandledCount()
        notifier.importAllPending { queued in
            count.value = queued
            done.fulfill()
        }
        wait(for: [done], timeout: 2.0)

        XCTAssertEqual(count.value, 1)
        XCTAssertEqual(engine.enqueued.map { $0.localID }, ["A/L0/001"])
        XCTAssertTrue(notifier.getPendingAssets().isEmpty)
    }
}

/// Collects afterImport callbacks. A separate Sendable box, because the
/// callback is @Sendable and the XCTestCase itself is not.
private final class AfterImportRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var _calls: [(AppleCameraRollImportNotifier.Action, Int)] = []

    func record(_ action: AppleCameraRollImportNotifier.Action, _ queued: Int) {
        lock.lock()
        _calls.append((action, queued))
        lock.unlock()
    }

    var calls: [(AppleCameraRollImportNotifier.Action, Int)] {
        lock.lock()
        defer { lock.unlock() }
        return _calls
    }
}

/// Stages asynchronously with a short delay and records how many stagings
/// were in flight at once.
private final class ConcurrencyProbeStager: AssetStager, @unchecked Sendable {
    private let lock = NSLock()
    private var inFlight = 0
    private var _maxInFlight = 0

    var maxInFlight: Int {
        lock.lock()
        defer { lock.unlock() }
        return _maxInFlight
    }

    func stage(localIdentifier: String, filename: String, completion: @escaping @Sendable (String?) -> Void) {
        lock.lock()
        inFlight += 1
        _maxInFlight = max(_maxInFlight, inFlight)
        lock.unlock()
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.02) {
            self.lock.lock()
            self.inFlight -= 1
            self.lock.unlock()
            completion("/staged/\(localIdentifier.replacingOccurrences(of: "/", with: "_"))")
        }
    }
}

private final class LinkRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var _entries = [String]()

    func record(_ entry: String) {
        lock.lock()
        _entries.append(entry)
        lock.unlock()
    }

    var entries: [String] {
        lock.lock()
        defer { lock.unlock() }
        return _entries
    }
}

private final class HandledFlag: @unchecked Sendable {
    var value = false
}

private final class HandledCount: @unchecked Sendable {
    var value = -1
}
