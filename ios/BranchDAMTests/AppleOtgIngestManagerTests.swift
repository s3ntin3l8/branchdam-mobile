import XCTest
@testable import BranchDAM

/// Engine stand-in: hashes, prior hashes, enqueue refusals and upload
/// statuses are scripted per test, so no test depends on the Go framework.
final class FakeOtgEngine: AppleOtgIngestEngine, @unchecked Sendable {
    private let lock = NSLock()
    private var _priorByFileName: [String: String] = [:]
    private var _refusedFileNames: Set<String> = []
    private var _enqueuedPaths: [String] = []
    private var _statuses: [String: String] = [:]
    let freshHash = String(repeating: "f", count: 64)

    var priorByFileName: [String: String] {
        get { lock.withLock { _priorByFileName } }
        set { lock.withLock { _priorByFileName = newValue } }
    }
    var refusedFileNames: Set<String> {
        get { lock.withLock { _refusedFileNames } }
        set { lock.withLock { _refusedFileNames = newValue } }
    }
    var statuses: [String: String] {
        get { lock.withLock { _statuses } }
        set { lock.withLock { _statuses = newValue } }
    }
    var enqueuedPaths: [String] { lock.withLock { _enqueuedPaths } }

    func computeHashes(localPath: String) -> String { freshHash }

    func lookupBlake3ForLocalID(localID: String) -> String {
        let priors = priorByFileName
        return priors.first(where: { localID.contains($0.key) })?.value ?? ""
    }

    func enqueueMedia(localPath: String, filename: String, capturedAtUnix: Int64, localID: String) -> Int64 {
        if refusedFileNames.contains(filename) { return 0 }
        lock.withLock { _enqueuedPaths.append(localPath) }
        return 1
    }

    func mediaStatuses() -> [String: String] { statuses }
}

final class AppleOtgIngestManagerTests: XCTestCase {

    var tempDir: URL!
    var stageDir: URL!
    var stagingRoot: URL!

    override func setUp() {
        super.setUp()
        let temp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let stage = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "_stage", isDirectory: true)
        stagingRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
            .appendingPathComponent("otg_staging", isDirectory: true)

        try? FileManager.default.createDirectory(at: temp, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: stage, withIntermediateDirectories: true)

