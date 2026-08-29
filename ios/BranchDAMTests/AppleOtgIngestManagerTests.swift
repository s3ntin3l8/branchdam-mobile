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
}
