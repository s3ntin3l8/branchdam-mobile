import Foundation

public class AppleSyncLogger {
    public static let shared = AppleSyncLogger()

    private let queue = DispatchQueue(label: "com.branchdam.mobile.synclogger", qos: .background)
    private let maxBytes = 1_000_000

    private init() {
        try? FileManager.default.createDirectory(at: logDirectory, withIntermediateDirectories: true)
    }

    public var logFileURL: URL {
        logDirectory.appendingPathComponent("branchdam_sync.log")
    }

    private var logDirectory: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("sync_logs")
    }

    public func log(_ message: String) {
        queue.async { self.write(message) }
    }

    public func logSync(uploaded: Int32, events: Int32) {
        log("SYNC uploaded=\(uploaded) events=\(events)")
    }

    public func logError(_ message: String) {
        log("ERROR \(message)")
    }

    private func write(_ message: String) {
        let formatter = ISO8601DateFormatter()
        let timestamp = formatter.string(from: Date())
        let line = "[\(timestamp)] \(message)\n"
        guard let data = line.data(using: .utf8) else { return }

        let url = logFileURL
        // Rolling rotation: rename current log to .old when it exceeds 1 MB
        if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
           let size = attrs[.size] as? Int, size >= maxBytes {
            let oldURL = logDirectory.appendingPathComponent("branchdam_sync.old.log")
            try? FileManager.default.removeItem(at: oldURL)
            try? FileManager.default.moveItem(at: url, to: oldURL)
        }

        if FileManager.default.fileExists(atPath: url.path) {
            if let handle = try? FileHandle(forWritingTo: url) {
                handle.seekToEndOfFile()
                handle.write(data)
                try? handle.close()
            }
        } else {
            try? data.write(to: url)
        }
    }
}
