import SwiftUI

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
