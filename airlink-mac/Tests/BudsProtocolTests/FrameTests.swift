import Testing
@testable import BudsProtocol

private func hex(_ s: String) -> [UInt8] { Hex.parse(s)! }

@Suite struct FrameEncodingTests {
    @Test func batteryRequest() {
        #expect(Frame(command: Command.battery, sequence: 1).encoded() == hex("AA 07 00 00 06 01 01 00 00"))
    }

    @Test func noiseSetANC() {
        let frame = Frame(command: Command.noiseSet, sequence: 5, payload: Request.noiseSet(0x08))
        #expect(frame.encoded() == hex("AA 0A 00 00 04 04 05 03 00 01 01 08"))
    }

    @Test func subscribe() {
        let frame = Frame(command: Command.subscribe, sequence: 2, payload: Request.subscribe([1, 2, 3]))
        #expect(frame.encoded() == hex("AA 0B 00 00 05 02 02 04 00 03 01 02 03"))
    }

    @Test func longPayloadUsesTwoByteLength() {
        let frame = Frame(command: 0x0105, sequence: 1, payload: Array(repeating: 0x41, count: 124))
        let bytes = frame.encoded()
        #expect(Array(bytes.prefix(3)) == [0xAA, 0x83, 0x01])
        #expect(bytes.count == 1 + 2 + 7 + 124)
    }

    @Test func timeReply() {
        // 0x69A050B5: the "auth token" other projects hard-code is just a time reply.
        #expect(Request.timeReply(unixSeconds: 0x69A0_50B5) == hex("00 B5 50 A0 69"))
    }

    @Test func leb128RoundTrip() {
        for value in [0, 7, 127, 128, 131, 300, 4096] {
            let encoded = LEB128.encode(value)
            #expect(LEB128.decode(encoded, at: 0) == .value(value, byteCount: encoded.count))
        }
        #expect(LEB128.decode([0x83], at: 0) == .needMoreData)
        #expect(LEB128.decode([0xFF, 0xFF, 0xFF], at: 0) == .invalid)
    }
}

@Suite struct FrameDecoderTests {
    let batteryReply = hex("AA 0F 00 00 06 81 01 08 00 00 03 01 55 02 D7 03 00")

    private func frames(_ events: [FrameDecoder.Event]) -> [Frame] {
        events.compactMap { if case .frame(let f) = $0 { f } else { nil } }
    }

    @Test func wholeFrame() {
        var decoder = FrameDecoder()
        let out = frames(decoder.append(batteryReply))
        #expect(out.count == 1)
        #expect(out[0].command == 0x8106)
        #expect(out[0].sequence == 1)
        #expect(out[0].payload == hex("00 03 01 55 02 D7 03 00"))
        #expect(out[0].status == 0x00)
        #expect(decoder.pendingByteCount == 0)
    }

    @Test func splitAcrossReads() {
        var decoder = FrameDecoder()
        for cut in 1..<batteryReply.count {
            #expect(frames(decoder.append(Array(batteryReply[..<cut]))).isEmpty)
            #expect(frames(decoder.append(Array(batteryReply[cut...]))).count == 1)
        }
    }

    @Test func byteByByte() {
        var decoder = FrameDecoder()
        var out: [Frame] = []
        for byte in batteryReply { out += frames(decoder.append([byte])) }
        #expect(out.count == 1)
    }

    @Test func mergedFrames() {
        var decoder = FrameDecoder()
        let push = hex("AA 0B 00 00 04 02 FF 04 00 03 01 01 02")
        let out = frames(decoder.append(batteryReply + push))
        #expect(out.map(\.command) == [0x8106, 0x0204])
    }

    @Test func garbageBeforeFrameIsReported() {
        var decoder = FrameDecoder()
        let events = decoder.append(hex("01 02") + batteryReply)
        #expect(events.first == .discarded([0x01, 0x02]))
        #expect(frames(events).count == 1)
    }

    @Test func resyncAfterStrayStartByte() {
        // A stray AA whose "header" has a payload length that disagrees with its frame length.
        var decoder = FrameDecoder()
        let out = frames(decoder.append(hex("AA 09 11 22 33 44 55 66 77") + batteryReply))
        #expect(out.count == 1)
        #expect(out[0].command == 0x8106)
    }

    @Test func realmeControlByte() {
        // realme buds may send 04 or 08 in the control byte.
        var decoder = FrameDecoder()
        let out = frames(decoder.append(hex("AA 0B 04 00 04 02 FF 04 00 03 01 01 08")))
        #expect(out.first?.control == 0x04)
        #expect(out.first.flatMap(Push.init) == .noiseMode(0x08))
    }

    @Test func roundTrip() {
        var decoder = FrameDecoder()
        let frame = Frame(command: Command.noiseRead, sequence: 9, payload: Request.noiseRead)
        #expect(frames(decoder.append(frame.encoded())) == [frame])
    }
}
