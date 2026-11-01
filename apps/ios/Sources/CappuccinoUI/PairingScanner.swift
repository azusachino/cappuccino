#if os(iOS)
  import SwiftUI
  import VisionKit

  /// Scans a bridge pairing QR code with the camera. The first QR payload goes to `onPayload`
  /// once; the caller closes the sheet and decides when to pair.
  @MainActor struct PairingScanner: UIViewControllerRepresentable {
    let onPayload: (String) -> Void

    static var isAvailable: Bool {
      DataScannerViewController.isSupported && DataScannerViewController.isAvailable
    }

    func makeUIViewController(context: Context) -> DataScannerViewController {
      let scanner = DataScannerViewController(
        recognizedDataTypes: [.barcode(symbologies: [.qr])], qualityLevel: .balanced,
        recognizesMultipleItems: false, isHighFrameRateTrackingEnabled: false,
        isPinchToZoomEnabled: true, isGuidanceEnabled: true, isHighlightingEnabled: true)
      scanner.delegate = context.coordinator
      return scanner
    }

    func updateUIViewController(_ scanner: DataScannerViewController, context: Context) {
      if !scanner.isScanning { try? scanner.startScanning() }
    }

    static func dismantleUIViewController(
      _ scanner: DataScannerViewController, coordinator: Coordinator
    ) {
      scanner.stopScanning()
    }

    func makeCoordinator() -> Coordinator {
      Coordinator(onPayload: onPayload)
    }

    @MainActor final class Coordinator: NSObject, DataScannerViewControllerDelegate {
      private let onPayload: (String) -> Void
      private var delivered = false

      init(onPayload: @escaping (String) -> Void) {
        self.onPayload = onPayload
      }

      func dataScanner(
        _ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem],
        allItems: [RecognizedItem]
      ) {
        deliver(addedItems)
      }

      func dataScanner(_ dataScanner: DataScannerViewController, didTapOn item: RecognizedItem) {
        deliver([item])
      }

      private func deliver(_ items: [RecognizedItem]) {
        guard !delivered else { return }
        for case .barcode(let code) in items {
          guard let payload = code.payloadStringValue, !payload.isEmpty else { continue }
          delivered = true
          onPayload(payload)
          return
        }
      }
    }
  }

  /// The scanner in a sheet, with the Camera-unavailable case explained rather than shown blank.
  @MainActor struct PairingScannerSheet: View {
    let onPayload: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
      NavigationStack {
        Group {
          if PairingScanner.isAvailable {
            PairingScanner { payload in
              onPayload(payload)
              dismiss()
            }
            .ignoresSafeArea()
          } else {
            ContentUnavailableView(
              "Camera scanning unavailable", systemImage: "qrcode.viewfinder",
              description: Text("Paste the pairing payload instead."))
          }
        }
        .navigationTitle("Scan pairing code")
        .inlineTitle()
        .toolbar {
          ToolbarItem(placement: .cancellationAction) {
            Button("Cancel") { dismiss() }
          }
        }
      }
    }
  }
#endif
