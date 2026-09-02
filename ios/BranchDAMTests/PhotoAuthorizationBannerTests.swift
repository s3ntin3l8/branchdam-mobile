import XCTest
import Photos
import SwiftUI
@testable import BranchDAM

/// Recursively collect all subviews from a UIView hierarchy.
private extension UIView {
    var recursiveSubviews: [UIView] {
        subviews + subviews.flatMap { $0.recursiveSubviews }
    }
}

/// Tests for PhotoAuthorizationBanner — the banner shown when
/// camera-roll access is denied, restricted, or not yet determined.
///
/// Extracted from ContentView.swift to its own file (and made public)
/// so the unit test target can access it.
final class PhotoAuthorizationBannerTests: XCTestCase {

    func testTitleForDenied() {
        let banner = PhotoAuthorizationBanner(status: .denied)
        XCTAssertEqual(banner.bannerTitle, "Camera Roll Access Denied")
    }

    func testTitleForRestricted() {
        let banner = PhotoAuthorizationBanner(status: .restricted)
        XCTAssertEqual(banner.bannerTitle, "Camera Roll Access Restricted")
    }

    func testTitleForNotDetermined() {
        let banner = PhotoAuthorizationBanner(status: .notDetermined)
        XCTAssertEqual(banner.bannerTitle, "Camera Roll Access Needed")
    }

    func testTitleForAuthorized() {
        // The ContentView only shows the banner for notDetermined,
        // denied, restricted. But the banner itself can be
        // constructed for any status. Verify the title falls
        // through to the default for authorized (it should not be
        // "Denied" or "Restricted" — those are wrong for granted access).
        let banner = PhotoAuthorizationBanner(status: .authorized)
        XCTAssertEqual(banner.bannerTitle, "Camera Roll Access Needed")
    }

    /// The body must render a button whose label differs by status:
    /// - .notDetermined → "Grant Camera Roll Access"
    /// - .denied / .restricted → "Open Settings"
    ///
    /// We walk the view hierarchy to find UILabels containing the
    /// expected button text. SwiftUI may not bridge UIButton into
    /// the UIKit hierarchy, but Text views are always bridged as
    /// UILabels.
    func testButtonLabelPerStatus() {
        for status: PHAuthorizationStatus in [.notDetermined, .denied, .restricted] {
            let banner = PhotoAuthorizationBanner(status: status)
            let hosting = UIHostingController(rootView: banner)
            hosting.loadViewIfNeeded()

            let allLabels = hosting.view.recursiveSubviews.compactMap { $0 as? UILabel }
            let allText = allLabels.compactMap { $0.text }.joined(separator: " | ")

            switch status {
            case .notDetermined:
                XCTAssertTrue(
                    allLabels.contains { $0.text == "Grant Camera Roll Access" },
                    "notDetermined banner must contain 'Grant Camera Roll Access' label. Found: \(allText)"
                )
            case .denied, .restricted:
                XCTAssertTrue(
                    allLabels.contains { $0.text == "Open Settings" },
                    "\(status) banner must contain 'Open Settings' label. Found: \(allText)"
                )
            default:
                break
            }
        }
    }
}
