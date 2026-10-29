/// Battery component index used in battery replies and pushes.
public enum Component: UInt8, Sendable, CaseIterable {
    case left = 0x01, right = 0x02, caseBattery = 0x03
}

public struct BatteryReading: Sendable, Equatable {
    public var component: Component
    /// `nil` when the buds report 0, which they use for "unknown" (e.g. case lid closed).
    public var percent: Int?
    public var isCharging: Bool

    public init(component: Component, percent: Int?, isCharging: Bool) {
        self.component = component
        self.percent = percent
        self.isCharging = isCharging
    }
}

public enum WearState: Sendable, Equatable {
    case inCase, outOfEar, inEar, unknown(UInt8)

    public init(raw: UInt8) {
        switch raw {
        case 0x00: self = .inCase
        case 0x01, 0x02: self = .outOfEar
        case 0x03: self = .inEar
        default: self = .unknown(raw)
        }
    }
}

/// Parsers for reply and push payloads. All validate lengths and return `nil` on malformed input.
/// Reply payloads start with a status byte; push payloads start with the event id.
public enum Parse {
    /// Body `<n> [<component> <raw>]…`, with level = `raw & 0x7F`, charging = `raw & 0x80`.
    /// Shared by the `0x8106` reply (after the status byte) and the event-`01` push (after the id).
    public static func batteryBody(_ body: ArraySlice<UInt8>) -> [BatteryReading]? {
        guard let count = body.first else { return nil }
        let entries = body.dropFirst()
        guard entries.count >= Int(count) * 2 else { return nil }
        var readings: [BatteryReading] = []
        var index = entries.startIndex
        for _ in 0..<Int(count) {
            let id = entries[index], raw = entries[index + 1]
            index += 2
            guard let component = Component(rawValue: id) else { continue }   // e.g. FF padding
            let level = Int(raw & 0x7F)
            readings.append(BatteryReading(component: component,
                                           percent: level == 0 ? nil : min(level, 100),
                                           isCharging: raw & 0x80 != 0))
        }
        return readings
    }

    /// `0x8106` reply: `00 <n> [<component> <raw>]…`
    public static func batteryReply(_ payload: [UInt8]) -> [BatteryReading]? {
        guard payload.first == 0x00 else { return nil }
        return batteryBody(payload.dropFirst())
    }

    /// Noise body after the status byte (reply) or event id (push).
    /// Two layouts are seen: `<type> 01 <value>` and `<type> <value>`. Only the mode type is returned.
    public static func noiseBody(_ body: ArraySlice<UInt8>) -> UInt8? {
        let b = Array(body)
        switch b.count {
        case 2 where b[0] == NoiseConfigType.mode: return b[1]
        case 3... where b[0] == NoiseConfigType.mode: return b[2]
        default: return nil
        }
    }

    /// `0x810C` reply: `00 01 01 <value>` (or `00 01 <value>`).
    public static func noiseReply(_ payload: [UInt8]) -> UInt8? {
        guard payload.first == 0x00 else { return nil }
        return noiseBody(payload.dropFirst())
    }

    /// Wear body: `<n> [<side> <state>]…`. Shared by the `0x8109` reply and the event-`02` push.
    public static func wearBody(_ body: ArraySlice<UInt8>) -> [Component: WearState]? {
        guard let count = body.first else { return nil }
        let entries = body.dropFirst()
        guard entries.count >= Int(count) * 2 else { return nil }
        var out: [Component: WearState] = [:]
        var index = entries.startIndex
        for _ in 0..<Int(count) {
            if let side = Component(rawValue: entries[index]) { out[side] = WearState(raw: entries[index + 1]) }
            index += 2
        }
        return out
    }

    public static func wearReply(_ payload: [UInt8]) -> [Component: WearState]? {
        guard payload.first == 0x00 else { return nil }
        return wearBody(payload.dropFirst())
    }

    /// `0x8103` reply: `00 <3-byte product id, LE>` → `"063012"`.
    public static func productID(_ payload: [UInt8]) -> String? {
        guard payload.count >= 4, payload[0] == 0x00 else { return nil }
        return Hex.byte(payload[3]) + Hex.byte(payload[2]) + Hex.byte(payload[1])
    }

    /// `0x8105` reply: `00 <n>` + ASCII `component,kind,version` triples. Returns the text.
    public static func firmware(_ payload: [UInt8]) -> String? {
        guard payload.count >= 2, payload[0] == 0x00 else { return nil }
        let printable = payload.dropFirst(2).filter { $0 >= 0x20 && $0 < 0x7F }
        return String(decoding: printable, as: UTF8.self)
    }
}

/// A decoded `0x0204` push.
public enum Push: Sendable, Equatable {
    case battery([BatteryReading])
    case wear([Component: WearState])
    case noiseMode(UInt8)
    case other(event: UInt8, body: [UInt8])

    public init?(_ frame: Frame) {
        guard frame.command == Command.eventPush, let id = frame.payload.first else { return nil }
        let body = frame.payload.dropFirst()
        switch id {
        case Event.battery:
            guard let readings = Parse.batteryBody(body) else { return nil }
            self = .battery(readings)
        case Event.wearStatus:
            guard let wear = Parse.wearBody(body) else { return nil }
            self = .wear(wear)
        case Event.noiseMode:
            guard let value = Parse.noiseBody(body) else { return nil }
            self = .noiseMode(value)
        default:
            self = .other(event: id, body: Array(body))
        }
    }
}
