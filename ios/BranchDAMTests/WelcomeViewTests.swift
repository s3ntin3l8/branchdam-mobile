import XCTest
import Photos
@testable import BranchDAM

/// Tests for the persisted onboarding flag and the Welcome screen's
/// photo-authorization state mapping.
final class WelcomeViewTests: XCTestCase {

    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "WelcomeViewTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    func testKeyRawValue() {
        XCTAssertEqual(BranchDamKeys.onboardingCompleted.rawValue, "branchdam_onboarding_completed")
    }

    func testNotCompletedByDefault() {
        XCTAssertFalse(OnboardingState.isCompleted(defaults: defaults))
    }

    func testMarkCompletedPersistsUnderTheKey() {
        OnboardingState.markCompleted(defaults: defaults)
        XCTAssertTrue(OnboardingState.isCompleted(defaults: defaults))
        XCTAssertTrue(defaults.bool(forKey: "branchdam_onboarding_completed"))
    }

    func testSetUpLaterFlagSkipsWelcomeAtLaunchEvenWhenNotDetermined() {
        XCTAssertFalse(OnboardingState.shouldSkipWelcome(status: .notDetermined, defaults: defaults))
        OnboardingState.markCompleted(defaults: defaults)
        XCTAssertTrue(OnboardingState.shouldSkipWelcome(status: .notDetermined, defaults: defaults))
        XCTAssertTrue(OnboardingState.shouldSkipWelcome(status: .denied, defaults: defaults))
    }

    func testGrantedAccessSkipsWelcomeWithoutFlag() {
        XCTAssertTrue(OnboardingState.shouldSkipWelcome(status: .authorized, defaults: defaults))
        XCTAssertTrue(OnboardingState.shouldSkipWelcome(status: .limited, defaults: defaults))
        XCTAssertFalse(OnboardingState.shouldSkipWelcome(status: .denied, defaults: defaults))
        XCTAssertFalse(OnboardingState.shouldSkipWelcome(status: .restricted, defaults: defaults))
    }

    func testAccessStateMapping() {
        XCTAssertEqual(WelcomeAccessState(.notDetermined), .needsRequest)
        XCTAssertEqual(WelcomeAccessState(.denied), .denied)
        XCTAssertEqual(WelcomeAccessState(.restricted), .restricted)
        XCTAssertEqual(WelcomeAccessState(.authorized), .granted)
        XCTAssertEqual(WelcomeAccessState(.limited), .granted)
    }

    func testOnlyDeniedAndRestrictedNeedSettings() {
        XCTAssertTrue(WelcomeAccessState.denied.needsSettings)
        XCTAssertTrue(WelcomeAccessState.restricted.needsSettings)
        XCTAssertFalse(WelcomeAccessState.needsRequest.needsSettings)
        XCTAssertFalse(WelcomeAccessState.granted.needsSettings)
    }
}
