import Foundation

// Opt-in per-poll diagnostics for the pane-diff pipeline. Enabled only when
// CAPP_DAEMON_TRACE=1; every line goes to stderr with line counts, overlap
// size and the ingest decision, so live-append behavior on a churning TUI can
// be diagnosed from the daemon's own trace.

public enum Trace {
  static let enabled = ProcessInfo.processInfo.environment["CAPP_DAEMON_TRACE"] == "1"
  private static let lock = NSLock()

  public static func log(_ message: String) {
    guard enabled else { return }
    lock.lock()
    FileHandle.standardError.write(
      Data("[trace \(Int(Date().timeIntervalSince1970 * 1000))] \(message)\n".utf8))
    lock.unlock()
  }
}
