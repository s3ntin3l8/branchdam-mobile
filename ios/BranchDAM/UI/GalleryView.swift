import SwiftUI
import Photos

struct GalleryItem: Identifiable {
    let id: String
    let asset: PHAsset
    let lineageStatus: String
    let isRaw: Bool
}

@MainActor
class GalleryViewModel: ObservableObject {
    @Published var items: [GalleryItem] = []
    @Published var isLoading = true
    @Published var loadError: String? = nil

    func load() async {
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

        var pairedIds = Set<String>()
        for pair in pairs {
            pairedIds.insert(pair.masterLocalId)
            pairedIds.insert(pair.derivativeLocalId)
        }

        return metas.map { meta in
            let status: String
            if pairedIds.contains(meta.id) {
                status = "Paired"
            } else if meta.isRaw {
                status = "RAW"
            } else {
                status = "Unpaired"
            }
            return GalleryItem(id: meta.id, asset: meta.asset, lineageStatus: status, isRaw: meta.isRaw)
        }
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
            galleryContent
                .navigationTitle("Gallery")
                .toolbar {
                    ToolbarItem(placement: .navigationBarLeading) {
                        if !viewModel.isLoading && viewModel.loadError == nil && !viewModel.items.isEmpty {
                            Text("\(viewModel.items.count) items")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button {
                            Task { await viewModel.load() }
                        } label: {
                            Image(systemName: "arrow.clockwise")
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
                        }
                        .aspectRatio(1, contentMode: .fit)
                        .clipped()
                    }
                }
                .padding(.horizontal, 2)
            }
        }
    }

    private func statusColor(_ status: String) -> Color {
        switch status {
        case "Paired": return .green.opacity(0.85)
        case "RAW": return Color.accentColor.opacity(0.85)
        default: return Color.gray.opacity(0.7)
        }
    }
}
