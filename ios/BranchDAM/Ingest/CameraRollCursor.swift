import Foundation

/// The camera-roll scan position, persisted across launches.
///
/// `PhotoKitObserver` prefers a PhotoKit persistent change token (the
/// library's change history since the last scan, including assets that
/// arrive late from iCloud and deletions made while the app was not
/// running). When there is no usable token -- first launch, or the
/// history expired -- it falls back to a creation-date cursor. Both used
/// to live in memory only: every launch restarted from "one hour ago",
/// so anything captured while the app was closed for longer was never
/// imported.
///
/// The token is stored as opaque archived data so this type has no
/// PhotoKit dependency and can be unit-tested with any `UserDefaults`.
public final class CameraRollCursorStore: @unchecked Sendable {
    private let defaults: UserDefaults
    private let now: () -> Date
    private let lock = NSLock()

    /// How far back the very first scan looks.
    public static let initialLookback: TimeInterval = 3600

    public init(defaults: UserDefaults = .standard, now: @escaping () -> Date = Date.init) {
        self.defaults = defaults
        self.now = now
    }

    /// Creation-date cursor: assets created after it are new. On the
    /// first read ever it is set to [initialLookback] before now and
    /// persisted, so later launches resume from there instead of
    /// resetting.
    public var lastScannedDate: Date {
        lock.lock()
        defer { lock.unlock() }
        let key = BranchDamKeys.cameraRollLastScannedTimestamp.rawValue
        if let seconds = defaults.object(forKey: key) as? Double {
            return Date(timeIntervalSince1970: seconds)
        }
        let initial = now().addingTimeInterval(-Self.initialLookback)
        defaults.set(initial.timeIntervalSince1970, forKey: key)
        return initial
    }

    /// Moves the date cursor forward to [date]; never backwards.
    public func advanceDate(to date: Date) {
        let current = lastScannedDate
        guard date > current else { return }
        lock.lock()
        defer { lock.unlock() }
        defaults.set(date.timeIntervalSince1970, forKey: BranchDamKeys.cameraRollLastScannedTimestamp.rawValue)
    }

    /// The archived PhotoKit persistent change token, if one was saved.
    public var changeTokenData: Data? {
        lock.lock()
        defer { lock.unlock() }
        return defaults.data(forKey: BranchDamKeys.cameraRollChangeToken.rawValue)
    }

    public func saveChangeTokenData(_ data: Data) {
        lock.lock()
        defer { lock.unlock() }
        defaults.set(data, forKey: BranchDamKeys.cameraRollChangeToken.rawValue)
    }

    /// Forgets the token (it expired, or the history is unavailable); the
    /// next scan uses the date cursor.
    public func clearChangeToken() {
        lock.lock()
        defer { lock.unlock() }
        defaults.removeObject(forKey: BranchDamKeys.cameraRollChangeToken.rawValue)
    }
}

/// Records what one camera-roll scan found, in the only safe order: each
/// asset's intent (a pending_import row, or for auto-import an upload
/// row) is persisted before the scan position moves past it.
///
/// Pure logic with the side effects injected, so the ordering is unit
/// testable without PhotoKit or the Go engine.
public enum CameraRollIntake {

    public enum Outcome: Equatable, Sendable {
        /// Recorded (staged as a pending import).
        case persisted
        /// Nothing to record: the user already skipped it.
        case alreadyDecided
        /// Nothing was recorded; the asset must be offered again.
        case failed
    }

    public struct Result: Equatable, Sendable {
        /// Assets newly recorded by this pass, oldest first.
        public let persisted: [DiscoveredAsset]
        /// True when every asset was recorded or already decided, so the
        /// caller may advance its change token past this batch.
        public let complete: Bool
        /// The date cursor after this pass, or nil when it did not move.
        public let advancedTo: Date?
    }

    /// Persists [assets] oldest first via [persist]. The pass stops at the
    /// first failure, and the returned cursor stays just below the failed
    /// asset's creation date so the next scan (creationDate > cursor)
    /// offers it again. Re-persisting is harmless: staging is an upsert.
    public static func commit(
        assets: [DiscoveredAsset],
        startCursor: Date,
        persist: (DiscoveredAsset) -> Outcome
    ) -> Result {
        let ordered = assets.sorted {
            ($0.effectiveCreationDate, $0.localIdentifier) < ($1.effectiveCreationDate, $1.localIdentifier)
        }
        var persisted = [DiscoveredAsset]()
        var cursor = startCursor
        var complete = true
        for asset in ordered {
            switch persist(asset) {
            case .persisted:
                persisted.append(asset)
                cursor = max(cursor, asset.effectiveCreationDate)
            case .alreadyDecided:
                cursor = max(cursor, asset.effectiveCreationDate)
            case .failed:
                let justBefore = asset.effectiveCreationDate.addingTimeInterval(-0.001)
                cursor = max(startCursor, min(cursor, justBefore))
                complete = false
            }
            if !complete { break }
        }
        return Result(persisted: persisted, complete: complete, advancedTo: cursor > startCursor ? cursor : nil)
    }
}

extension DiscoveredAsset {
    /// The exact PhotoKit creation date when known, else the whole-second
    /// `creationDateUnix`.
    public var effectiveCreationDate: Date {
        creationDate ?? Date(timeIntervalSince1970: TimeInterval(creationDateUnix))
    }
}
