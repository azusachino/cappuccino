import Foundation

/// Ports shortModel and formatTokens from ui/CappuccinoShell.kt (session status line).

/// "claude-opus-5-5" reads as "opus 5.5"; other model ids pass through unchanged.
public func shortModel(_ model: String) -> String {
  let bare = model.hasPrefix("claude-") ? String(model.dropFirst("claude-".count)) : model
  guard
    let regex = try? NSRegularExpression(pattern: #"-(\d+)-(\d+)$"#),
    let match = regex.firstMatch(in: bare, range: NSRange(bare.startIndex..., in: bare))
  else { return bare }
  return regex.stringByReplacingMatches(
    in: bare, range: match.range, withTemplate: " $1.$2")
}

/// 950 as "950", 76000 as "76K", 1234567 as "1.2M". A whole ".0" is dropped.
public func formatTokens(_ count: Int64) -> String {
  let text: String
  if count >= 1_000_000 {
    text = String(format: "%.1fM", Double(count) / 1_000_000)
  } else if count >= 1_000 {
    text = String(format: "%.1fK", Double(count) / 1_000)
  } else {
    text = String(count)
  }
  return text.replacingOccurrences(of: ".0", with: "")
}
