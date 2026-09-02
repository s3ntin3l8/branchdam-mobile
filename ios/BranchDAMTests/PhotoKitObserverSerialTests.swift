import XCTest
import Photos
@testable import BranchDAM

/// Tests for PhotoKitObserver's lastScannedDate behavior and
/// DiscoveredAsset model. F plan items:
/// - lastScannedDate is mutated only on the serial queue
/// - pair detection fires lineage events for known pairs
///
/// lastScannedDate is private and not directly testable; we verify
/// the DiscoveredAsset model and the start/stop observing idempotence.
/// Tests that call startObserving/stopObserving use a fresh local
/// instance rather than the shared singleton to prevent mid-test
/// failures from leaking the observer's registration into later tests.
final class PhotoKitObserverSerialTests: XCTestCase {

    func testStartObservingIsIdempotent() {
        // Use a fresh instance — if a test fails mid-registration,
        // the singleton would remain registered and bleed into later
        // tests. A local instance is self-contained.
        let observer = PhotoKitObserver()

        // PhotoKitObserver is an NSObject subclass, so KVC works.
        func readIsObserving() -> Bool {
            (observer as AnyObject).value(forKey: "isObserving") as? Bool ?? false
        }

        XCTAssertFalse(readIsObserving(), "fresh instance must not be observing")

        observer.startObserving()
        XCTAssertTrue(readIsObserving(), "after first start, isObserving must be true")

        // Second and third calls must NOT flip isObserving back to
        // false — the guard returns early, so the state must remain
        // unchanged. This is the actual idempotence assertion.
        observer.startObserving()
        XCTAssertTrue(readIsObserving(), "second start must not change isObserving")

        observer.startObserving()
        XCTAssertTrue(readIsObserving(), "third start must not change isObserving")

        observer.stopObserving()
        XCTAssertFalse(readIsObserving(), "after stop, isObserving must be false")
    }

    func testStopObservingWithoutStartDoesNotCrash() {
        // stopObserving on a never-started observer must not crash.
        let observer = PhotoKitObserver()
        observer.stopObserving()
    }

    func testDiscoveredAssetDefaultBoolFields() {
        // The DiscoveredAsset init takes non-optional Bool params with
        // default false. The struct properties ARE optional (Bool?)
        // because some media types don't have a definitive answer.
        // Verify the default init values propagate to the optional
        // properties as .some(false).
        let asset = DiscoveredAsset(
            localIdentifier: "test",
            filename: "test.dng",
            creationDateUnix: 1724000000
        )
        // Default init: isRaw = false, isVideo = false. The optional
        // properties should be .some(false), not nil.
        if case .some(let val) = asset.isRaw {
            XCTAssertFalse(val)
        } else {
            XCTFail("expected isRaw to be .some(false), got nil")
        }
        if case .some(let val) = asset.isVideo {
            XCTAssertFalse(val)
        } else {
            XCTFail("expected isVideo to be .some(false), got nil")
        }
    }

    func testDiscoveredAssetEquatable() {
        // Equatable conformance allows the observer to dedupe
        // discovered assets across scan cycles.
        let a = DiscoveredAsset(
            localIdentifier: "id1",
            filename: "f.dng",
            creationDateUnix: 1724000000,
            isRaw: true,
            isVideo: false,
            pixelWidth: 100,
            pixelHeight: 200
        )
        let b = DiscoveredAsset(
            localIdentifier: "id1",
            filename: "f.dng",
            creationDateUnix: 1724000000,
            isRaw: true,
            isVideo: false,
            pixelWidth: 100,
            pixelHeight: 200
        )
        XCTAssertEqual(a, b)
    }
}
