/// Incremental decoder for the RFCOMM byte stream.
///
/// A read can hold part of a frame or several frames, so bytes are buffered across reads.
/// After anything malformed the decoder drops one byte and resynchronises on the next `AA`.
public struct FrameDecoder: Sendable {
    public enum Event: Sendable, Equatable {
        case frame(Frame)
        /// Bytes that were not part of a valid frame. Reported so the probe can show them.
        case discarded([UInt8])
    }

    /// Anything longer is treated as corruption rather than waited for.
    public static let maxBodyLength = 4096

    private var buffer: [UInt8] = []

    public init() {}

    public var pendingByteCount: Int { buffer.count }

    public mutating func reset() { buffer.removeAll() }

    public mutating func append(_ bytes: [UInt8]) -> [Event] {
        buffer += bytes
        var events: [Event] = []
        while !buffer.isEmpty {
            guard let start = buffer.firstIndex(of: Frame.startByte) else {
                events.append(.discarded(buffer))
                buffer.removeAll()
                break
            }
            if start > 0 {
                events.append(.discarded(Array(buffer[..<start])))
                buffer.removeFirst(start)
            }

            let bodyLength: Int
            let lengthBytes: Int
            switch LEB128.decode(buffer, at: 1) {
            case .needMoreData:
                return events
            case .invalid:
                events.append(dropStartByte())
                continue
            case .value(let value, let count):
                bodyLength = value
                lengthBytes = count
            }
            guard bodyLength >= Frame.headerLength, bodyLength <= Self.maxBodyLength else {
                events.append(dropStartByte())
                continue
            }

            // Validate the header as soon as it is complete, so a stray AA that decodes to a
            // long length cannot stall the stream waiting for bytes that never come.
            let headerEnd = 1 + lengthBytes + Frame.headerLength
            guard buffer.count >= headerEnd else { return events }
            // The frame length is authoritative. The inner payload length may be shorter: the Air 6
            // Pro's hello reply says 5 but carries 7 (`AA 0E … 05 00 00 BF 2F 5A 64 45 00`). A
            // longer one cannot be a real frame, so it marks a stray AA.
            let payloadLength = Int(buffer[headerEnd - 2]) | Int(buffer[headerEnd - 1]) << 8
            guard payloadLength <= bodyLength - Frame.headerLength else {
                events.append(dropStartByte())
                continue
            }

            let total = 1 + lengthBytes + bodyLength
            guard buffer.count >= total else { return events }
            let base = 1 + lengthBytes
            events.append(.frame(Frame(
                command: UInt16(buffer[base + 2]) | UInt16(buffer[base + 3]) << 8,
                sequence: buffer[base + 4],
                payload: Array(buffer[headerEnd..<(headerEnd + payloadLength)]),
                control: buffer[base]
            )))
            buffer.removeFirst(total)
        }
        return events
    }

    private mutating func dropStartByte() -> Event {
        buffer.removeFirst()
        return .discarded([Frame.startByte])
    }
}
