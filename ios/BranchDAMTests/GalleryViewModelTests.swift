import XCTest
import Photos
@testable import BranchDAM

final class GalleryViewModelTests: XCTestCase {

    @MainActor
    func testSelectionModeToggles() {
        let vm = GalleryViewModel()
        XCTAssertFalse(vm.isSelectionMode)
        XCTAssertTrue(vm.selectedIds.isEmpty)

        vm.toggleSelection(id: "asset-1")
        XCTAssertTrue(vm.selectedIds.contains("asset-1"))

        vm.toggleSelection(id: "asset-1")
        XCTAssertFalse(vm.selectedIds.contains("asset-1"))
    }

    @MainActor
    func testClearSelection() {
        let vm = GalleryViewModel()
        vm.toggleSelection(id: "asset-1")
        vm.toggleSelection(id: "asset-2")
        XCTAssertEqual(vm.selectedIds.count, 2)

        vm.clearSelection()
        XCTAssertTrue(vm.selectedIds.isEmpty)
    }
}
