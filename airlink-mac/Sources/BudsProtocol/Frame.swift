/// One OPO control-protocol frame, as spoken by realme / OPPO / OnePlus buds over RFCOMM.
///
///     AA | length (LEB128) | control | 00 | command (u16 LE) | sequence | payloadLength (u16 LE) | payload
///
/// `length` counts every byte after itself: `7 + payload.count`. There is no checksum.
/// See PROTOCOL.md §2.
public struct Frame: Sendable, Equatable {
    public static let startByte: UInt8 = 0xAA
    /// Header bytes between the length field and the payload.
    static let headerLength = 7

    public var command: UInt16
    public var sequence: UInt8
    public var payload: [UInt8]
    /// `00` when sending. Buds have been seen to send `00`, `04` or `08` here.
    public var control: UInt8

    public init(command: UInt16, sequence: UInt8, payload: [UInt8] = [], control: UInt8 = 0) {
        precondition(payload.count <= Int(UInt16.max), "payload too long")
        self.command = command
        self.sequence = sequence
        self.payload = payload
        self.control = control
    }

    /// Replies carry the request's command with bit 15 set (`0x0106` → `0x8106`).
    public var isReply: Bool { command & Command.replyBit != 0 }

    /// Replies start their payload with a status byte; `00` means OK.
    public var status: UInt8? { isReply ? payload.first : nil }

    public func encoded() -> [UInt8] {
        let count = payload.count
        var out: [UInt8] = [Self.startByte]
        out += LEB128.encode(Self.headerLength + count)
        out += [control, 0x00,
                UInt8(command & 0xFF), UInt8(command >> 8),
                sequence,
                UInt8(count & 0xFF), UInt8(count >> 8)]
        out += payload
        return out
    }
}

/// Unsigned little-endian base-128 varint, used for the frame length.
public enum LEB128 {
    public enum Decoded: Equatable {
        case value(Int, byteCount: Int)
        case needMoreData
        case invalid
    }

    public static func encode(_ value: Int) -> [UInt8] {
        precondition(value >= 0)
        var rest = value
        var out: [UInt8] = []
        repeat {
            var byte = UInt8(rest & 0x7F)
            rest >>= 7
            if rest != 0 { byte |= 0x80 }
            out.append(byte)
        } while rest != 0
        return out
    }

    /// Decodes at most `maxBytes` bytes of `bytes` starting at `index`.
    public static func decode(_ bytes: [UInt8], at index: Int, maxBytes: Int = 3) -> Decoded {
        var value = 0
        for offset in 0..<maxBytes {
            guard index + offset < bytes.count else { return .needMoreData }
            let byte = bytes[index + offset]
            value |= Int(byte & 0x7F) << (7 * offset)
            if byte & 0x80 == 0 { return .value(value, byteCount: offset + 1) }
        }
        return .invalid
    }
}
