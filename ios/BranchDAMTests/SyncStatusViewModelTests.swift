import XCTest
@testable import BranchDAM

final class SyncStatusViewModelTests: XCTestCase {

    @MainActor
    func testSpeedFormatting() {
        let vm = SyncStatusViewModel()
        XCTAssertEqual(vm.formatSpeed(0), "0 KB/s")
        XCTAssertEqual(vm.formatSpeed(512 * 1024), "512.0 KB/s")
        XCTAssertEqual(vm.formatSpeed(2.5 * 1024 * 1024), "2.5 MB/s")
    }

    @MainActor
    func testBytesFormatting() {
        let vm = SyncStatusViewModel()
        XCTAssertEqual(vm.formatBytes(0), "0 MB")
        XCTAssertEqual(vm.formatBytes(1024 * 1024 * 15), "15.0 MB")
    }

    @MainActor
    func testCancelSyncFlipsState() {
        let vm = SyncStatusViewModel()
        vm.isSyncing = true
        vm.cancelSync()

        XCTAssertFalse(vm.isSyncing)
        XCTAssertNil(vm.activeProgress)
        XCTAssertEqual(vm.syncResultMessage, "Sync cancelled by user")
    }
}
