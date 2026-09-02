import XCTest
import Photos
import SwiftUI
@testable import BranchDAM

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
        let banner = PhotoAuthorizationBanner(status: .authorized)
        XCTAssertEqual(banner.bannerTitle, "Camera Roll Access Needed")
    }

    /// The button label must differ by status:
    /// - .notDetermined → "Grant Camera Roll Access"
    /// - .denied / .restricted / .authorized → "Open Settings"
    ///
    /// Tests the buttonLabel property directly (same pattern as
    /// bannerTitle) rather than walking the UIKit view hierarchy,
    /// which is fragile across simulator environments.
    func testButtonLabelPerStatus() {
        XCTAssertEqual(PhotoAuthorizationBanner(status: .notDetermined).buttonLabel,
                       "Grant Camera Roll Access")
        XCTAssertEqual(PhotoAuthorizationBanner(status: .denied).buttonLabel,
                       "Open Settings")
        XCTAssertEqual(PhotoAuthorizationBanner(status: .restricted).buttonLabel,
                       "Open Settings")
        XCTAssertEqual(PhotoAuthorizationBanner(status: .authorized).buttonLabel,
                       "Open Settings")
    }

    func testBodyRendersWithoutCrashing() {
        for status: PHAuthorizationStatus in [.authorized, .denied, .restricted, .notDetermined] {
            _ = PhotoAuthorizationBanner(status: status).body
        }
    }
}
