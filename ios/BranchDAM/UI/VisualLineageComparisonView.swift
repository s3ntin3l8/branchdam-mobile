import SwiftUI
import Photos

public struct VisualLineageComparisonView: View {
    public let item: GalleryItem
    @Environment(\.dismiss) private var dismiss

    @State private var masterImage: UIImage? = nil
    @State private var derivativeImage: UIImage? = nil
    @State private var masterFilename: String = "Master (RAW)"
    @State private var derivativeFilename: String = "Derivative (JPEG)"
    @State private var viewMode: Int = 0 // 0: Side-by-Side, 1: Master, 2: Derivative

    // Synchronized Zoom & Offset
    @State private var syncScale: CGFloat = 1.0
    @State private var syncOffset: CGSize = .zero

    public init(item: GalleryItem) {
        self.item = item
    }

    public var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // View Mode Selector
                Picker("View Mode", selection: $viewMode) {
                    Text("Side by Side").tag(0)
                    Text("Master").tag(1)
                    Text("Derivative").tag(2)
                }
                .pickerStyle(.segmented)
                .padding()

                // Media Comparison Canvas
                GeometryReader { geometry in
                    if viewMode == 0 {
                        HStack(spacing: 2) {
                            // Left: Master (RAW/Original)
                            VStack(spacing: 4) {
                                Text(masterFilename)
                                    .font(.caption.bold())
                                    .padding(.vertical, 4)
                                    .frame(maxWidth: .infinity)
                                    .background(Color.orange.opacity(0.85))
                                    .foregroundColor(.white)

                                ImageContainerView(image: masterImage, scale: syncScale, offset: syncOffset)
                            }
                            .frame(width: geometry.size.width / 2 - 1)

                            Divider()

                            // Right: Derivative (JPEG/Edit)
                            VStack(spacing: 4) {
                                Text(derivativeFilename)
                                    .font(.caption.bold())
                                    .padding(.vertical, 4)
                                    .frame(maxWidth: .infinity)
                                    .background(Color.blue.opacity(0.85))
                                    .foregroundColor(.white)

                                ImageContainerView(image: derivativeImage, scale: syncScale, offset: syncOffset)
                            }
                            .frame(width: geometry.size.width / 2 - 1)
                        }
                    } else if viewMode == 1 {
                        VStack(spacing: 4) {
                            Text(masterFilename)
                                .font(.caption.bold())
                                .padding(.vertical, 4)
                                .frame(maxWidth: .infinity)
                                .background(Color.orange.opacity(0.85))
                                .foregroundColor(.white)

                            ImageContainerView(image: masterImage, scale: syncScale, offset: syncOffset)
                        }
                    } else {
                        VStack(spacing: 4) {
                            Text(derivativeFilename)
                                .font(.caption.bold())
                                .padding(.vertical, 4)
                                .frame(maxWidth: .infinity)
                                .background(Color.blue.opacity(0.85))
                                .foregroundColor(.white)

                            ImageContainerView(image: derivativeImage, scale: syncScale, offset: syncOffset)
                        }
                    }
                }
                .background(Color.black)
            }
            .navigationTitle("Lineage Comparison")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .task {
                await loadPairedImages()
            }
        }
    }

    private func loadPairedImages() async {
        // Load master RAW asset
        let resources = PHAssetResource.assetResources(for: item.asset)
        let primaryRes = resources.first(where: { $0.type == .photo || $0.type == .alternatePhoto }) ?? resources.first
        let primaryName = primaryRes?.originalFilename ?? "Master"

        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true

        PHImageManager.default().requestImage(
            for: item.asset,
            targetSize: CGSize(width: 1024, height: 1024),
            contentMode: .aspectFit,
            options: options
        ) { image, _ in
            DispatchQueue.main.async {
                self.masterImage = image
                self.masterFilename = "\(primaryName) (RAW)"
            }
        }

        // Load paired derivative asset if derivativeLocalId exists
        if let derivativeId = item.derivativeLocalId {
            let cleanId = derivativeId.hasPrefix("ph://") ? String(derivativeId.dropFirst(5)) : derivativeId
            let fetchResult = PHAsset.fetchAssets(withLocalIdentifiers: [cleanId], options: nil)
            if let derivativeAsset = fetchResult.firstObject {
                let derivResources = PHAssetResource.assetResources(for: derivativeAsset)
                let derivName = derivResources.first?.originalFilename ?? "Derivative.JPG"

                PHImageManager.default().requestImage(
                    for: derivativeAsset,
                    targetSize: CGSize(width: 1024, height: 1024),
                    contentMode: .aspectFit,
                    options: options
                ) { image, _ in
                    DispatchQueue.main.async {
                        self.derivativeImage = image
                        self.derivativeFilename = "\(derivName) (JPEG Derivative)"
                    }
                }
            }
        } else {
            // Search for companion resource within the same PHAsset
            let cleanId = item.id.hasPrefix("ph://") ? String(item.id.dropFirst(5)) : item.id
            let allAssets = PHAsset.fetchAssets(withLocalIdentifiers: [cleanId], options: nil)
            if let asset = allAssets.firstObject {
                let resList = PHAssetResource.assetResources(for: asset)
                if let companionRes = resList.first(where: { $0.type == .alternatePhoto || ($0.type == .photo && item.isRaw) }) {
                    let buffer = NSMutableData()
                    PHAssetResourceManager.default().requestData(for: companionRes, options: nil, dataReceivedHandler: { chunk in
                        buffer.append(chunk)
                    }, completionHandler: { error in
                        if error == nil, let compImg = UIImage(data: buffer as Data) {
                            DispatchQueue.main.async {
                                self.derivativeImage = compImg
                                self.derivativeFilename = "\(companionRes.originalFilename) (JPEG)"
                            }
                        }
                    })
                }
            }
        }
    }
}

private struct ImageContainerView: View {
    let image: UIImage?
    let scale: CGFloat
    let offset: CGSize

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .scaleEffect(scale)
                    .offset(offset)
            } else {
                ProgressView()
                    .tint(.white)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
    }
}
