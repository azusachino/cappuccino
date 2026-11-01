import Foundation
import Observation

public enum ConnectionLogLevel: String, Equatable, Sendable {
  case info, warn, error
}

public struct ConnectionLogEntry: Identifiable, Equatable, Sendable {
  public let id: Int
  public let timestamp: Date
  public let level: ConnectionLogLevel
  public let category: String
  public let message: String
}

/// Ports io/ConnectionLogger.kt: a bounded, in-memory record of connection events for the logs
/// sheet. Nothing is persisted, and the oldest entries drop once the cap is reached.
@MainActor @Observable
public final class ConnectionLog {
  public static let capacity = 300

  public private(set) var entries: [ConnectionLogEntry] = []
  @ObservationIgnored private var nextID = 1
  @ObservationIgnored private let now: @Sendable () -> Date

  public init(now: @escaping @Sendable () -> Date = { Date() }) {
    self.now = now
  }

  public func log(_ category: String, _ message: String, level: ConnectionLogLevel = .info) {
    entries.append(
      ConnectionLogEntry(
        id: nextID, timestamp: now(), level: level, category: category, message: message))
    nextID += 1
    if entries.count > Self.capacity {
      entries.removeFirst(entries.count - Self.capacity)
    }
  }

  public func clear() {
    entries.removeAll()
  }
}
