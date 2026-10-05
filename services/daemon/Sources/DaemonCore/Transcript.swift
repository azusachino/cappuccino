import CryptoKit
import Foundation

// Transcript model for the read-only active-branch stream.
//
// Herdr exposes pane scrollback as raw text; it has no normalized transcripts
// or replay guarantees (herdr 0.9.3 socket docs). The daemon therefore derives
// entries itself: each line of `pane.read recent_unwrapped` becomes one entry,
// identified by content hash so clients can dedupe idempotently.

public struct TranscriptEntry: Sendable, Equatable {
  /// Daemon-assigned, monotonically increasing stream sequence number.
  public var seq: Int
  /// Stable content identity (sha256 of branch + text); the idempotency key.
  public var id: String
  public var kind: String  // "output" for raw pane output; "gap" placeholders use seq null
  public var text: String
  public var branch: String?
  /// False only while a line is still the trailing line of an active stream.
  public var complete: Bool

  public init(seq: Int, id: String, kind: String, text: String, branch: String?, complete: Bool) {
    self.seq = seq
    self.id = id
    self.kind = kind
    self.text = text
    self.branch = branch
    self.complete = complete
  }

  public var json: JSONValue {
    .object([
      "seq": seq >= 0 ? .number(Double(seq)) : .null,
      "id": .string(id),
      "kind": .string(kind),
      "text": .string(text),
      "branch": branch.map { .string($0) } ?? .null,
      "complete": .bool(complete),
    ])
  }
}

public enum EntryIdentity {
  /// sha256(branch + "\n" + text), hex-encoded first 32 bytes.
  public static func id(branch: String?, text: String) -> String {
    var data = Data((branch ?? "").utf8)
    data.append(0x0A)
    data.append(contentsOf: text.utf8)
    let digest = SHA256.hash(data: data)
    return digest.prefix(32).map { String(format: "%02x", $0) }.joined()
  }
}
