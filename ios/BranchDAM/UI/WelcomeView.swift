import SwiftUI
import Photos

/// Persisted first-run state. `WelcomeView` writes it from both buttons;
/// `BranchDAMApp` reads it at launch to pick the first screen.
public enum OnboardingState {
    public static func isCompleted(defaults: UserDefaults = .standard) -> Bool {
        defaults.bool(forKey: BranchDamKeys.onboardingCompleted.rawValue)
    }

    public static func markCompleted(defaults: UserDefaults = .standard) {
        defaults.set(true, forKey: BranchDamKeys.onboardingCompleted.rawValue)
    }

    /// Whether launch goes straight to the main tabs: onboarding was
    /// completed, or access is already granted (full or limited).
    public static func shouldSkipWelcome(
        status: PHAuthorizationStatus,
        defaults: UserDefaults = .standard
    ) -> Bool {
        isCompleted(defaults: defaults) || status == .authorized || status == .limited
    }
}

/// What the Welcome screen shows for a photo authorization status.
public enum WelcomeAccessState: Equatable {
    case needsRequest
    case denied
    case restricted
    case granted

    public init(_ status: PHAuthorizationStatus) {
        switch status {
        case .denied: self = .denied
        case .restricted: self = .restricted
        case .authorized, .limited: self = .granted
        default: self = .needsRequest
        }
    }

    /// Denied and restricted both dead-end the system prompt; only Settings helps.
    public var needsSettings: Bool { self == .denied || self == .restricted }
}

@MainActor
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

                if accessState.needsSettings {
                    let banner = PhotoAuthorizationBanner(status: authorizationStatus)
                    VStack(spacing: 8) {
                        Label(banner.bannerTitle, systemImage: "exclamationmark.triangle.fill")
                            .font(.subheadline.bold())
                        Text(banner.bannerMessage)
                            .font(.caption)
                            .multilineTextAlignment(.center)
                            .foregroundColor(.secondary)
                    }
                    .padding(.horizontal, 32)

                    Button(action: openSettings) {
                        Text(banner.buttonLabel)
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color.orange)
                            .foregroundColor(.white)
                            .cornerRadius(16)
                    }
                    .padding(.horizontal, 32)
                } else {
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
                }

                Button(action: {
                    OnboardingState.markCompleted()
                    showMainApp = true
                }) {
                    Text("Set Up Later")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                .padding(.bottom, 16)
            }
            .onAppear {
                refreshAuthorization()
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
                // The user may have granted access in Settings.
                refreshAuthorization()
            }
        }
    }

    private var accessState: WelcomeAccessState { WelcomeAccessState(authorizationStatus) }

    private func refreshAuthorization() {
        authorizationStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        if accessState == .granted {
            OnboardingState.markCompleted()
            // ContentView's foreground handler starts the engine on a
            // grant it sees; a grant made in Settings reaches here first.
            Task {
                await BranchDamCoreBridge.shared.startEngineIfNeededAsync()
                PhotoKitObserver.shared.startObserving()
            }
            showMainApp = true
        }
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    private func requestPhotoAccess() {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
            DispatchQueue.main.async {
                authorizationStatus = status
                if status == .authorized || status == .limited {
                    // E.1: On first-launch grant, start the engine and
                    // observer immediately so the user gets a live pipeline
                    // rather than waiting for a full app relaunch.
                    // Opening the engine blocks; do it off the main actor.
                    Task {
                        await BranchDamCoreBridge.shared.startEngineIfNeededAsync()
                        PhotoKitObserver.shared.startObserving()
                    }
                    OnboardingState.markCompleted()
                    showMainApp = true
                }
            }
        }
    }
}
