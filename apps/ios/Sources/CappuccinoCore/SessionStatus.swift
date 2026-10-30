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

/// Context window inference based on known models and agents when not explicitly reported.
public func defaultContextWindow(model: String?, agent: String? = nil) -> Int64 {
  let m = model?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
  let a = agent?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
  if let m, m.contains("terra") || m.contains("gpt-5") || m.contains("gpt-6") {
    return 500_000
  }
  if let m, m.contains("gemini") || m.contains("glm") {
    return 1_000_000
  }
  if let a, a == "agy" || a == "antigravity" || a == "antigravity-cli" {
    return 1_000_000
  }
  if let m, m.contains("deepseek") {
    return 64_000
  }
  if let m, m.contains("gpt-4") || m.contains("o1") || m.contains("o3") || m.contains("qwen") {
    return 128_000
  }
  return 200_000
}

/// The context meter under the composer: percent and `tokens / window` when the window is known,
/// tokens alone otherwise. Nil when the bridge reported no token count.
public func contextSummary(_ stats: SessionStats) -> String? {
  guard let tokens = stats.contextTokens else { return nil }
  var pieces: [String] = []
  let window =
    stats.contextWindow
    ?? (stats.model != nil ? defaultContextWindow(model: stats.model) : nil)
  if let window, window > 0 {
    let percent = min(max(tokens * 100 / window, 0), 100)
    pieces.append("\(percent)%")
    pieces.append("\(formatTokens(tokens)) / \(formatTokens(window))")
  } else {
    pieces.append(formatTokens(tokens))
  }
  return pieces.joined(separator: " · ")
}

/// The model and effort chip: "opus 5.5 · low", or nil when neither is known.
public func modelChip(_ stats: SessionStats?) -> String? {
  guard let stats else { return nil }
  let pieces = [stats.model.map(shortModel), stats.thinkingLevel].compactMap { $0 }
  return pieces.isEmpty ? nil : pieces.joined(separator: " · ")
}

/// An agent is idle when its live status is neither working nor blocked on an answer.
public func isIdle(_ liveStatus: String) -> Bool {
  liveStatus != "working" && liveStatus != "blocked"
}
