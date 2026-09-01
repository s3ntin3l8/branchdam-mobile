import SwiftUI
import Photos

public struct WelcomeView: View {
    @State private var authorizationStatus: PHAuthorizationStatus = .notDetermined
    @State private var showMainApp = false

    public init() {}

    public var body: some View {
        if showMainApp {
            ContentView()
        } else {
            VStack(spacing: 24) {
                Spacer()

                BrandMonogramView(size: 96)

                Text("Welcome to branchDAM")
                    .font(.largeTitle.bold())

                Text("branchDAM preserves your lossless photo masters and manages storage via verified archival to your Tier 3 NAS.")
                    .multilineTextAlignment(.center)
                    .foregroundColor(.secondary)
                    .padding(.horizontal, 32)

                VStack(alignment: .leading, spacing: 12) {
                    Label("Reads your camera roll to detect RAW + JPEG pairs", systemImage: "camera.fill")
                    Label("Uploads lossless masters to your server", systemImage: "arrow.up.circle.fill")
                    Label("Reclaims local storage after verified archival", systemImage: "sparkles")
                }
                .font(.subheadline)
                .padding(.horizontal, 48)

                Spacer()

                Button(action: requestPhotoAccess) {
                    Text("Grant Camera Roll Access")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.accentColor)
                        .foregroundColor(.white)
                        .cornerRadius(16)
                }
                .padding(.horizontal, 32)

                Button(action: { showMainApp = true }) {
                    Text("Set Up Later")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                .padding(.bottom, 16)
            }
            .onAppear {
                authorizationStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)
                if authorizationStatus == .authorized || authorizationStatus == .limited {
                    showMainApp = true
                }
            }
        }
    }

    private func requestPhotoAccess() {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
            DispatchQueue.main.async {
                authorizationStatus = status
                if status == .authorized || status == .limited {
                    // E.1: On first-launch grant, start the engine and
                    // observer immediately so the user gets a live pipeline
                    // rather than waiting for a full app relaunch.
                    BranchDamApp.startEngineIfNeeded()
                    showMainApp = true
                }
            }
        }
    }
}
