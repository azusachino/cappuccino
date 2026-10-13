import Foundation

/// The text an owner sees for a failed action: the bridge's own reason, without transport prefixes.
public enum OwnerMessage {
  public static func text(for error: Error) -> String {
    switch error as? DaemonClientError {
    case .protocolError(let detail)?: return detail
    case let other?: return other.message
    case nil: return error.localizedDescription
    }
  }
}
