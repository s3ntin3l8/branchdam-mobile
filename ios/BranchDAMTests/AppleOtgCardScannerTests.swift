import XCTest
@testable import BranchDAM

final class AppleOtgCardScannerTests: XCTestCase {

    var tempDir: URL!

    override func setUp() {
        super.setUp()
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: tempDir)
        super.tearDown()
    }

    func testExtensionCategorization() {
        XCTAssertTrue(AppleOtgCandidate.isRawExtension("IMG_0001.CR3"))
        XCTAssertTrue(AppleOtgCandidate.isRawExtension("DSC_0001.ARW"))
        XCTAssertTrue(AppleOtgCandidate.isRawExtension("PXL_2026.DNG"))
        XCTAssertFalse(AppleOtgCandidate.isRawExtension("photo.jpg"))
        XCTAssertFalse(AppleOtgCandidate.isRawExtension("video.mp4"))

        XCTAssertTrue(AppleOtgCandidate.isVideoExtension("clip.MOV"))
        XCTAssertTrue(AppleOtgCandidate.isVideoExtension("movie.mp4"))
        XCTAssertFalse(AppleOtgCandidate.isVideoExtension("photo.heic"))

        XCTAssertTrue(AppleOtgCandidate.isSupportedMedia("raw.nef"))
        XCTAssertTrue(AppleOtgCandidate.isSupportedMedia("photo.jpeg"))
        XCTAssertFalse(AppleOtgCandidate.isSupportedMedia("notes.txt"))
    }

    func testScanDirectoryHierarchy() throws {
        let dcim = tempDir.appendingPathComponent("DCIM/100EOSR5", isDirectory: true)
        try FileManager.default.createDirectory(at: dcim, withIntermediateDirectories: true)

        let cr3Data = Data(repeating: 0x42, count: 1024)
        let jpgData = Data(repeating: 0x43, count: 512)
        let mp4Data = Data(repeating: 0x44, count: 2048)

        try cr3Data.write(to: dcim.appendingPathComponent("IMG_0001.CR3"))
        try jpgData.write(to: dcim.appendingPathComponent("IMG_0001.JPG"))
        try mp4Data.write(to: dcim.appendingPathComponent("MVI_0002.MP4"))

        let scanResult = AppleOtgCardScanner.scanDirectory(at: tempDir, deviceLabel: "CANON R5")

        XCTAssertEqual(scanResult.deviceLabel, "CANON R5")
        XCTAssertEqual(scanResult.totalCount, 3)
        XCTAssertEqual(scanResult.rawCount, 1)
        XCTAssertEqual(scanResult.jpegCount, 1)
        XCTAssertEqual(scanResult.videoCount, 1)
        XCTAssertEqual(scanResult.totalSizeBytes, 3584)
    }
}
