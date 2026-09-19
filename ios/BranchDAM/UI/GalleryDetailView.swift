import SwiftUI
import Photos
import AVKit

public struct ExifMetadata {
    public var filename: String = ""
    public var creationDate: String = ""
    public var dimensions: String = ""
    public var cameraModel: String = ""
    public var lensModel: String = ""
    public var iso: String = ""
    public var exposureTime: String = ""
    public var aperture: String = ""
    public var focalLength: String = ""
    public var blake3Hash: String = ""
    public var backupStatus: String = ""
    public var lineageStatus: String = ""
}

public struct GalleryDetailView: View {
    public let item: GalleryItem
    @Environment(\.dismiss) private var dismiss

    @State private var fullImage: UIImage? = nil
    @State private var videoUrl: URL? = nil
    @State private var isVideoLoading = false
    @State private var exifMetadata = ExifMetadata()
    @State private var showExifSheet = false
    @State private var showLineageComparison = false
    @State private var showDeleteConfirmation = false
    @State private var actionMessage: String? = nil
    @State private var isActioning = false

    // Zoom & Pan state
    @State private var scale: CGFloat = 1.0
    @State private var lastScale: CGFloat = 1.0
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    public init(item: GalleryItem) {
        self.item = item
    }

    public var body: some View {
        VStack(spacing: 0) {
            // Main media viewport
            ZStack {
                Color.black.edgesIgnoringSafeArea(.all)

                if item.asset.mediaType == .video {
                    if let videoUrl {
                        VideoPlayer(player: AVPlayer(url: videoUrl))
                            .edgesIgnoringSafeArea(.all)
                    } else if isVideoLoading {
                        ProgressView()
                            .tint(.white)
                    } else {
                        ContentUnavailableView("Unable to load video", systemImage: "video.slash")
                    }
                } else {
                    if let fullImage {
                        Image(uiImage: fullImage)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .scaleEffect(scale)
                            .offset(offset)
                            .gesture(
                                MagnificationGesture()
                                    .onChanged { value in
                                        let delta = value / lastScale
                                        lastScale = value
                                        scale = min(max(scale * delta, 1.0), 5.0)
                                    }
                                    .onEnded { _ in
                                        lastScale = 1.0
                                        if scale == 1.0 {
                                            withAnimation { offset = .zero }
                                        }
                                    }
                            )
                            .simultaneousGesture(
                                DragGesture()
                                    .onChanged { value in
                                        if scale > 1.0 {
                                            offset = CGSize(
                                                width: lastOffset.width + value.translation.width,
                                                height: lastOffset.height + value.translation.height
                                            )
                                        }
                                    }
                                    .onEnded { _ in
                                        lastOffset = offset
                                    }
                            )
                            .onTapGesture(count: 2) {
                                withAnimation {
                                    if scale > 1.0 {
                                        scale = 1.0
                                        offset = .zero
                                        lastOffset = .zero
                                    } else {
                                        scale = 2.5
                                    }
                                }
                            }
                    } else {
                        ProgressView()
                            .tint(.white)
                    }
                }

                if let actionMessage {
                    VStack {
                        Spacer()
                        Text(actionMessage)
                            .font(.subheadline.bold())
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                            .background(Color.black.opacity(0.8))
                            .foregroundColor(.white)
                            .clipShape(Capsule())
                            .padding(.bottom, 24)
                    }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }

            // Bottom Action Control Bar
            HStack(spacing: 20) {
                // EXIF Info Button
                Button {
                    showExifSheet = true
                } label: {
                    VStack(spacing: 4) {
                        Image(systemName: "info.circle.fill")
                            .font(.title2)
                        Text("Info")
                            .font(.caption)
                    }
                }

                // Lineage Comparison Button (if paired or RAW+JPEG)
                if item.lineageStatus == "RAW+JPEG" || item.lineageStatus == "Paired" || item.isRaw {
                    Button {
                        showLineageComparison = true
                    } label: {
                        VStack(spacing: 4) {
                            Image(systemName: "square.split.2x1.fill")
                                .font(.title2)
                            Text("Compare")
                                .font(.caption)
                        }
                    }
                }

                Spacer()

                // Upload Button
                Button {
                    enqueueUpload()
                } label: {
                    VStack(spacing: 4) {
                        Image(systemName: item.isBackedUp ? "checkmark.icloud.fill" : "icloud.and.arrow.up.fill")
                            .font(.title2)
                        Text(item.isBackedUp ? "Backed Up" : "Upload")
                            .font(.caption)
                    }
                }
                .disabled(item.isBackedUp || isActioning)

                // Reclaim / Delete Button
                Button(role: .destructive) {
                    showDeleteConfirmation = true
                } label: {
                    VStack(spacing: 4) {
                        Image(systemName: "trash.fill")
                            .font(.title2)
                        Text("Delete")
                            .font(.caption)
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
            .background(Color(UIColor.secondarySystemBackground))
        }
        .navigationTitle(exifMetadata.filename.isEmpty ? "Asset Details" : exifMetadata.filename)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await loadFullAsset()
            await loadExifMetadata()
        }
        .sheet(isPresented: $showExifSheet) {
            ExifMetadataSheet(metadata: exifMetadata, item: item)
        }
        .sheet(isPresented: $showLineageComparison) {
            VisualLineageComparisonView(item: item)
        }
        .alert("Delete Photo?", isPresented: $showDeleteConfirmation) {
            Button("Delete Permanently", role: .destructive) {
                performDelete()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This will permanently reclaim or delete this photo from your library and branchDAM.")
        }
    }

    private func loadFullAsset() async {
        if item.asset.mediaType == .video {
            isVideoLoading = true
            let options = PHVideoRequestOptions()
            options.deliveryMode = .highQualityFormat
            options.isNetworkAccessAllowed = true
            PHImageManager.default().requestAVAsset(forVideo: item.asset, options: options) { avAsset, _, _ in
                if let urlAsset = avAsset as? AVURLAsset {
                    DispatchQueue.main.async {
                        self.videoUrl = urlAsset.url
                        self.isVideoLoading = false
                    }
                }
            }
        } else {
            let options = PHImageRequestOptions()
            options.deliveryMode = .highQualityFormat
            options.isNetworkAccessAllowed = true
            PHImageManager.default().requestImage(
                for: item.asset,
                targetSize: PHImageManagerMaximumSize,
                contentMode: .aspectFit,
                options: options
            ) { image, _ in
                if let image {
                    DispatchQueue.main.async {
                        self.fullImage = image
                    }
                }
            }
        }
    }

    private func loadExifMetadata() async {
        let resources = PHAssetResource.assetResources(for: item.asset)
        let primary = resources.first(where: {
            $0.type == .photo || $0.type == .video || $0.type == .alternatePhoto
        }) ?? resources.first
        let filename = primary?.originalFilename ?? "IMG_\(item.id.prefix(8)).JPG"

        var dateStr = "—"
        if let date = item.asset.creationDate {
            let formatter = DateFormatter()
            formatter.dateStyle = .medium
            formatter.timeStyle = .short
            dateStr = formatter.string(from: date)
        }

        let dimensions = "\(item.asset.pixelWidth) × \(item.asset.pixelHeight)"
        let blake3 = BranchDamCoreBridge.shared.lookupBlake3ForLocalID(localID: item.id)

        var meta = ExifMetadata(
            filename: filename,
            creationDate: dateStr,
            dimensions: dimensions,
            blake3Hash: blake3.isEmpty ? "Not hashed" : blake3,
            backupStatus: item.backupStatus,
            lineageStatus: item.lineageStatus
        )

        // Read EXIF dictionary from PHAsset
        let options = PHContentEditingInputRequestOptions()
        options.isNetworkAccessAllowed = false
        item.asset.requestContentEditingInput(with: options) { input, _ in
            if let url = input?.fullSizeImageURL,
               let source = CGImageSourceCreateWithURL(url as CFURL, nil),
               let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any] {
                if let tiff = properties[kCGImagePropertyTIFFDictionary as String] as? [String: Any] {
                    meta.cameraModel = (tiff[kCGImagePropertyTIFFModel as String] as? String) ?? "—"
                }
                if let exif = properties[kCGImagePropertyExifDictionary as String] as? [String: Any] {
                    if let isoArr = exif[kCGImagePropertyExifISOSpeedRatings as String] as? [Int], let iso = isoArr.first {
                        meta.iso = "ISO \(iso)"
                    }
                    if let exp = exif[kCGImagePropertyExifExposureTime as String] as? Double {
                        meta.exposureTime = exp < 1.0 ? "1/\(Int(1.0 / exp)) s" : "\(exp) s"
                    }
                    if let f = exif[kCGImagePropertyExifFNumber as String] as? Double {
                        meta.aperture = String(format: "f/%.1f", f)
                    }
                    if let focal = exif[kCGImagePropertyExifFocalLength as String] as? Double {
                        meta.focalLength = "\(Int(focal)) mm"
                    }
                    if let lens = exif[kCGImagePropertyExifLensModel as String] as? String {
                        meta.lensModel = lens
                    }
                }
            }
            DispatchQueue.main.async {
                self.exifMetadata = meta
            }
        }
    }

    private func enqueueUpload() {
        isActioning = true
        let resources = PHAssetResource.assetResources(for: item.asset)
        let primary = resources.first(where: {
            $0.type == .photo || $0.type == .video || $0.type == .alternatePhoto
        }) ?? resources.first
        let filename = primary?.originalFilename ?? "IMG_\(item.id.prefix(8)).JPG"
        let unix = Int64(item.asset.creationDate?.timeIntervalSince1970 ?? 0)

        let uploadId = BranchDamCoreBridge.shared.enqueueMedia(
            localPath: "ph://\(item.id)",
            filename: filename,
            capturedAtUnix: unix,
            localID: item.id
        )

        BackgroundSyncManager.shared.triggerImmediateSync()

        withAnimation {
            actionMessage = uploadId > 0 ? "Enqueued for upload" : "Upload enqueue failed"
        }
        isActioning = false

        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
            withAnimation { actionMessage = nil }
        }
    }

    private func performDelete() {
        let (eligible, reason) = BranchDamCoreBridge.shared.reclaimSafeSpace(localID: item.id)
        if eligible {
            PHPhotoLibrary.shared().performChanges({
                PHAssetChangeRequest.deleteAssets([item.asset] as NSArray)
            }) { success, error in
                DispatchQueue.main.async {
                    if success {
                        _ = BranchDamCoreBridge.shared.enqueueDeleteEvent(nodeUUID: item.id)
                        self.dismiss()
                    } else {
                        // Rollback offloaded state in Go core so local file does not leak
                        _ = BranchDamCoreBridge.shared.setMediaOffloaded(localID: item.id, isOffloaded: false)
                        withAnimation { self.actionMessage = "Deletion failed: \(error?.localizedDescription ?? "Cancelled")" }
                    }
                }
            }
        } else {
            withAnimation { actionMessage = "Cannot reclaim: \(reason)" }
            DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
                withAnimation { actionMessage = nil }
            }
        }
    }
}

private struct ExifMetadataSheet: View {
    let metadata: ExifMetadata
    let item: GalleryItem
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section(header: Text("File Details")) {
                    LabeledContent("Filename", value: metadata.filename)
                    LabeledContent("Captured", value: metadata.creationDate)
                    LabeledContent("Dimensions", value: metadata.dimensions)
                    LabeledContent("Lineage Status", value: metadata.lineageStatus)
                    LabeledContent("Backup Status", value: metadata.backupStatus)
                }

                Section(header: Text("Camera & Lens")) {
                    LabeledContent("Camera", value: metadata.cameraModel.isEmpty ? "—" : metadata.cameraModel)
                    LabeledContent("Lens", value: metadata.lensModel.isEmpty ? "—" : metadata.lensModel)
                    LabeledContent("ISO", value: metadata.iso.isEmpty ? "—" : metadata.iso)
                    LabeledContent("Shutter", value: metadata.exposureTime.isEmpty ? "—" : metadata.exposureTime)
                    LabeledContent("Aperture", value: metadata.aperture.isEmpty ? "—" : metadata.aperture)
                    LabeledContent("Focal Length", value: metadata.focalLength.isEmpty ? "—" : metadata.focalLength)
                }

                Section(header: Text("Cryptographic Checksum")) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("BLAKE3-256")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Text(metadata.blake3Hash)
                            .font(.system(.caption, design: .monospaced))
                            .textSelection(.enabled)
                    }
                }
            }
            .navigationTitle("Asset Metadata")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