        _ = BranchDamCoreBridge.shared.initialize(
            dbPath: NSTemporaryDirectory() + "otg_ios_test.db",
            baseURL: "http://localhost:8080"
        )
        tempDir = temp
        stageDir = stage
    }

    override func tearDown() {
        if let temp = tempDir { try? FileManager.default.removeItem(at: temp) }
        if let stage = stageDir { try? FileManager.default.removeItem(at: stage) }
        if let root = stagingRoot { try? FileManager.default.removeItem(at: root.deletingLastPathComponent()) }
        super.tearDown()
    }

    @MainActor func testCardDetectionTransitionsToAwaitingConfirmation() {
        let dcim = tempDir.appendingPathComponent("DCIM/100EOSR5", isDirectory: true)
        try? FileManager.default.createDirectory(at: dcim, withIntermediateDirectories: true)
        try? Data(repeating: 0x42, count: 1024).write(to: dcim.appendingPathComponent("IMG_0001.CR3"))

        let manager = AppleOtgIngestManager(engine: FakeOtgEngine(), stagingRoot: stagingRoot)
        let expectation = expectation(description: "Scan completion")

        let cancellable = manager.$state.sink { state in
            if case .awaitingConfirmation(let result) = state {
                XCTAssertEqual(result.deviceLabel, "CANON R5")
                XCTAssertEqual(result.totalCount, 1)
                expectation.fulfill()
            }
        }

        manager.onCardDetected(deviceLabel: "CANON R5", directory: tempDir)
        wait(for: [expectation], timeout: 2.0)
        cancellable.cancel()
    }

    @MainActor func testConfirmImportPreservesRelativePaths() throws {
        let folder1 = tempDir.appendingPathComponent("DCIM/100EOSR5", isDirectory: true)
        let folder2 = tempDir.appendingPathComponent("DCIM/101EOSR5", isDirectory: true)
        try FileManager.default.createDirectory(at: folder1, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: folder2, withIntermediateDirectories: true)

        let file1 = folder1.appendingPathComponent("IMG_0001.CR3")
        let file2 = folder2.appendingPathComponent("IMG_0001.CR3")

        try "photo_100".write(to: file1, atomically: true, encoding: .utf8)
        try "photo_101".write(to: file2, atomically: true, encoding: .utf8)

        let candidate1 = AppleOtgCandidate(
            url: file1,
            relativePath: "DCIM/100EOSR5/IMG_0001.CR3",
            fileName: "IMG_0001.CR3",
            sizeBytes: 9,
            lastModifiedUnix: 1700000000,
            isRaw: true,
            isVideo: false
        )
        let candidate2 = AppleOtgCandidate(
            url: file2,
            relativePath: "DCIM/101EOSR5/IMG_0001.CR3",
            fileName: "IMG_0001.CR3",
            sizeBytes: 9,
            lastModifiedUnix: 1700000010,
            isRaw: true,
            isVideo: false
        )

        let scanResult = AppleOtgScanResult(deviceLabel: "CANON R5", rootUrl: tempDir, candidates: [candidate1, candidate2])

        let manager = AppleOtgIngestManager(engine: FakeOtgEngine(), stagingRoot: stagingRoot)
        let expectation = expectation(description: "Import completion")

        let cancellable = manager.$state.sink { state in
            if case .completed(let count, let bytes, let failures) = state {
                XCTAssertEqual(failures, [])
                XCTAssertEqual(count, 2)
                XCTAssertEqual(bytes, 18)
                expectation.fulfill()
            }
        }

        manager.confirmImport(scanResult: scanResult, stageDirectory: stageDir)
        wait(for: [expectation], timeout: 2.0)
        cancellable.cancel()

        let staged1 = stageDir.appendingPathComponent("DCIM/100EOSR5/IMG_0001.CR3")
        let staged2 = stageDir.appendingPathComponent("DCIM/101EOSR5/IMG_0001.CR3")

        XCTAssertTrue(FileManager.default.fileExists(atPath: staged1.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: staged2.path))

        XCTAssertEqual(try String(contentsOf: staged1, encoding: .utf8), "photo_100")
        XCTAssertEqual(try String(contentsOf: staged2, encoding: .utf8), "photo_101")
    }

    @MainActor func testCancelImportResetsToIdle() {
        let manager = AppleOtgIngestManager(engine: FakeOtgEngine(), stagingRoot: stagingRoot)
        manager.cancelImport()
        XCTAssertEqual(manager.state, .idle)
    }

    /// T2-9: cancelling from the main thread while a background
    /// `confirmImport` is mid-copy must actually halt the copy loop.
    /// Before the `isCancelled` flag was wrapped in
    /// `OSAllocatedUnfairLock<Bool>` (deployment target is iOS 17,
    /// below the `Synchronization` floor of iOS 18), the Swift 6
    /// strict concurrency checker flagged the cross-thread read/write
    /// as a data race, and on platforms where Bool is not naturally
    /// atomic the cancel could silently fail to land before the next
    /// loop iteration.
    @MainActor func testCancelImportHonoredByBackgroundCopy() throws {
        let folder = tempDir.appendingPathComponent("DCIM/100EOSR5", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)

        var candidates: [AppleOtgCandidate] = []
        for index in 0..<120 {
            let fileName = "IMG_\(String(format: "%04d", index)).CR3"
            let file = folder.appendingPathComponent(fileName)
            try Data(repeating: 0x42, count: 8192).write(to: file)
            candidates.append(
                AppleOtgCandidate(
                    url: file,
                    relativePath: "DCIM/100EOSR5/\(fileName)",
                    fileName: fileName,
                    sizeBytes: 8192,
                    lastModifiedUnix: 1700000000,
                    isRaw: true,
                    isVideo: false
                )
            )
        }

        let scanResult = AppleOtgScanResult(deviceLabel: "CANON R5", rootUrl: tempDir, candidates: candidates)
        let manager = AppleOtgIngestManager(engine: FakeOtgEngine(), stagingRoot: stagingRoot)

        final class TestStateBox: @unchecked Sendable {
            let lock = NSLock()
            var observedStates: [AppleOtgState] = []
            var didCancel = false
        }
        let box = TestStateBox()

        // Cancel the moment the loop publishes its first `.ingesting`
        // state — that lands squarely mid-flight regardless of how
        // fast the host disk is.
        let cancellable = manager.$state.sink { state in
            box.lock.lock()
            box.observedStates.append(state)
            let sawIngesting = !box.didCancel && {
                if case .ingesting = state { return true }
                return false
            }()
            box.lock.unlock()
            if sawIngesting {
                box.lock.lock()
                box.didCancel = true
                box.lock.unlock()
                DispatchQueue.main.async { manager.cancelImport() }
            }
        }

        manager.confirmImport(scanResult: scanResult, stageDirectory: stageDir)

        let idleExpectation = expectation(description: "Manager reaches idle after cancel")
        let poller = DispatchQueue(label: "test.otg.cancel.poller")
        poller.async {
            let deadline = Date().addingTimeInterval(5.0)
            while Date() < deadline {
                box.lock.lock()
                let didCancelNow = box.didCancel
                let snapshot = box.observedStates
                box.lock.unlock()
                if didCancelNow, let ingestingIndex = snapshot.firstIndex(where: { if case .ingesting = $0 { return true } else { return false } }) {
                    if snapshot[ingestingIndex...].contains(where: { if case .idle = $0 { return true } else { return false } }) {
                        idleExpectation.fulfill()
                        return
                    }
                }
                Thread.sleep(forTimeInterval: 0.01)
            }
        }
        wait(for: [idleExpectation], timeout: 5.0)
        cancellable.cancel()

        box.lock.lock()
        let snapshot = box.observedStates
        let didCancelFlag = box.didCancel
        box.lock.unlock()

        XCTAssertTrue(didCancelFlag, "Test should have observed .ingesting and issued cancel mid-flight")
        XCTAssertFalse(
            snapshot.contains(where: { if case .completed = $0 { return true } else { return false } }),
            "Cancel should have prevented .completed state, but .completed was observed"
        )
        XCTAssertEqual(manager.state, .idle, "Final state should be .idle after cancel")
    }

    /// T2-9: hammer the manager with 1000 concurrent cancel calls
    /// interleaved with state reads on a background queue. The Swift 6
    /// strict concurrency checker flagged the unguarded `isCancelled`
    /// `var` as a data race; wrapping it in `OSAllocatedUnfairLock<Bool>`
    /// removes the race and the runtime stress test asserts no
    /// iteration crashes, deadlocks, or produces a torn state read.
    @MainActor func testConcurrentCancelAndStateUpdateStress() {
        let manager = AppleOtgIngestManager(engine: FakeOtgEngine(), stagingRoot: stagingRoot)
        let iterations = 1000
        let bgQueue = DispatchQueue(label: "test.otg.stress.bg", attributes: .concurrent)
        let group = DispatchGroup()

        for _ in 0..<iterations {
            group.enter()
            bgQueue.async {
                Task { @MainActor in
                    _ = manager.state
                }
                group.leave()
            }
            group.enter()
            DispatchQueue.main.async {
                manager.cancelImport()
                group.leave()
            }
        }

        let exp = expectation(description: "Stress test completes")
        group.notify(queue: .main) {
            exp.fulfill()
        }

        wait(for: [exp], timeout: 10.0)
        XCTAssertEqual(manager.state, .idle, "Final state should be .idle after 1000 cancel iterations")
    }

    // MARK: - M10: per-file failures, hash rejection, staging

    private func writeCandidate(_ relativePath: String, bytes: Int = 64) throws -> AppleOtgCandidate {
        let url = tempDir.appendingPathComponent(relativePath)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(repeating: 0x42, count: bytes).write(to: url)
        let name = url.lastPathComponent
        return AppleOtgCandidate(
            url: url,
            relativePath: relativePath,
            fileName: name,
            sizeBytes: Int64(bytes),
            lastModifiedUnix: 1700000000,
            isRaw: AppleOtgCandidate.isRawExtension(name),
            isVideo: false
        )
    }

    @MainActor private func runImport(
        _ manager: AppleOtgIngestManager,
        _ candidates: [AppleOtgCandidate],
        stageDirectory: URL? = nil
    ) -> (count: Int, bytes: Int64, failures: [AppleOtgFileFailure])? {
        let done = expectation(description: "Import completion")
        var outcome: (Int, Int64, [AppleOtgFileFailure])?
        let cancellable = manager.$state.sink { state in
            if case .completed(let count, let bytes, let failures) = state {
                outcome = (count, bytes, failures)
                done.fulfill()
            }
        }
        manager.confirmImport(
            scanResult: AppleOtgScanResult(deviceLabel: "CARD", rootUrl: tempDir, candidates: candidates),
            stageDirectory: stageDirectory
        )
        wait(for: [done], timeout: 5.0)
        cancellable.cancel()
        return outcome.map { (count: $0.0, bytes: $0.1, failures: $0.2) }
    }

    @MainActor func testHashMismatchRejectsTheFileAndTheRunContinues() throws {
        let bad = try writeCandidate("DCIM/100CANON/IMG_0001.CR3")
        let good = try writeCandidate("DCIM/100CANON/IMG_0002.CR3", bytes: 32)
        let engine = FakeOtgEngine()
        engine.priorByFileName = ["IMG_0001.CR3": String(repeating: "a", count: 64)]
        let manager = AppleOtgIngestManager(engine: engine, stagingRoot: stagingRoot)

        let result = try XCTUnwrap(runImport(manager, [bad, good], stageDirectory: stageDir))

        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result.bytes, 32)
        XCTAssertEqual(result.failures.map(\.fileName), ["IMG_0001.CR3"])
        XCTAssertTrue(result.failures[0].message.contains("BLAKE3 mismatch"))
        XCTAssertFalse(FileManager.default.fileExists(atPath: stageDir.appendingPathComponent(bad.relativePath).path),
                       "a rejected copy must not stay staged")
        XCTAssertEqual(engine.enqueuedPaths, [stageDir.appendingPathComponent(good.relativePath).path])
    }

    @MainActor func testCopyFailureDoesNotAbortTheRun() throws {
        let missing = AppleOtgCandidate(
            url: tempDir.appendingPathComponent("DCIM/GONE.JPG"),
            relativePath: "DCIM/GONE.JPG",
            fileName: "GONE.JPG",
            sizeBytes: 10,
            lastModifiedUnix: 1700000000,
            isRaw: false,
            isVideo: false
        )
        let good = try writeCandidate("DCIM/IMG_0003.JPG")
        let manager = AppleOtgIngestManager(engine: FakeOtgEngine(), stagingRoot: stagingRoot)

        let result = try XCTUnwrap(runImport(manager, [missing, good], stageDirectory: stageDir))

        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result.failures.map(\.fileName), ["GONE.JPG"])
        XCTAssertTrue(FileManager.default.fileExists(atPath: stageDir.appendingPathComponent(good.relativePath).path))
    }

    @MainActor func testEnqueueRefusalDeletesTheStagedCopy() throws {
        let refused = try writeCandidate("DCIM/IMG_0004.JPG")
        let engine = FakeOtgEngine()
        engine.refusedFileNames = ["IMG_0004.JPG"]
        let manager = AppleOtgIngestManager(engine: engine, stagingRoot: stagingRoot)

        let result = try XCTUnwrap(runImport(manager, [refused], stageDirectory: stageDir))

        XCTAssertEqual(result.count, 0)
        XCTAssertEqual(result.failures.count, 1)
        XCTAssertFalse(FileManager.default.fileExists(atPath: stageDir.appendingPathComponent(refused.relativePath).path))
    }

    @MainActor func testDefaultImportStagesInAFreshSessionUnderTheStagingRoot() throws {
        let candidate = try writeCandidate("DCIM/IMG_0005.JPG")
        let engine = FakeOtgEngine()
        let manager = AppleOtgIngestManager(engine: engine, stagingRoot: stagingRoot)

        let result = try XCTUnwrap(runImport(manager, [candidate]))

        XCTAssertEqual(result.count, 1)
        let staged = try XCTUnwrap(engine.enqueuedPaths.first)
        XCTAssertTrue(staged.hasPrefix(stagingRoot.path + "/"), staged)
        XCTAssertTrue(staged.hasSuffix("/DCIM/IMG_0005.JPG"), staged)
        XCTAssertFalse(FileManager.default.fileExists(atPath: staged + ".part"))
        let excluded = try stagingRoot.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup
        XCTAssertEqual(excluded, true)
    }

    func testDefaultStagingRootIsNotPurgeableTemp() {
        let root = AppleOtgStaging.defaultRoot().standardizedFileURL.path
        XCTAssertTrue(root.contains("/Library/Application Support/"), root)
        XCTAssertFalse(root.hasPrefix(FileManager.default.temporaryDirectory.standardizedFileURL.path), root)
    }

    func testStagedURLRejectsPathsThatEscapeTheSession() {
        let session = URL(fileURLWithPath: "/tmp/s1", isDirectory: true)
        XCTAssertEqual(AppleOtgStaging.stagedURL(in: session, relativePath: "DCIM/IMG_0001.CR3")?.path, "/tmp/s1/DCIM/IMG_0001.CR3")
        for bad in ["", "/etc/passwd", "../x.jpg", "DCIM/../../x.jpg", "DCIM/./x.jpg", "DCIM//x.jpg"] {
            XCTAssertNil(AppleOtgStaging.stagedURL(in: session, relativePath: bad), bad)
        }
    }

    func testSweepDeletesOnlyUploadedCopies() throws {
        let fm = FileManager.default
        let session = try AppleOtgStaging.newSessionDirectory(in: stagingRoot)
        let done = session.appendingPathComponent("DCIM/DONE.JPG")
        let pending = session.appendingPathComponent("DCIM/PENDING.JPG")
        let moved = session.appendingPathComponent("DCIM/MOVED.JPG")
        let unknown = session.appendingPathComponent("DCIM/UNKNOWN.JPG")
        try fm.createDirectory(at: done.deletingLastPathComponent(), withIntermediateDirectories: true)
        for url in [done, pending, moved, unknown] { try Data([1]).write(to: url) }
        let key = "\(session.lastPathComponent)/DCIM/MOVED.JPG"

        let removed = AppleOtgStaging.sweep(
            root: stagingRoot,
            statuses: [
                done.path: "COMPLETED",
                pending.path: "PENDING",
                // The app container path changes across updates: matched
                // on the part below otg_staging.
                "/var/mobile/Containers/Data/Application/OLD/Library/Application Support/otg_staging/\(key)": "COMPLETED",
            ],
            now: Date().addingTimeInterval(30 * 24 * 60 * 60)
        )

        XCTAssertEqual(removed, 3, "done, moved and the month-old unknown copy")
        XCTAssertFalse(fm.fileExists(atPath: done.path))
        XCTAssertFalse(fm.fileExists(atPath: moved.path))
        XCTAssertTrue(fm.fileExists(atPath: pending.path))
    }

    func testSweepAfterSyncRemovesUploadedCopies() throws {
        let fm = FileManager.default
        let session = try AppleOtgStaging.newSessionDirectory(in: stagingRoot)
        let done = session.appendingPathComponent("DCIM/DONE.JPG")
        let pending = session.appendingPathComponent("DCIM/PENDING.JPG")
        try fm.createDirectory(at: done.deletingLastPathComponent(), withIntermediateDirectories: true)
        for url in [done, pending] { try Data([1]).write(to: url) }
        let engine = FakeOtgEngine()
        engine.statuses = [done.path: "COMPLETED", pending.path: "PENDING"]

        XCTAssertEqual(AppleOtgStaging.sweepAfterSync(engine: engine, root: stagingRoot), 1)
        XCTAssertFalse(fm.fileExists(atPath: done.path))
        XCTAssertTrue(fm.fileExists(atPath: pending.path))
    }

    func testSweepAfterSyncWithoutStagingIsANoOp() {
        let missing = stagingRoot.appendingPathComponent("never-created")
        XCTAssertEqual(AppleOtgStaging.sweepAfterSync(engine: FakeOtgEngine(), root: missing), 0)
    }

    func testSweepKeepsUnknownCopiesWhenTheEngineDidNotAnswer() throws {
        let session = try AppleOtgStaging.newSessionDirectory(in: stagingRoot)
        let file = session.appendingPathComponent("IMG.JPG")
        try Data([1]).write(to: file)
        AppleOtgStaging.sweep(root: stagingRoot, statuses: [:], now: Date().addingTimeInterval(365 * 24 * 60 * 60))
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
    }

    @MainActor func testPickedFolderWithoutMediaReportsAnError() {
        let empty = tempDir.appendingPathComponent("EMPTY", isDirectory: true)
        try? FileManager.default.createDirectory(at: empty, withIntermediateDirectories: true)
        let manager = AppleOtgIngestManager(engine: FakeOtgEngine(), stagingRoot: stagingRoot)
        let reported = expectation(description: "error state")
        let cancellable = manager.$state.sink { state in
            if case .error = state { reported.fulfill() }
        }
        manager.onCardFolderPicked(empty)
        wait(for: [reported], timeout: 2.0)
        cancellable.cancel()
    }

    func testLocalIDSeparatesAReusedNameOnAReformattedCard() {
        let url = URL(fileURLWithPath: "/Volumes/CARD/DCIM/IMG_0001.CR3")
        let first = AppleOtgCandidate(url: url, relativePath: "DCIM/IMG_0001.CR3", fileName: "IMG_0001.CR3",
                                      sizeBytes: 100, lastModifiedUnix: 1700000000, isRaw: true, isVideo: false)
        let newShot = AppleOtgCandidate(url: url, relativePath: "DCIM/IMG_0001.CR3", fileName: "IMG_0001.CR3",
                                        sizeBytes: 120, lastModifiedUnix: 1800000000, isRaw: true, isVideo: false)
        XCTAssertNotEqual(first.localID, newShot.localID)
        XCTAssertTrue(first.localID.hasPrefix(url.absoluteString))
    }
}
