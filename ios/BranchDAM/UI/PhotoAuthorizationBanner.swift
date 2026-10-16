import SwiftUI
import Photos
import PhotosUI
import UIKit

/// E.5: Banner shown when camera-roll access is denied, restricted, or
/// not yet determined -- and, for `.limited` ("Select Photos"), a notice
/// that only the selected items are visible and backed up, with a
/// "Select More Photos" action. Extracted from ContentView.swift so the
/// banner is testable from the iOS unit test target.
@MainActor
public struct PhotoAuthorizationBanner: View {
    public let status: PHAuthorizationStatus
    private let onDismiss: (() -> Void)?

    public init(status: PHAuthorizationStatus, onDismiss: (() -> Void)? = nil) {
        self.status = status
        self.onDismiss = onDismiss
    }

    /// Whether ContentView shows the banner for [status]. `.limited` is a
    /// grant, but a partial one: photos outside the selection are never
    /// backed up, so the user is told and offered the picker.
    nonisolated public static func isShown(for status: PHAuthorizationStatus) -> Bool {
        switch status {
        case .notDetermined, .denied, .restricted, .limited:
            return true
        default:
            return false
        }
    }

    public var body: some View {
        VStack(spacing: 8) {
            Label(bannerTitle, systemImage: "exclamationmark.triangle.fill")
                .font(.subheadline.bold())
            Text(bannerMessage)
                .font(.caption)
                .multilineTextAlignment(.center)
            if status == .limited {
                HStack(spacing: 12) {
                    Button(action: presentLimitedLibraryPicker) {
                        Text(buttonLabel)
                            .font(.caption.bold())
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                            .background(Color.accentColor)
                            .foregroundColor(.white)
                            .cornerRadius(8)
                    }
                    if let onDismiss {
                        Button("Not Now", action: onDismiss)
                            .font(.caption)
                    }
                }
            } else if status == .notDetermined {
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

    /// The banner title for the current status. Exposed as `internal`
    /// so the unit test target can verify the title matches the
    /// expected message for each PHAuthorizationStatus case (denied,
    /// restricted, notDetermined, authorized).
    /// Localized with `String(localized:)`: a String passed to Label or
    /// Text is shown as is, not looked up in Localizable.strings.
    var bannerTitle: String {
        switch status {
        case .denied: return String(localized: "Camera Roll Access Denied")
        case .restricted: return String(localized: "Camera Roll Access Restricted")
        case .limited: return String(localized: "Limited Photo Access")
        default: return String(localized: "Camera Roll Access Needed")
        }
    }

    /// The button label for the current status. Exposed as `internal`
    /// so the unit test target can verify the correct action button
    /// text is shown for each status without relying on UIKit view
    /// hierarchy walking (which is fragile across simulator environments).
    var buttonLabel: String {
        switch status {
        case .notDetermined: return String(localized: "Grant Camera Roll Access")
        case .limited: return String(localized: "Select More Photos")
        default: return String(localized: "Open Settings")
        }
    }

    var bannerMessage: String {
        if status == .limited {
            return String(localized: "branchDAM can only see the photos you selected. Others are not detected or backed up.")
        }
        return String(localized: "branchDAM needs camera roll access to detect RAW + JPEG pairs and preserve your lossless masters.")
    }

    private func requestAccess() {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { _ in
            DispatchQueue.main.async {
                let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
                if status == .authorized || status == .limited {
                    // Opening the engine blocks; do it off the main actor.
                    Task {
                        await BranchDamCoreBridge.shared.startEngineIfNeededAsync()
                        PhotoKitObserver.shared.startObserving()
                    }
                }
            }
        }
    }

    /// Opens the system picker that lets the user add to (or change) a
    /// "Select Photos" grant. PhotoKitObserver picks up the new items
    /// through its library change observer.
    private func presentLimitedLibraryPicker() {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        guard var presenter = scene?.keyWindow?.rootViewController else { return }
        while let presented = presenter.presentedViewController {
            presenter = presented
        }
        PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: presenter)
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}
