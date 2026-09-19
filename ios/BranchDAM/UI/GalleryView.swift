import SwiftUI
import Photos

public struct GalleryItem: Identifiable, Hashable, Equatable {
    public let id: String
    public let asset: PHAsset
    public let lineageStatus: String
    public let isRaw: Bool
    public let isOffloaded: Bool
    public let backupStatus: String

    public var isBackedUp: Bool { backupStatus == "COMPLETED" || isOffloaded }
    public var isPendingUpload: Bool { backupStatus == "PENDING" || backupStatus == "IN_PROGRESS" }

    public init(id: String, asset: PHAsset, lineageStatus: String, isRaw: Bool, isOffloaded: Bool, backupStatus: String) {
        self.id = id
        self.asset = asset
        self.lineageStatus = lineageStatus
        self.isRaw = isRaw
        self.isOffloaded = isOffloaded
        self.backupStatus = backupStatus
    }

    public static func == (lhs: GalleryItem, rhs: GalleryItem) -> Bool {
        lhs.id == rhs.id && lhs.lineageStatus == rhs.lineageStatus && lhs.isOffloaded == rhs.isOffloaded && lhs.backupStatus == rhs.backupStatus
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }
}

@MainActor
public class GalleryViewModel: ObservableObject {
    @Published public var items: [GalleryItem] = []
    @Published public var isLoading = true
    @Published public var loadError: String? = nil

    // Multi-Selection State
    @Published public var isSelectionMode = false
    @Published public var selectedIds = Set<String>()
    @Published public var batchStatusMessage: String? = nil

    public init() {}

    public func load() async {
        isLoading = true
        loadError = nil
        do {
            items = try await Task.detached(priority: .userInitiated) {
                try await GalleryViewModel.fetchItems()
            }.value
        } catch {
            loadError = error.localizedDescription
        }
        isLoading = false
    }

    public func toggleSelection(id: String) {
        if selectedIds.contains(id) {
            selectedIds.remove(id)
        } else {
            selectedIds.insert(id)
        }
    }

    public func selectAll() {
        selectedIds = Set(items.map { $0.id })
    }

    public func clearSelection() {
        selectedIds.removeAll()
    }

