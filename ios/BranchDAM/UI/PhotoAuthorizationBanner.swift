import SwiftUI
import Photos
import UIKit

/// E.5: Banner shown when camera-roll access is denied, restricted, or
/// not yet determined. Extracted from ContentView.swift so the banner
/// is testable from the iOS unit test target.
public struct PhotoAuthorizationBanner: View {
    public let status: PHAuthorizationStatus

    public init(status: PHAuthorizationStatus) {
        self.status = status
    }

    public var body: some View {
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
                let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
                if status == .authorized || status == .limited {
                    BranchDamCoreBridge.shared.startEngineIfNeeded()
                }
            }
        }
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}
