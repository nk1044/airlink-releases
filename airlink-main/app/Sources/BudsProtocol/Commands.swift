/// Command identifiers (PROTOCOL.md §4). The high byte is the group:
/// `01` query, `02` notification, `04` set, `05` request from the buds.
public enum Command {
    public static let replyBit: UInt16 = 0x8000

    public static let hello: UInt16 = 0x0100
    public static let productID: UInt16 = 0x0103
    public static let firmware: UInt16 = 0x0105
    public static let battery: UInt16 = 0x0106
    public static let wearStatus: UInt16 = 0x0109
    public static let noiseRead: UInt16 = 0x010C
    public static let eventPush: UInt16 = 0x0204
    public static let subscribe: UInt16 = 0x0205
    public static let noiseSet: UInt16 = 0x0404
    public static let timeRequest: UInt16 = 0x0500

    public static func reply(to command: UInt16) -> UInt16 { command | replyBit }

    public static func name(_ command: UInt16) -> String {
        let base = command & ~replyBit
        let name: String
        switch base {
        case hello: name = "hello"
        case productID: name = "product id"
        case firmware: name = "firmware"
        case battery: name = "battery"
        case wearStatus: name = "wear status"
        case noiseRead: name = "noise read"
        case eventPush: name = "event push"
        case subscribe: name = "subscribe"
        case noiseSet: name = "noise set"
        case timeRequest: name = "time request"
        default: name = "cmd " + Hex.word(base)
        }
        return command & replyBit != 0 ? name + " reply" : name
    }
}

/// Event ids carried in the first payload byte of an `0x0204` push, and used when subscribing.
public enum Event {
    public static let battery: UInt8 = 0x01
    public static let wearStatus: UInt8 = 0x02
    public static let noiseMode: UInt8 = 0x03
}

/// Noise-control "type" byte in `0x010C` / `0x0404` / event `03` payloads.
public enum NoiseConfigType {
    public static let mode: UInt8 = 0x01
}

/// Request payload builders. Model-specific values (e.g. the noise byte) come from the caller.
public enum Request {
    public static let hello: [UInt8] = []
    public static let productID: [UInt8] = []
    public static let firmware: [UInt8] = []
    public static let battery: [UInt8] = []
    public static let wearStatus: [UInt8] = []
    public static let noiseRead: [UInt8] = [NoiseConfigType.mode, 0x01]

    public static func noiseSet(_ value: UInt8) -> [UInt8] { [NoiseConfigType.mode, 0x01, value] }

    /// `<count> <event ids…>`
    public static func subscribe(_ events: [UInt8]) -> [UInt8] { [UInt8(events.count)] + events }

    /// Answer to the buds' `0x0500` time request: `00` + Unix seconds, u32 little-endian.
    public static func timeReply(unixSeconds: UInt32) -> [UInt8] {
        [0x00,
         UInt8(unixSeconds & 0xFF), UInt8((unixSeconds >> 8) & 0xFF),
         UInt8((unixSeconds >> 16) & 0xFF), UInt8(unixSeconds >> 24)]
    }
}
