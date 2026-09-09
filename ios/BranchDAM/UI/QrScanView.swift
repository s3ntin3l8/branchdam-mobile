import SwiftUI
import VisionKit

/// Sheet that presents a live QR code scanner and calls back with the
/// parsed `ApplePairingConfig` on a successful `branchdam://` scan.
public struct QrScanSheet: View {
    public let onScanned: (ApplePairingConfig) -> Void
    @Environment(\.dismiss) private var dismiss

    public init(onScanned: @escaping (ApplePairingConfig) -> Void) {
        self.onScanned = onScanned
    }

    public var body: some View {
        NavigationStack {
            Group {
                if DataScannerViewController.isSupported {
                    DataScannerRepresentable { config in
                        onScanned(config)
                        dismiss()
                    }
                    .ignoresSafeArea(edges: .bottom)
                } else {
                    ContentUnavailableView(
                        "Scanner Unavailable",
                        systemImage: "qrcode.viewfinder",
                        description: Text("QR scanning requires a device with a camera.")
                    )
                }
            }
            .navigationTitle("Scan QR Code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}

private struct DataScannerRepresentable: UIViewControllerRepresentable {
    let onScanned: (ApplePairingConfig) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onScanned: onScanned)
    }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        DispatchQueue.main.async {
            try? scanner.startScanning()
        }
        return scanner
    }

    func updateUIViewController(_ uiViewController: DataScannerViewController, context: Context) {}

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onScanned: (ApplePairingConfig) -> Void
        private var scanned = false

        init(onScanned: @escaping (ApplePairingConfig) -> Void) {
            self.onScanned = onScanned
        }

        func dataScanner(
            _ dataScanner: DataScannerViewController,
            didAdd addedItems: [RecognizedItem],
            allItems: [RecognizedItem]
        ) {
            guard !scanned else { return }
            for item in addedItems {
                if case .barcode(let code) = item,
                   let value = code.payloadStringValue,
                   let config = AppleQrParser.parse(uriString: value) {
                    scanned = true
                    dataScanner.stopScanning()
                    onScanned(config)
                    return
                }
            }
        }
    }
}
