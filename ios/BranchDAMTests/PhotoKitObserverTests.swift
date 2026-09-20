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

        PhotoKitObserver.pruneStagedMediaDirectory(directory: tempDir, maxAgeSeconds: -1)

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

        PhotoKitObserver.pruneStagedMediaDirectory(directory: tempDir, maxAgeSeconds: 3600)

        XCTAssertTrue(FileManager.default.fileExists(atPath: freshFile.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: partFile.path))
    }
}
