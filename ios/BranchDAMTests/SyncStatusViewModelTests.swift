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

    func testProgressLabelUsesTheOneBasedIndexAsIs() {
        // engine.go reports itemIndex as i+1: the first item is 1.
        XCTAssertEqual(SyncStatusViewModel.progressItemLabel(itemIndex: 1, totalItems: 3), "Item 1 of 3")
        XCTAssertEqual(SyncStatusViewModel.progressItemLabel(itemIndex: 3, totalItems: 3), "Item 3 of 3")
    }

    func testProgressLabelIsHiddenWithoutATotal() {
        XCTAssertNil(SyncStatusViewModel.progressItemLabel(itemIndex: 0, totalItems: 0))
    }

    func testProgressLabelNeverLeavesTheRange() {
        XCTAssertEqual(SyncStatusViewModel.progressItemLabel(itemIndex: 0, totalItems: 2), "Item 1 of 2")
        XCTAssertEqual(SyncStatusViewModel.progressItemLabel(itemIndex: 5, totalItems: 2), "Item 2 of 2")
    }
}
