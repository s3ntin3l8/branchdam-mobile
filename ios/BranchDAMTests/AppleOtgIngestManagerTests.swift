import XCTest
@testable import BranchDAM

final class AppleOtgIngestManagerTests: XCTestCase {

    var tempDir: URL!
    var stageDir: URL!

    override func setUp() {
        super.setUp()
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        stageDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "_stage", isDirectory: true)

        try? FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: stageDir, withIntermediateDirectories: true)

        _ = BranchDamCoreBridge.shared.initialize(
            dbPath: NSTemporaryDirectory() + "otg_ios_test.db",
            baseURL: "http://localhost:8080"
        )
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: tempDir)
        try? FileManager.default.removeItem(at: stageDir)
        super.tearDown()
    }

    func testCardDetectionTransitionsToAwaitingConfirmation() {
        let dcim = tempDir.appendingPathComponent("DCIM/100EOSR5", isDirectory: true)
        try? FileManager.default.createDirectory(at: dcim, withIntermediateDirectories: true)
        try? Data(repeating: 0x42, count: 1024).write(to: dcim.appendingPathComponent("IMG_0001.CR3"))

        let manager = AppleOtgIngestManager()
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

    func testConfirmImportPreservesRelativePaths() throws {
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

        let manager = AppleOtgIngestManager()
        let expectation = expectation(description: "Import completion")

        let cancellable = manager.$state.sink { state in
            if case .completed(let count, let bytes) = state {
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

    func testCancelImportResetsToIdle() {
        let manager = AppleOtgIngestManager()
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
    func testCancelImportHonoredByBackgroundCopy() throws {
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
        let manager = AppleOtgIngestManager()

        let observedLock = NSLock()
        var observedStates: [AppleOtgState] = []
        var didCancel = false

        // Cancel the moment the loop publishes its first `.ingesting`
        // state — that lands squarely mid-flight regardless of how
        // fast the host disk is.
        let cancellable = manager.$state.sink { state in
            observedLock.lock()
            observedStates.append(state)
            let sawIngesting = !didCancel && {
                if case .ingesting = state { return true }
                return false
            }()
            observedLock.unlock()
            if sawIngesting {
                observedLock.lock()
                didCancel = true
                observedLock.unlock()
                DispatchQueue.main.async { manager.cancelImport() }
            }
        }

        manager.confirmImport(scanResult: scanResult, stageDirectory: stageDir)

        let idleExpectation = expectation(description: "Manager reaches idle after cancel")
        let poller = DispatchQueue(label: "test.otg.cancel.poller")
        poller.async {
            let deadline = Date().addingTimeInterval(5.0)
            while Date() < deadline {
                observedLock.lock()
                let snapshot = observedStates
                observedLock.unlock()
                if snapshot.contains(where: { if case .idle = $0 { return true } else { return false } }) {
                    idleExpectation.fulfill()
                    return
                }
                Thread.sleep(forTimeInterval: 0.01)
            }
        }
        wait(for: [idleExpectation], timeout: 5.0)
        cancellable.cancel()

        observedLock.lock()
        let snapshot = observedStates
        let didCancelFlag = didCancel
        observedLock.unlock()

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
    func testConcurrentCancelAndStateUpdateStress() {
        let manager = AppleOtgIngestManager()
        let iterations = 1000
        let bgQueue = DispatchQueue(label: "test.otg.stress.bg", attributes: .concurrent)
        let group = DispatchGroup()

        for _ in 0..<iterations {
            group.enter()
            bgQueue.async {
                _ = manager.state
                group.leave()
            }
            group.enter()
            DispatchQueue.main.async {
                manager.cancelImport()
                group.leave()
            }
        }

        let waitResult = group.wait(timeout: .now() + 30.0)
        XCTAssertEqual(waitResult, .success, "Stress test timed out — possible deadlock or starvation")
        XCTAssertEqual(manager.state, .idle, "Final state should be .idle after 1000 cancel iterations")
    }
}
