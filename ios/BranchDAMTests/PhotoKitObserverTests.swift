import XCTest
@testable import BranchDAM

final class PhotoKitObserverTests: XCTestCase {

    func testDiscoveredAssetModel() {
        let asset = DiscoveredAsset(
            localIdentifier: "TEST-UUID-1234",
            filename: "IMG_0042.DNG",
            creationDateUnix: 1724000000,
            isRaw: true,
            isVideo: false,
            pixelWidth: 8064,
            pixelHeight: 6048
        )

        XCTAssertEqual(asset.localIdentifier, "TEST-UUID-1234")
        XCTAssertEqual(asset.filename, "IMG_0042.DNG")
        XCTAssertEqual(asset.isRaw, true)
        XCTAssertEqual(asset.isVideo, false)
        XCTAssertEqual(asset.pixelWidth, 8064)
    }

    func testStagedMediaDirectoryAndPrune() {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let partFile = tempDir.appendingPathComponent("test.part")
        let oldFile = tempDir.appendingPathComponent("old.mov")

        try? "part-data".write(to: partFile, atomically: true, encoding: .utf8)
        try? "old-data".write(to: oldFile, atomically: true, encoding: .utf8)

        // statusMap with empty status allows old completed/untracked files to be pruned
        PhotoKitObserver.pruneStagedMediaDirectory(directory: tempDir, maxAgeSeconds: -1, statusMap: [:])

        XCTAssertFalse(FileManager.default.fileExists(atPath: partFile.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: oldFile.path))
    }

    func testPruneStagedMediaDirectory_PreservesFreshAndPendingFiles() {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let freshFile = tempDir.appendingPathComponent("fresh.mov")
        let partFile = tempDir.appendingPathComponent("test.part")

        try? "fresh-data".write(to: freshFile, atomically: true, encoding: .utf8)
        try? "part-data".write(to: partFile, atomically: true, encoding: .utf8)

        PhotoKitObserver.pruneStagedMediaDirectory(directory: tempDir, maxAgeSeconds: 3600, statusMap: [:])

        XCTAssertTrue(FileManager.default.fileExists(atPath: freshFile.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: partFile.path))
    }

    func testPruneStagedMediaDirectory_PreservesPendingAndFailedFiles() {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let pendingFile = tempDir.appendingPathComponent("pending.mov")
        let failedFile = tempDir.appendingPathComponent("failed.mov")
        let completedFile = tempDir.appendingPathComponent("completed.mov")

        try? "pending-data".write(to: pendingFile, atomically: true, encoding: .utf8)
        try? "failed-data".write(to: failedFile, atomically: true, encoding: .utf8)
        try? "completed-data".write(to: completedFile, atomically: true, encoding: .utf8)

        let statusMap: [String: String] = [
            pendingFile.path: "PENDING",
            failedFile.path: "FAILED",
            completedFile.path: "COMPLETED"
        ]

        PhotoKitObserver.pruneStagedMediaDirectory(directory: tempDir, maxAgeSeconds: -1, statusMap: statusMap)

        XCTAssertTrue(FileManager.default.fileExists(atPath: pendingFile.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: failedFile.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: completedFile.path))
    }

    func testStagedMediaDirectoryIsInApplicationSupportAndExcludedFromBackup() throws {
        let dir = PhotoKitObserver.stagedMediaDirectory()
        let path = dir.standardizedFileURL.path
        XCTAssertTrue(path.contains("/Library/Application Support/"), path)
        XCTAssertFalse(path.contains("/Library/Caches/"), path)
        XCTAssertTrue(FileManager.default.fileExists(atPath: dir.path))
        let excluded = try dir.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup
        XCTAssertEqual(excluded, true)
    }

    func testPruneStagedMediaDirectories_SweepsNewAndLegacyAndRemovesEmptyLegacy() throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let newDir = root.appendingPathComponent("new", isDirectory: true)
        let legacyDir = root.appendingPathComponent("legacy", isDirectory: true)
        try fm.createDirectory(at: newDir, withIntermediateDirectories: true)
        try fm.createDirectory(at: legacyDir, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: root) }

        let newPending = newDir.appendingPathComponent("pending.mov")
        let newCompleted = newDir.appendingPathComponent("completed.mov")
        let legacyPending = legacyDir.appendingPathComponent("legacy_pending.mov")
        let legacyCompleted = legacyDir.appendingPathComponent("legacy_completed.mov")

        for url in [newPending, newCompleted, legacyPending, legacyCompleted] {
            try "data".write(to: url, atomically: true, encoding: .utf8)
        }

        let initialStatuses: [String: String] = [
            newPending.path: "PENDING",
            newCompleted.path: "COMPLETED",
            legacyPending.path: "PENDING",
            legacyCompleted.path: "COMPLETED"
        ]

        PhotoKitObserver.pruneStagedMediaDirectories(
            directory: newDir,
            legacyDirectory: legacyDir,
            maxAgeSeconds: -1,
            statusMap: initialStatuses
        )

        XCTAssertTrue(fm.fileExists(atPath: newPending.path))
        XCTAssertFalse(fm.fileExists(atPath: newCompleted.path))
        XCTAssertTrue(fm.fileExists(atPath: legacyPending.path))
        XCTAssertFalse(fm.fileExists(atPath: legacyCompleted.path))
        XCTAssertTrue(fm.fileExists(atPath: legacyDir.path))

        let finalStatuses: [String: String] = [
            newPending.path: "PENDING",
            legacyPending.path: "COMPLETED"
        ]

        PhotoKitObserver.pruneStagedMediaDirectories(
            directory: newDir,
            legacyDirectory: legacyDir,
            maxAgeSeconds: -1,
            statusMap: finalStatuses
        )

        XCTAssertTrue(fm.fileExists(atPath: newPending.path))
        XCTAssertFalse(fm.fileExists(atPath: legacyPending.path))
        XCTAssertFalse(fm.fileExists(atPath: legacyDir.path))
        XCTAssertTrue(fm.fileExists(atPath: newDir.path))
    }
}