    public func batchUpload() {
        let targets = items.filter { selectedIds.contains($0.id) && !$0.isBackedUp }
        var enqueuedCount = 0
        for item in targets {
            let resources = PHAssetResource.assetResources(for: item.asset)
            let primary = resources.first(where: { $0.type == .photo || $0.type == .video || $0.type == .alternatePhoto }) ?? resources.first
            let filename = primary?.originalFilename ?? "IMG_\(item.id.prefix(8)).JPG"
            let unix = Int64(item.asset.creationDate?.timeIntervalSince1970 ?? 0)

            let uploadId = BranchDamCoreBridge.shared.enqueueMedia(
                localPath: "ph://\(item.id)",
                filename: filename,
                capturedAtUnix: unix,
                localID: item.id
            )
            if uploadId > 0 { enqueuedCount += 1 }
        }

        if enqueuedCount > 0 {
            BackgroundSyncManager.shared.triggerImmediateSync()
            batchStatusMessage = "Enqueued \(enqueuedCount) items for upload"
        } else {
            batchStatusMessage = "Selected items already backed up"
        }

        clearSelection()
        isSelectionMode = false

        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
            self.batchStatusMessage = nil
        }
    }

    public func batchReclaim() {
        let targets = items.filter { selectedIds.contains($0.id) }
        var eligibleAssets = [PHAsset]()
        for item in targets {
            let (eligible, _) = BranchDamCoreBridge.shared.reclaimSafeSpace(localID: item.id)
            if eligible {
                eligibleAssets.append(item.asset)
            }
        }

        if !eligibleAssets.isEmpty {
            PHPhotoLibrary.shared().performChanges({
                PHAssetChangeRequest.deleteAssets(eligibleAssets as NSArray)
            }) { success, error in
                DispatchQueue.main.async {
                    if success {
                        for asset in eligibleAssets {
                            _ = BranchDamCoreBridge.shared.enqueueDeleteEvent(nodeUUID: asset.localIdentifier)
                        }
                        self.batchStatusMessage = "Reclaimed \(eligibleAssets.count) items"
                        Task { await self.load() }
                    } else {
                        self.batchStatusMessage = "Reclaim cancelled or failed"
                    }
                }
            }
        } else {
            batchStatusMessage = "No selected items eligible for reclaim"
        }

        clearSelection()
        isSelectionMode = false

        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
            self.batchStatusMessage = nil
        }
    }

    private static func fetchItems() async throws -> [GalleryItem] {
        let fetchOptions = PHFetchOptions()
        fetchOptions.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        fetchOptions.fetchLimit = 500

        let imageResult = PHAsset.fetchAssets(with: .image, options: fetchOptions)
        let videoResult = PHAsset.fetchAssets(with: .video, options: fetchOptions)

        var assets: [PHAsset] = []
        imageResult.enumerateObjects { asset, _, _ in assets.append(asset) }
        videoResult.enumerateObjects { asset, _, _ in assets.append(asset) }

        typealias AssetMeta = (id: String, filename: String, dateUnix: Int64, isRaw: Bool, asset: PHAsset)
        let metas: [AssetMeta] = assets.map { asset in
            let resources = PHAssetResource.assetResources(for: asset)
            let primary = resources.first(where: {
                $0.type == .photo || $0.type == .video || $0.type == .alternatePhoto
            }) ?? resources.first
            let filename = primary?.originalFilename ?? "IMG_\(asset.localIdentifier.prefix(8)).JPG"
            let dateUnix = Int64(asset.creationDate?.timeIntervalSince1970 ?? 0)
            let isRaw = filename.uppercased().hasSuffix(".DNG")
            return (id: asset.localIdentifier, filename: filename, dateUnix: dateUnix, isRaw: isRaw, asset: asset)
        }

        let raws = metas.filter { $0.isRaw }.map { (id: $0.id, filename: $0.filename, dateUnix: $0.dateUnix) }
        let jpegs = metas.filter { !$0.isRaw && $0.asset.mediaType == .image }
            .map { (id: $0.id, filename: $0.filename, dateUnix: $0.dateUnix) }
        let pairs = ApplePairDetector.findProRawPairs(masters: raws, derivatives: jpegs)

        var pairedRawIds = Set<String>()
        var pairedJpegIds = Set<String>()
        for pair in pairs {
            pairedRawIds.insert(pair.masterLocalId)
            pairedJpegIds.insert(pair.derivativeLocalId)
        }

        let allStatuses = BranchDamCoreBridge.shared.getAllMediaStatuses()
        var galleryItems = [GalleryItem]()

        for meta in metas {
            let metaLocalId = "ph://\(meta.id)"
            if pairedRawIds.contains(meta.id) || pairedRawIds.contains(metaLocalId) {
                continue
            }

            if pairedJpegIds.contains(meta.id) || pairedJpegIds.contains(metaLocalId) {
                let backupStatus = allStatuses[meta.id]
                    ?? allStatuses[metaLocalId]
                    ?? allStatuses[meta.filename]
                    ?? "NOT_ENQUEUED"
                let isOffloaded = backupStatus == "OFFLOADED"
                galleryItems.append(
                    GalleryItem(
                        id: meta.id,
                        asset: meta.asset,
                        lineageStatus: "RAW+JPEG",
                        isRaw: false,
                        isOffloaded: isOffloaded,
                        backupStatus: backupStatus
                    )
                )
            } else {
                let status: String = meta.isRaw ? "RAW" : "Unpaired"
                let backupStatus = allStatuses[meta.id]
                    ?? allStatuses[metaLocalId]
                    ?? allStatuses[meta.filename]
                    ?? "NOT_ENQUEUED"
                let isOffloaded = backupStatus == "OFFLOADED"
                galleryItems.append(
                    GalleryItem(
                        id: meta.id,
                        asset: meta.asset,
                        lineageStatus: status,
                        isRaw: meta.isRaw,
                        isOffloaded: isOffloaded,
                        backupStatus: backupStatus
                    )
                )
            }
        }
        return galleryItems
    }
}

private struct AssetThumbnailView: View {
    let asset: PHAsset
    @State private var image: UIImage? = nil

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Color(.secondarySystemBackground)
            }
        }
        .task(id: asset.localIdentifier) {
            image = await loadThumbnail()
        }
    }

    private func loadThumbnail() async -> UIImage? {
        await withCheckedContinuation { continuation in
            let options = PHImageRequestOptions()
            options.deliveryMode = .fastFormat
            options.isNetworkAccessAllowed = false
            PHImageManager.default().requestImage(
                for: asset,
                targetSize: CGSize(width: 200, height: 200),
                contentMode: .aspectFill,
                options: options
            ) { image, _ in
                continuation.resume(returning: image)
            }
        }
    }
}

public struct GalleryView: View {
    @StateObject private var viewModel = GalleryViewModel()

    public init() {}

    public var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                galleryContent

