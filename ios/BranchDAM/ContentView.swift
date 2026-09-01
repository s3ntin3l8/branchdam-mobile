import SwiftUI
import Photos

public struct ContentView: View {
    @ObservedObject private var otgManager = AppleOtgIngestManager.shared
    @State private var authorizationStatus: PHAuthorizationStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)

    public init() {}

    public var body: some View {
        ZStack {
            TabView {
                AuditTriageView()
                    .tabItem {
                        Label("Lineage", systemImage: "point.3.filled.connected.trianglepath.dotted")
                    }

                SafeSpaceView()
                    .tabItem {
                        Label("Safe Space", systemImage: "sparkles.rectangle.stack")
                    }

                QrPairingView()
                    .tabItem {
                        Label("Settings", systemImage: "gearshape")
                    }
            }

            // E.5: Photo authorization banner when access is not yet granted.
            // Shows for .notDetermined (Set Up Later path), .denied, and
            // .restricted so users always have an in-app path to enable access.
            if authorizationStatus == .notDetermined || authorizationStatus == .denied || authorizationStatus == .restricted {
                VStack {
                    PhotoAuthorizationBanner(status: authorizationStatus)
                    Spacer()
                }
            }

            if case .awaitingConfirmation(let scanResult) = otgManager.state {
                Color.black.opacity(0.4)
                    .edgesIgnoringSafeArea(.all)

                AppleOtgImportConfirmationView(
                    scanResult: scanResult,
                    onConfirm: { otgManager.confirmImport(scanResult: scanResult) },
                    onDismiss: { otgManager.cancelImport() }
                )
                .background(Color(UIColor.systemBackground))
                .cornerRadius(20)
                .shadow(radius: 12)
                .padding()
            } else if case .ingesting(let progress) = otgManager.state {
                Color.black.opacity(0.4)
                    .edgesIgnoringSafeArea(.all)

                AppleOtgProgressView(
                    progress: progress,
                    onCancel: { otgManager.cancelImport() }
                )
            } else if case .completed(let importedCount, let totalBytes) = otgManager.state {
                Color.black.opacity(0.4)
                    .edgesIgnoringSafeArea(.all)

                AppleOtgCompletedView(
                    importedCount: importedCount,
                    totalBytes: totalBytes,
                    onDismiss: { otgManager.reset() }
                )
            } else if case .error(let message) = otgManager.state {
                Color.black.opacity(0.4)
                    .edgesIgnoringSafeArea(.all)

                AppleOtgErrorView(
                    errorMessage: message,
                    onDismiss: { otgManager.reset() }
                )
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
            authorizationStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            // If the user granted access in Settings while the app was
            // backgrounded, start the engine + observer now.
            if authorizationStatus == .authorized || authorizationStatus == .limited {
                BranchDamApp.startEngineIfNeeded()
            }
        }
    }
}

/// E.5: Banner shown when camera-roll access is denied or restricted.
struct PhotoAuthorizationBanner: View {
    let status: PHAuthorizationStatus

    var body: some View {
        VStack(spacing: 8) {
            Label(bannerTitle, systemImage: "exclamationmark.triangle.fill")
                .font(.subheadline.bold())
            Text(bannerMessage)
                .font(.caption)
                .multilineTextAlignment(.center)
            if status == .notDetermined {
                Button(action: requestAccess) {
                    Text("Grant Camera Roll Access")
                        .font(.caption.bold())
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(Color.accentColor)
                        .foregroundColor(.white)
                        .cornerRadius(8)
                }
            } else {
                Button(action: openSettings) {
                    Text("Open Settings")
                        .font(.caption.bold())
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(Color.orange)
                        .foregroundColor(.white)
                        .cornerRadius(8)
                }
            }
        }
        .padding(12)
        .background(Color(UIColor.secondarySystemBackground))
        .cornerRadius(12)
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    private var bannerTitle: String {
        switch status {
        case .denied: return "Camera Roll Access Denied"
        case .restricted: return "Camera Roll Access Restricted"
        default: return "Camera Roll Access Needed"
        }
    }

    private var bannerMessage: String {
        "branchDAM needs camera roll access to detect RAW + JPEG pairs and preserve your lossless masters."
    }

    private func requestAccess() {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { _ in
            DispatchQueue.main.async {
                // The parent ContentView's onReceive will re-evaluate
                // authorizationStatus on next foreground cycle; trigger
                // engine init immediately if access was granted.
                let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
                if status == .authorized || status == .limited {
                    BranchDamApp.startEngineIfNeeded()
                }
            }
        }
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

#Preview {
    ContentView()
}
