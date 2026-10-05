import Foundation

// Minimal JSON value model: the Herdr socket and our client wire format are
// both newline-delimited JSON with loose schemas; a typed model per message
// would freeze the wire format this spike is meant to probe.

public enum JSONValue: Sendable, Equatable {
  case null
  case bool(Bool)
  case number(Double)
  case string(String)
  case array([JSONValue])
  case object([String: JSONValue])

  public var stringValue: String? {
    if case .string(let s) = self { return s }
    return nil
  }

  public var doubleValue: Double? {
    if case .number(let n) = self { return n }
    return nil
  }

  public var intValue: Int? {
    if case .number(let n) = self, n == n.rounded() { return Int(n) }
    return nil
  }

  public var objectValue: [String: JSONValue]? {
    if case .object(let o) = self { return o }
    return nil
  }

  public var arrayValue: [JSONValue]? {
    if case .array(let a) = self { return a }
    return nil
  }

  public subscript(key: String) -> JSONValue? {
    objectValue?[key]
  }

  public static func decode(_ data: Data) throws -> JSONValue {
    let raw = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    return JSONValue(raw)
  }

  public init(_ raw: Any) {
    switch raw {
    case is NSNull: self = .null
    case let n as NSNumber:
      // Distinguish booleans from numbers; JSONSerialization boxes Bool as NSNumber.
      if CFGetTypeID(n) == CFBooleanGetTypeID() {
        self = .bool(n.boolValue)
      } else {
        self = .number(n.doubleValue)
      }
    case let s as String: self = .string(s)
    case let a as [Any]: self = .array(a.map { JSONValue($0) })
    case let o as [String: Any]:
      self = .object(o.mapValues { JSONValue($0) })
    default: self = .null
    }
  }

  public func encode() throws -> Data {
    var any: Any = NSNull()
    switch self {
    case .null: break
    case .bool(let b): any = b
    case .number(let n):
      if n == n.rounded(), abs(n) < 1e15 {
        any = Int(n)
      } else {
        any = n
      }
    case .string(let s): any = s
    case .array(let a): any = a.map { try! $0.encodeAny() }
    case .object(let o): any = o.mapValues { try! $0.encodeAny() }
    }
    return try JSONSerialization.data(withJSONObject: any, options: [.sortedKeys])
  }

  private func encodeAny() throws -> Any {
    switch self {
    case .null: return NSNull()
    case .bool(let b): return b
    case .number(let n): return n
    case .string(let s): return s
    case .array(let a): return try a.map { try $0.encodeAny() }
    case .object(let o): return try o.mapValues { try $0.encodeAny() }
    }
  }

  public var rendered: String {
    (try? String(data: encode(), encoding: .utf8)) ?? "null"
  }
}
