import Foundation
import Synchronization
import Testing

@testable import CappuccinoCore

private struct RecoveryConnector: BridgeWebSocketConnecting {
  let socket: any BridgeWebSocket
  func connect(url: URL) async throws -> any BridgeWebSocket { socket }
}

/// The receive deliberately does not observe Task cancellation. The transport must close
/// the socket synchronously to unblock it, just like URLSession's callback-based receive.
private final class WaitingSocket: BridgeWebSocket, Sendable {
  private struct State {
    var pending: CheckedContinuation<String?, any Error>?
    var closed = false
  }
  private let state = Mutex(State())

  var closed: Bool { state.withLock { $0.closed } }
  var receiving: Bool { state.withLock { $0.pending != nil } }

  func receiveText() async throws -> String? {
    try await withCheckedThrowingContinuation { continuation in
      let alreadyClosed = state.withLock {
        if $0.closed { return true }
        $0.pending = continuation
        return false
      }
      if alreadyClosed { continuation.resume(returning: nil) }
    }
  }

  func close() async { closeImmediately() }

  func closeImmediately() {
    let pending = state.withLock {
      $0.closed = true
      let pending = $0.pending
      $0.pending = nil
      return pending
    }
    pending?.resume(returning: nil)
  }
}

struct AgentStreamRecoveryTests {
  private func until(_ condition: () -> Bool) async {
    let deadline = ContinuousClock.now.advanced(by: .seconds(5))
    while !condition(), ContinuousClock.now < deadline { await Task.yield() }
    #expect(condition(), "Bounded stream synchronization deadline")
  }

  @Test func slowConsumerOverflowIsVisibleBoundedAndClosesProducer() async {
    let frame = #"{"event":"agent_status","session_id":"s","state":"working","detail":null}"#
    let socket = FakeBridgeWebSocket(frames: Array(repeating: frame, count: 200))
    // AsyncThrowingStream starts its producer eagerly. Withhold consumption until the
    // producer closes: this is actual backpressure, not a delay before transport startup.
    let stream = BridgeClient().agentEvents(
      base: URL(string: "http://127.0.0.1:7392")!, session: "s",
      connector: RecoveryConnector(socket: socket))
    await until { socket.closed }
    var count = 0
    var failure: (any Error)?
    do { for try await _ in stream { count += 1 } } catch { failure = error }
    #expect(count == 64)
    #expect(failure as? BridgeError == .streamOverflow)
    #expect(socket.closed)
  }

  @Test func cancellationClosesEvenANonCancellationAwareReceive() async {
    let socket = WaitingSocket()
    let stream = BridgeClient().agentEvents(
      base: URL(string: "http://127.0.0.1:7392")!, session: "s",
      connector: RecoveryConnector(socket: socket))
    let consumer = Task {
      do { for try await _ in stream {} } catch {}
    }
    await until { socket.receiving }
    consumer.cancel()
    await consumer.value
    await until { socket.closed }
    #expect(!socket.receiving)
  }

  @Test func errorEnvelopeClosesSocketAndRemainsVisible() async {
    let socket = FakeBridgeWebSocket(frames: [
      #"{"event":"error","code":"upstream_unavailable","message":"Source unavailable"}"#
    ])
    var received: [AgentStreamEvent] = []
    do {
      for try await event in BridgeClient().agentEvents(
        base: URL(string: "http://127.0.0.1:7392")!, session: "s",
        connector: RecoveryConnector(socket: socket))
      {
        #expect(socket.closed)
        received.append(event)
      }
    } catch { Issue.record("Error envelope must be observable, not replaced: \(error)") }
    #expect(received == [.error(code: "upstream_unavailable", message: "Source unavailable")])
  }
}
