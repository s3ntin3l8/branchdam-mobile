import SwiftUI
import UniformTypeIdentifiers

/// Entry point for importing from a camera card: opens the Files folder
/// picker so the user can choose the card (or its DCIM folder) from a
/// USB-C / Lightning card reader. The scan, confirmation and progress
/// overlays are hosted by ContentView, which observes the same manager.
public struct AppleOtgCardImportButton: View {
    @State private var isPickerPresented = false

    public init() {}

    public var body: some View {
        Button {
            isPickerPresented = true
        } label: {
            Label("Import from SD Card", systemImage: "sdcard")
                .font(.headline)
                .frame(maxWidth: .infinity)
                .padding()
                .background(Color(.secondarySystemBackground))
                .foregroundColor(.primary)
                .cornerRadius(12)
        }
        .accessibilityHint(Text("Choose a camera card or its DCIM folder in Files"))
        .fileImporter(
            isPresented: $isPickerPresented,
            allowedContentTypes: [.folder],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first {
                    AppleOtgIngestManager.shared.onCardFolderPicked(url)
                }
            case .failure(let error):
                AppleOtgIngestManager.shared.onCardFolderPickerFailed(error.localizedDescription)
            }
        }
    }
}

public struct AppleOtgImportConfirmationView: View {
    public let scanResult: AppleOtgScanResult
    public let onConfirm: () -> Void
    public let onDismiss: () -> Void

    public init(
        scanResult: AppleOtgScanResult,
        onConfirm: @escaping () -> Void,
        onDismiss: @escaping () -> Void
    ) {
        self.scanResult = scanResult
        self.onConfirm = onConfirm
        self.onDismiss = onDismiss
    }

    public var body: some View {
        VStack(spacing: 20) {
            VStack(spacing: 8) {
                Image(systemName: "sdcard.fill")
                    .font(.system(size: 48))
                    .foregroundColor(.accentColor)

                Text("USB-C SD Card Detected")
                    .font(.title2)
                    .fontWeight(.bold)

                Text(scanResult.deviceLabel)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
            .padding(.top, 16)

            VStack(alignment: .leading, spacing: 12) {
                Text("Found \(scanResult.totalCount) media items (\(scanResult.formattedTotalSize)):")
                    .font(.headline)

                VStack(alignment: .leading, spacing: 8) {
                    if scanResult.rawCount > 0 {
                        HStack {
                            Image(systemName: "camera.fill")
                                .foregroundColor(.accentColor)
                            Text("RAW Photos: \(scanResult.rawCount)")
                                .font(.body)
                        }
                    }
                    if scanResult.jpegCount > 0 {
                        HStack {
                            Image(systemName: "photo.fill")
                                .foregroundColor(.accentColor)
                            Text("JPEGs / HEICs: \(scanResult.jpegCount)")
                                .font(.body)
                        }
                    }
                    if scanResult.videoCount > 0 {
                        HStack {
                            Image(systemName: "video.fill")
                                .foregroundColor(.accentColor)
                            Text("Videos: \(scanResult.videoCount)")
                                .font(.body)
                        }
                    }
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(UIColor.secondarySystemBackground))
                .cornerRadius(12)

                Text("Confirm import to stage full-resolution masters into branchDAM local queue for sync.")
                    .font(.footnote)
                    .foregroundColor(.secondary)
            }
            .padding(.horizontal)

            Spacer()

            VStack(spacing: 12) {
                Button(action: onConfirm) {
                    Text("Import to branchDAM")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.accentColor)
                        .foregroundColor(.white)
                        .cornerRadius(12)
                }

                Button(action: onDismiss) {
                    Text("Skip / Cancel")
                        .font(.body)
                        .foregroundColor(.secondary)
                }
            }
            .padding(.horizontal)
            .padding(.bottom, 16)
        }
        .padding()
    }
}

public struct AppleOtgProgressView: View {
    public let progress: AppleOtgIngestProgress
    public let onCancel: () -> Void

    public var body: some View {
        VStack(spacing: 16) {
            Text("Importing Media (\(progress.currentFileIndex)/\(progress.totalFiles))")
                .font(.headline)

            ProgressView(value: progress.percentage, total: 1.0)
                .progressViewStyle(LinearProgressViewStyle())
                .padding(.horizontal)

            Text(progress.currentFileName)
                .font(.caption)
                .foregroundColor(.secondary)

            Button("Cancel", action: onCancel)
                .foregroundColor(.red)
                .padding(.top, 8)
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(Color(UIColor.systemBackground))
        .cornerRadius(16)
        .shadow(radius: 8)
        .padding()
    }
}

public struct AppleOtgCompletedView: View {
    public let importedCount: Int
    public let totalBytes: Int64
    public let onDismiss: () -> Void
    public var failures: [AppleOtgFileFailure] = []

    public var body: some View {
        VStack(spacing: 16) {
            Image(systemName: failures.isEmpty ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                .font(.system(size: 44))
                .foregroundColor(failures.isEmpty ? .green : .orange)
                .accessibilityHidden(true)

            Text(failures.isEmpty ? "Import Complete" : "Import Finished With Errors")
                .font(.title3)
                .fontWeight(.bold)

            Text("Successfully staged \(importedCount) items (\(AppleOtgCandidate.formatBytes(totalBytes))) to branchDAM queue.")
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
                .padding(.horizontal)

            if !failures.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("\(failures.count) item(s) skipped:")
                        .font(.subheadline.bold())
                    ScrollView {
                        VStack(alignment: .leading, spacing: 8) {
                            ForEach(Array(failures.enumerated()), id: \.offset) { _, failure in
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(failure.relativePath)
                                        .font(.caption.bold())
                                    Text(failure.message)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                .accessibilityElement(children: .combine)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 200)
                }
                .padding()
                .background(Color(UIColor.secondarySystemBackground))
                .cornerRadius(12)
                .padding(.horizontal)
            }

            Button(action: onDismiss) {
                Text("Done")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.accentColor)
                    .foregroundColor(.white)
                    .cornerRadius(12)
            }
            .padding(.horizontal)
            .padding(.top, 8)
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(Color(UIColor.systemBackground))
        .cornerRadius(16)
        .shadow(radius: 10)
        .padding()
    }
}

public struct AppleOtgErrorView: View {
    public let errorMessage: String
    public let onDismiss: () -> Void

    public var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 44))
                .foregroundColor(.red)

            Text("Import Failed")
                .font(.title3)
                .fontWeight(.bold)

            Text(errorMessage)
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
                .padding(.horizontal)

            Button(action: onDismiss) {
                Text("OK")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.red)
                    .foregroundColor(.white)
                    .cornerRadius(12)
            }
            .padding(.horizontal)
            .padding(.top, 8)
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(Color(UIColor.systemBackground))
        .cornerRadius(16)
        .shadow(radius: 10)
        .padding()
    }
}
