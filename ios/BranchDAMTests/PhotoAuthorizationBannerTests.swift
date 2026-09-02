import XCTest
import Photos
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
        // The ContentView only shows the banner for notDetermined,
        // denied, restricted. But the banner itself can be
        // constructed for any status. Verify the title falls
        // through to the default for authorized (it should not be
        // "Denied" or "Restricted" — those are wrong for granted access).
        let banner = PhotoAuthorizationBanner(status: .authorized)
        XCTAssertEqual(banner.bannerTitle, "Camera Roll Access Needed")
    }

    func testBodyRendersWithoutCrashing() {
        // Force evaluation of `body` to verify the view can be
        // constructed in a test (catches missing `var body: some View`
        // declarations or broken modifier chains).
        for status: PHAuthorizationStatus in [.authorized, .denied, .restricted, .notDetermined] {
            let banner = PhotoAuthorizationBanner(status: status)
            _ = banner.body
        }
    }
}