                if viewModel.isSelectionMode && !viewModel.selectedIds.isEmpty {
                    HStack(spacing: 16) {
                        Button {
                            viewModel.batchUpload()
                        } label: {
                            Label("Upload (\(viewModel.selectedIds.count))", systemImage: "icloud.and.arrow.up.fill")
                                .font(.subheadline.bold())
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                                .background(Color.accentColor)
                                .foregroundColor(.white)
                                .cornerRadius(10)
                        }

                        Button(role: .destructive) {
                            viewModel.batchReclaim()
                        } label: {
                            Label("Reclaim (\(viewModel.selectedIds.count))", systemImage: "trash.fill")
                                .font(.subheadline.bold())
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                                .background(Color.red.opacity(0.15))
                                .foregroundColor(.red)
                                .cornerRadius(10)
                        }
                    }
                    .padding()
                    .background(Color(UIColor.secondarySystemBackground))
                }

                if let batchMsg = viewModel.batchStatusMessage {
                    Text(batchMsg)
                        .font(.caption.bold())
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(Color.black.opacity(0.8))
                        .foregroundColor(.white)
                        .clipShape(Capsule())
                        .padding(.bottom, 8)
                }
            }
            .navigationTitle("Gallery")
            .navigationDestination(for: GalleryItem.self) { item in
                GalleryDetailView(item: item)
            }
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    if viewModel.isSelectionMode {
                        Button("Cancel") {
                            viewModel.isSelectionMode = false
                            viewModel.clearSelection()
                        }
                    } else if !viewModel.isLoading && viewModel.loadError == nil && !viewModel.items.isEmpty {
                        Text("\(viewModel.items.count) items")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    HStack(spacing: 12) {
                        if viewModel.isSelectionMode {
                            Button(viewModel.selectedIds.count == viewModel.items.count ? "Deselect All" : "Select All") {
                                if viewModel.selectedIds.count == viewModel.items.count {
                                    viewModel.clearSelection()
                                } else {
                                    viewModel.selectAll()
                                }
                            }
                        } else {
                            Button("Select") {
                                viewModel.isSelectionMode = true
                            }
                            Button {
                                Task { await viewModel.load() }
                            } label: {
                                Image(systemName: "arrow.clockwise")
                            }
                        }
                    }
                }
            }
            .task {
                await viewModel.load()
            }
        }
    }

    @ViewBuilder
    private var galleryContent: some View {
        if viewModel.isLoading {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error = viewModel.loadError {
            ContentUnavailableView(
                "Could Not Load Gallery",
                systemImage: "exclamationmark.triangle",
                description: Text(error)
            )
        } else if viewModel.items.isEmpty {
            ContentUnavailableView(
                "No Media Found",
                systemImage: "photo.on.rectangle",
                description: Text("No photos or videos in your library.")
            )
        } else {
            ScrollView {
                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: 110), spacing: 2)],
                    spacing: 2
                ) {
                    ForEach(viewModel.items) { item in
                        if viewModel.isSelectionMode {
                            Button {
                                viewModel.toggleSelection(id: item.id)
                            } label: {
                                galleryCardContent(for: item)
                            }
                        } else {
                            NavigationLink(value: item) {
                                galleryCardContent(for: item)
                            }
                        }
                    }
                }
                .padding(.horizontal, 2)
            }
        }
    }

    @ViewBuilder
    private func galleryCardContent(for item: GalleryItem) -> some View {
        ZStack(alignment: .topLeading) {
            AssetThumbnailView(asset: item.asset)
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            Text(item.lineageStatus)
                .font(.system(size: 9, weight: .semibold))
                .padding(.horizontal, 5)
                .padding(.vertical, 2)
                .background(statusColor(item.lineageStatus))
                .foregroundColor(.white)
                .clipShape(RoundedRectangle(cornerRadius: 4))
                .padding(4)

            if item.isRaw {
                HStack {
                    Spacer()
                    Text("RAW")
                        .font(.system(size: 9, weight: .bold))
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(Color.orange.opacity(0.9))
                        .foregroundColor(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 4))
                        .padding(4)
                }
            }

            VStack {
                HStack {
                    Spacer()
                    if viewModel.isSelectionMode {
                        Image(systemName: viewModel.selectedIds.contains(item.id) ? "checkmark.circle.fill" : "circle")
                            .foregroundColor(viewModel.selectedIds.contains(item.id) ? .accentColor : .white.opacity(0.8))
                            .font(.system(size: 18))
                            .padding(4)
                    } else if item.isBackedUp {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundColor(.green)
                            .font(.system(size: 14))
                            .padding(4)
                    } else if item.isPendingUpload {
                        Image(systemName: "arrow.triangle.2.circlepath")
                            .foregroundColor(.blue)
                            .font(.system(size: 14))
                            .padding(4)
                    }
                }
                Spacer()
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipped()
    }

    private func statusColor(_ status: String) -> Color {
        switch status {
        case "RAW+JPEG", "Paired": return .green.opacity(0.85)
        case "RAW": return Color.accentColor.opacity(0.85)
        default: return Color.gray.opacity(0.7)
        }
    }
}
