import Foundation

public struct AppleOtgCandidate: Equatable {
    public let url: URL
    public let relativePath: String
    public let fileName: String
    public let sizeBytes: Int64
    public let lastModifiedUnix: Int64
    public let isRaw: BooleanLiteralType
    public let isVideo: BooleanLiteralType

    public init(
        url: URL,
        relativePath: String,
        fileName: String,
        sizeBytes: Int64,
        lastModifiedUnix: Int64,
        isRaw: Bool,
        isVideo: Bool
    ) {
        self.url = url
        self.relativePath = relativePath
        self.fileName = fileName
        self.sizeBytes = sizeBytes
        self.lastModifiedUnix = lastModifiedUnix
        self.isRaw = isRaw
        self.isVideo = isVideo
    }

    public static let rawExtensions: Set<String> = [
        "dng", "cr3", "cr2", "arw", "nef", "nrw", "orf", "rw2", "pef", "raf", "3fr"
    ]

    public static let imageExtensions: Set<String> = [
        "jpg", "jpeg", "heic", "heif", "png", "webp", "tif", "tiff"
    ]

    public static let videoExtensions: Set<String> = [
        "mp4", "mov", "m4v", "avi", "mkv"
    ]

    public static func isRawExtension(_ fileName: String) -> Bool {
        let ext = (fileName as NSString).pathExtension.lowercased()
        return rawExtensions.contains(ext)
    }

    public static func isVideoExtension(_ fileName: String) -> Bool {
        let ext = (fileName as NSString).pathExtension.lowercased()
        return videoExtensions.contains(ext)
    }

    public static func isSupportedMedia(_ fileName: String) -> Bool {
        let ext = (fileName as NSString).pathExtension.lowercased()
        return rawExtensions.contains(ext) || imageExtensions.contains(ext) || videoExtensions.contains(ext)
    }

    public static func formatBytes(_ bytes: Int64) -> String {
        if bytes <= 0 { return "0 B" }
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useAll]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
}

public struct AppleOtgScanResult: Equatable {
    public let deviceLabel: String
    public let rootUrl: URL
    public let candidates: [AppleOtgCandidate]
    public let totalSizeBytes: Int64
    public let rawCount: Int
    public let jpegCount: Int
    public let videoCount: Int

    public init(
        deviceLabel: String,
        rootUrl: URL,
        candidates: [AppleOtgCandidate]
    ) {
        self.deviceLabel = deviceLabel
        self.rootUrl = rootUrl
        self.candidates = candidates
        self.totalSizeBytes = candidates.reduce(0) { $0 + $1.sizeBytes }
        self.rawCount = candidates.filter { $0.isRaw }.count
        self.videoCount = candidates.filter { $0.isVideo }.count
        self.jpegCount = candidates.filter { !$0.isRaw && !$0.isVideo }.count
    }

    public var totalCount: Int {
        return candidates.count
    }

    public var formattedTotalSize: String {
        return AppleOtgCandidate.formatBytes(totalSizeBytes)
    }
}

public enum AppleOtgCardScanner {

    public static func scanDirectory(
        at directory: URL,
        deviceLabel: String = "SD Card"
    ) -> AppleOtgScanResult {
        var candidates: [AppleOtgCandidate] = []
        let fileManager = FileManager.default

        guard let enumerator = fileManager.enumerator(
            at: directory,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles, .producesRelativePathURLs]
        ) else {
            return AppleOtgScanResult(deviceLabel: deviceLabel, rootUrl: directory, candidates: [])
        }

        let rootPath = directory.standardizedFileURL.path

        for case let fileURL as URL in enumerator {
            let fileName = fileURL.lastPathComponent
            if fileName.hasPrefix(".") { continue }

            if AppleOtgCandidate.isSupportedMedia(fileName) {
                let resourceValues = try? fileURL.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey])
                guard resourceValues?.isRegularFile == true else { continue }

                let sizeBytes = Int64(resourceValues?.fileSize ?? 0)
                let modDate = resourceValues?.contentModificationDate ?? Date()
                let lastModUnix = Int64(modDate.timeIntervalSince1970)

                let fullPath = fileURL.standardizedFileURL.path
                var relativePath = fullPath
                if fullPath.hasPrefix(rootPath) {
                    relativePath = String(fullPath.dropFirst(rootPath.count)).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                }
                if relativePath.isEmpty {
                    relativePath = fileName
                }

                candidates.append(
                    AppleOtgCandidate(
                        url: fileURL,
                        relativePath: relativePath,
                        fileName: fileName,
                        sizeBytes: sizeBytes,
                        lastModifiedUnix: lastModUnix,
                        isRaw: AppleOtgCandidate.isRawExtension(fileName),
                        isVideo: AppleOtgCandidate.isVideoExtension(fileName)
                    )
                )
            }
        }

        return AppleOtgScanResult(
            deviceLabel: deviceLabel,
            rootUrl: directory,
            candidates: candidates
        )
    }
}
