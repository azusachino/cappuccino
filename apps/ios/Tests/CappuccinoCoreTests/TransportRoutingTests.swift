import Foundation
import Testing

@testable import CappuccinoCore

/// Scripted transports: each kind either answers with itself or throws its scripted error.
private actor Script {
  private var failures: [TransportKind: any Error & Sendable]
  private(set) var calls: [TransportKind] = []

  init(failing: [TransportKind: any Error & Sendable] = [:]) {
    failures = failing
  }

  func run(_ kind: TransportKind) throws -> TransportKind {
    calls.append(kind)
    if let error = failures[kind] { throw error }
    return kind
  }

  func setFailure(_ kind: TransportKind, _ error: (any Error & Sendable)?) {
    failures[kind] = error
  }
}

private actor Counter {
  private(set) var value = 0
  func increment() { value += 1 }
}

private func router(
  _ kinds: [TransportKind] = [.iroh, .tailnet], irohPath: IrohPathKind = .direct,
  rebuild: @escaping @Sendable () async -> Void = {}
) -> MachineRouter<TransportKind> {
  MachineRouter(
    clients: Dictionary(uniqueKeysWithValues: kinds.map { ($0, $0) }), now: { 0 },
    irohPath: { irohPath }, rebuildIroh: rebuild)
}

/// The error a request ends with, or nil when it succeeded.
private func failure(
  _ router: MachineRouter<TransportKind>, mutation: Bool = false, _ script: Script
) async -> (any Error)? {
  do {
    _ = try await router.request(mutation: mutation) { try await script.run($0) }
    return nil
  } catch {
    return error
  }
}

@Suite struct TransportRoutingTests {
  @Test func probesIrohFirstThenFallsBackToTailnetAndStaysThere() async throws {
    let script = Script(failing: [.iroh: URLError(.timedOut)])
    let routed = router()
    let result = try await routed.request { try await script.run($0) }
    #expect(result == .tailnet)
    #expect(await script.calls == [.iroh, .tailnet])

    // The tailnet that worked is sticky: iroh is not probed again until it fails.
    _ = try await routed.request { try await script.run($0) }
    #expect(await script.calls == [.iroh, .tailnet, .tailnet])
  }

  @Test func aBridgeAnswerIsReturnedWithoutFallThroughOrFailureCount() async {
    let script = Script(failing: [.iroh: BridgeError.protocolError("bad request")])
    let routed = router()
    let error = await failure(routed, script)
    #expect(error as? BridgeError == .protocolError("bad request"))
    #expect(await script.calls == [.iroh])
  }

  @Test func aMutationFailingAfterSendIsNeverResent() async {
    let script = Script(failing: [.iroh: URLError(.timedOut)])
    let error = await failure(router(), mutation: true, script)
    #expect(error as? TransportRoutingError == .mutationOutcomeUnknown)
    #expect(await script.calls == [.iroh])
  }

  @Test func aMutationThatNeverConnectedMayFallThrough() async {
    let script = Script(failing: [.iroh: URLError(.cannotConnectToHost)])
    let error = await failure(router(), mutation: true, script)
    #expect(error == nil)
    #expect(await script.calls == [.iroh, .tailnet])
  }

  @Test func threeFailedIrohAttemptsRebuildTheEndpointOnce() async {
    let rebuilds = Counter()
    let script = Script(failing: [.iroh: URLError(.networkConnectionLost)])
    let routed = router(rebuild: { await rebuilds.increment() })
    for _ in 0..<irohRebuildAfterFailures {
      await routed.networkChanged()  // forces iroh to be probed again
      _ = await failure(routed, script)
    }
    #expect(await rebuilds.value == 1)
  }

  @Test func tailnetOnlyMachineNeverProbesIroh() async throws {
    let script = Script()
    let routed = router([.tailnet])
    _ = try await routed.request { try await script.run($0) }
    #expect(await script.calls == [.tailnet])
  }

  @Test func badgeShowsTheFirstReadingAndTheTailnetAtOnce() async {
    let routed = router(irohPath: .direct)
    let script = Script()
    _ = try? await routed.request { try await script.run($0) }
    #expect(await routed.badge() == .irohDirect)
    #expect(TransportBadge.irohDirect.label == "iroh direct")
  }
}

@Suite struct BadgeHoldDownTests {
  @Test func aDirectToRelayedSwitchWaitsForTheHoldDown() {
    var holdDown = BadgeHoldDown()
    #expect(holdDown.observe(.irohDirect, at: 0) == .irohDirect)
    #expect(holdDown.observe(.irohRelayed, at: 1) == .irohDirect)
    #expect(holdDown.observe(.irohRelayed, at: 10.9) == .irohDirect)
    #expect(holdDown.observe(.irohRelayed, at: 11) == .irohRelayed)
  }

  @Test func aSwitchThatDoesNotHoldIsDropped() {
    var holdDown = BadgeHoldDown()
    _ = holdDown.observe(.irohDirect, at: 0)
    _ = holdDown.observe(.irohRelayed, at: 1)
    _ = holdDown.observe(.irohDirect, at: 5)
    // A new switch starts its own hold-down, from the moment it is first seen again.
    #expect(holdDown.observe(.irohRelayed, at: 20) == .irohDirect)
    #expect(holdDown.observe(.irohRelayed, at: 30) == .irohRelayed)
  }

  @Test func aSwitchToTailnetAppliesImmediatelyAndMissingReadingsKeepTheBadge() {
    var holdDown = BadgeHoldDown()
    _ = holdDown.observe(.irohRelayed, at: 0)
    #expect(holdDown.observe(nil, at: 1) == .irohRelayed)
    #expect(holdDown.observe(.noReading, at: 2) == .irohRelayed)
    #expect(holdDown.observe(.tailnet, at: 3) == .tailnet)
  }

  @Test func pollCadenceFollowsTheShownBadge() {
    #expect(pollIntervalSeconds(.irohRelayed) == 30)
    #expect(pollIntervalSeconds(.irohDirect) == 5)
    #expect(pollIntervalSeconds(.tailnet) == 5)
    #expect(TransportBadge.noReading.label == nil)
  }
}
