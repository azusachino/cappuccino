import Foundation
import DaemonCore

// cappuccino-daemon: spike A companion daemon.
//
//   cappuccino-daemon [--port 7391] [--herdr-socket PATH]
//
// Token source: $CAPP_SPIKE_TOKEN or ~/Library/Application Support/
// cappuccino-spike/pairing-token (created 0600, never logged, never committed).

let arguments = ProcessInfo.processInfo.arguments.dropFirst()
var port: UInt16 = 7391
var herdrSocket: String? = nil
var iterator = arguments.makeIterator()
while let argument = iterator.next() {
  switch argument {
  case "--port":
    port = UInt16(iterator.next() ?? "7391") ?? 7391
  case "--herdr-socket":
    herdrSocket = iterator.next()
  default:
    FileHandle.standardError.write(Data("usage: cappuccino-daemon [--port N] [--herdr-socket P]\n".utf8))
    exit(2)
  }
}

let machine = MachineIdentity()
let herdr = HerdrClient(socketPath: herdrSocket)
let pairing = PairingStore()
let catalog = AgentCatalog(client: herdr, machineId: machine.machineId)
let state = DaemonState(pairing: pairing, catalog: catalog)
let listener = TcpListener(port: port, state: state, herdr: herdr)

// Write the token path for the operator; never the token itself.
print("cappuccino-daemon: listening on 127.0.0.1:\(port), machine_id \(machine.machineId)")
print("token source: \(ProcessInfo.processInfo.environment["CAPP_SPIKE_TOKEN"] != nil ? "$CAPP_SPIKE_TOKEN" : "~/Library/Application Support/cappuccino-spike/pairing-token")")

do {
  try listener.bind()
} catch {
  FileHandle.standardError.write(Data("\(error)\n".utf8))
  exit(1)
}

// Poll loop: read-only pane reads every 400 ms; wakes stream connections.
Thread {
  while true {
    state.poll(client: herdr)
    state.awaitChange(timeout: 0.4)
  }
}.start()

Thread { listener.acceptLoop() }.start()
dispatchMain()
