import CappuccinoCore
import SwiftUI

/// Ports ui/ConnectionLogsSheet.kt: the connection log, newest first, with copy and clear.
/// Status colour always comes with its level word, never alone.
@MainActor struct ConnectionLogsSheet: View {
  let log: ConnectionLog
  @Environment(\.colorScheme) private var colorScheme

  var body: some View {
    let palette = CatppuccinPalette.current(colorScheme)
    NavigationStack {
      List(log.entries.reversed()) { entry in
        VStack(alignment: .leading, spacing: 4) {
          HStack(spacing: 8) {
            Text(entry.timestamp, format: .dateTime.hour().minute().second())
              .font(.caption.monospacedDigit())
              .foregroundStyle(palette.muted)
            Text(entry.category)
              .font(.caption2.bold())
              .foregroundStyle(palette.muted)
            Spacer(minLength: 0)
            Text(entry.level.word)
              .font(.caption2.bold())
              .foregroundStyle(entry.level.color(palette))
          }
          Text(entry.message)
            .font(.callout)
            .textSelection(.enabled)
        }
      }
      .overlay {
        if log.entries.isEmpty {
          ContentUnavailableView(
            "No connection events yet", systemImage: "list.bullet.rectangle")
        }
      }
      .navigationTitle("Connection logs")
      .inlineTitle()
      .toolbar {
        ToolbarItem(placement: .primaryAction) {
          ShareLink(item: copyText) {
            Label("Copy", systemImage: "doc.on.doc")
          }
          .disabled(log.entries.isEmpty)
        }
        ToolbarItem(placement: .destructiveAction) {
          Button("Clear", role: .destructive) { log.clear() }
            .disabled(log.entries.isEmpty)
        }
      }
    }
  }

  private var copyText: String {
    log.entries.map { entry in
      "\(entry.timestamp.formatted(.dateTime.hour().minute().second())) "
        + "[\(entry.level.word)] \(entry.category): \(entry.message)"
    }.joined(separator: "\n")
  }
}

extension ConnectionLogLevel {
  fileprivate var word: String {
    switch self {
    case .info: "INFO"
    case .warn: "WARN"
    case .error: "ERROR"
    }
  }

  fileprivate func color(_ palette: CatppuccinPalette) -> Color {
    switch self {
    case .info: palette.muted
    case .warn: palette.warn
    case .error: palette.error
    }
  }
}
