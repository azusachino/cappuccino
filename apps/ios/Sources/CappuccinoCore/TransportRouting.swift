import Foundation

/// Ports io/TransportRouting.kt: picks the transport for one machine's requests. Iroh is probed
/// first, then tailnet. The transport that last worked is sticky until a request on it fails.
/// The concrete iroh client is supplied by the app; this file holds only the decision logic.

/// Transports a machine can carry. Declaration order is the probe order: iroh first.
public enum TransportKind: CaseIterable, Sendable {
  case iroh
  case tailnet
}

/// What a machine row shows. `none` means no reading yet.
public enum TransportBadge: Equatable, Sendable {
  case tailnet
  case irohDirect
  case irohRelayed
  case noReading

  public var label: String? {
    switch self {
    case .tailnet: "tailnet"
    case .irohDirect: "iroh direct"
    case .irohRelayed: "iroh relayed"
    case .noReading: nil
    }
  }
}

public enum IrohPathKind: Equatable, Sendable {
  case direct
  case relayed
  case none
}

/// Errors the router itself raises, as opposed to the bridge's own errors.
public enum TransportRoutingError: Error, Equatable, Sendable {
  /// The request may have reached the bridge before the transport failed. The change may be
  /// applied, so the owner re-checks instead of the app resending it.
  case mutationOutcomeUnknown
  case noTransport
}

/// Consecutive failed iroh attempts before the endpoint is rebuilt. Three keeps one flaky dial
/// from restarting the endpoint, and a bridge restart is still caught within three polls.
public let irohRebuildAfterFailures = 3

/// A direct⇄relayed change must hold this long (seconds) before the badge and poll cadence follow.
public let badgeHoldDownSeconds = 10.0

public let pollDirectSeconds = 5.0
public let pollRelayedSeconds = 30.0

/// Poll cadence for a machine, from the badge it shows (which already has the hold-down applied).
public func pollIntervalSeconds(_ badge: TransportBadge) -> Double {
  badge == .irohRelayed ? pollRelayedSeconds : pollDirectSeconds
}

/// Shows a direct/relayed switch only after it has held for `badgeHoldDownSeconds`. Any other
/// change (to tailnet, or the first reading) applies at once. A missing or `none` target keeps
/// what is shown, so a dial in progress does not blank the badge.
public struct BadgeHoldDown: Sendable {
  public private(set) var shown: TransportBadge = .noReading
  private var pending: TransportBadge?
  private var pendingSince = 0.0

  public init() {}

  public mutating func observe(_ target: TransportBadge?, at now: Double) -> TransportBadge {
    guard let target, target != .noReading, target != shown else {
      pending = nil
      return shown
    }
    guard shown.isIrohPath, target.isIrohPath else {
      shown = target
      pending = nil
      return shown
    }
    if pending != target {
      pending = target
      pendingSince = now
    }
    if now - pendingSince >= badgeHoldDownSeconds {
      shown = target
      pending = nil
    }
    return shown
  }
}

extension TransportBadge {
  fileprivate var isIrohPath: Bool { self == .irohDirect || self == .irohRelayed }
}

/// A failure where no answer came back: the connection or the iroh path failed, not the bridge.
func isTransportFailure(_ error: any Error) -> Bool {
  if case BridgeError.unreachable = error { return true }
  return error is URLError
}

/// A failure before any byte could reach the bridge, so resending cannot duplicate a change.
func isBeforeSend(_ error: any Error) -> Bool {
  guard let failure = error as? URLError else { return false }
  switch failure.code {
  case .cannotConnectToHost, .cannotFindHost, .dnsLookupFailed: return true
  default: return false
  }
}

/// Routes one machine's requests over its transports. Requests are serialized through the actor.
public actor MachineRouter<Client: Sendable> {
  private let clients: [TransportKind: Client]
  private let now: @Sendable () -> Double
  private let irohPath: @Sendable () -> IrohPathKind
  private let rebuildIroh: @Sendable () async -> Void
  private var sticky: TransportKind?
  private var irohFailures = 0
  private var holdDown = BadgeHoldDown()

  public init(
    clients: [TransportKind: Client],
    now: @escaping @Sendable () -> Double,
    irohPath: @escaping @Sendable () -> IrohPathKind = { .none },
    rebuildIroh: @escaping @Sendable () async -> Void = {}
  ) {
    self.clients = clients
    self.now = now
    self.irohPath = irohPath
    self.rebuildIroh = rebuildIroh
  }

  /// Runs `block` on the first transport that works, probing the rest in order on failure.
  /// A mutation is never resent once the request may have reached the bridge.
  public func request<T: Sendable>(
    mutation: Bool = false, _ block: @Sendable (Client) async throws -> T
  ) async throws -> T {
    var tried = Set<TransportKind>()
    var lastError: (any Error)?
    while let kind = nextKind(tried), let client = clients[kind] {
      tried.insert(kind)
      do {
        let result = try await block(client)
        recordSuccess(kind)
        return result
      } catch is CancellationError {
        throw CancellationError()
      } catch {
        guard isTransportFailure(error) else {
          // The bridge answered, so the path works and the error belongs to the caller.
          recordSuccess(kind)
          throw error
        }
        await recordFailure(kind)
        if mutation && !isBeforeSend(error) {
          throw TransportRoutingError.mutationOutcomeUnknown
        }
        lastError = error
      }
    }
    throw lastError ?? TransportRoutingError.noTransport
  }

  /// The badge for this machine now. Call it on each poll tick; the hold-down needs ticks.
  public func badge() -> TransportBadge {
    let raw: TransportBadge?
    switch sticky {
    case .tailnet:
      raw = .tailnet
    case .iroh:
      switch irohPath() {
      case .direct: raw = .irohDirect
      case .relayed: raw = .irohRelayed
      case .none: raw = nil
      }
    case nil:
      raw = nil
    }
    return holdDown.observe(raw, at: now())
  }

  /// Forget stickiness after a default-network change, so the order is probed again.
  public func networkChanged() {
    sticky = nil
  }

  /// Only transports this machine has a client for are candidates, so a tailnet-only machine
  /// never probes iroh.
  private func nextKind(_ tried: Set<TransportKind>) -> TransportKind? {
    let clients = self.clients
    let usable = { (kind: TransportKind) in !tried.contains(kind) && clients[kind] != nil }
    if let sticky, usable(sticky) { return sticky }
    return TransportKind.allCases.first(where: usable)
  }

  private func recordSuccess(_ kind: TransportKind) {
    sticky = kind
    if kind == .iroh { irohFailures = 0 }
  }

  private func recordFailure(_ kind: TransportKind) async {
    if sticky == kind { sticky = nil }
    guard kind == .iroh else { return }
    irohFailures += 1
    if irohFailures >= irohRebuildAfterFailures {
      irohFailures = 0
      await rebuildIroh()
    }
  }
}
