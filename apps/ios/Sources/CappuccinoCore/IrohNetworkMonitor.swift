import Foundation
import Network

/// Drops cached iroh connections when the device's network path changes, so the next request
/// dials on the new path instead of waiting out a dead one. Ports the app-side redial that
/// io/TransportRouting.kt does on Android; the first path report only records the state.
public final class IrohNetworkMonitor: @unchecked Sendable {
  public static let shared = IrohNetworkMonitor()

  private let monitor = NWPathMonitor()
  private let queue = DispatchQueue(label: "app.cappuccino.iroh.network")
  private let lock = NSLock()
  private var started = false
  private var lastStatus: NWPath.Status?

  public func start() {
    let begin = lock.withLock { () -> Bool in
      guard !started else { return false }
      started = true
      return true
    }
    guard begin else { return }
    monitor.pathUpdateHandler = { [weak self] path in
      self?.pathChanged(path.status)
    }
    monitor.start(queue: queue)
  }

  private func pathChanged(_ status: NWPath.Status) {
    let changed = lock.withLock { () -> Bool in
      defer { lastStatus = status }
      guard let previous = lastStatus else { return false }
      return previous != status
    }
    guard changed else { return }
    Task { await IrohEndpointService.shared.dropConnections() }
  }
}
