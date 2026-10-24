import Testing
@testable import BudsProtocol

/// Frames captured from a realme Buds Air6 Pro (product id 063012, firmware 1.1.0.75) with
/// airlink-probe on 2026-10-23, macOS 27.0.1. See PROTOCOL.md.
@Suite struct CapturedFramesTests {
    private func decode(_ text: String) -> [Frame] {
        var decoder = FrameDecoder()
        return decoder.append(Hex.parse(text)!).compactMap { if case .frame(let f) = $0 { f } else { nil } }
    }

    private func one(_ text: String) -> Frame {
        let frames = decode(text)
        #expect(frames.count == 1)
        return frames[0]
    }

    @Test func helloReplyWithShortInnerLength() {
        // Frame length 0x0E (7 payload bytes) but inner payload length 5: still a valid reply.
        let frame = one("AA 0E 00 00 00 81 01 05 00 00 BF 2F 5A 64 45 00")
        #expect(frame.command == 0x8100)
        #expect(frame.sequence == 0x01)
        #expect(frame.status == 0x00)
        #expect(frame.payload == [0x00, 0xBF, 0x2F, 0x5A, 0x64])
    }

    @Test func helloReplyFollowedByAnotherFrame() {
        let frames = decode("AA 0E 00 00 00 81 01 05 00 00 BF 2F 5A 64 45 00"
                            + " AA 0B 00 00 04 02 16 04 00 03 01 01 02")
        #expect(frames.map(\.command) == [0x8100, 0x0204])
    }

    @Test func productID() {
        #expect(Parse.productID(one("AA 0B 00 00 03 81 03 04 00 00 12 30 06").payload) == "063012")
    }

    @Test func firmware() {
        let frame = one("""
            AA 64 00 00 05 81 04 5D 00 00 09 31 2C 31 2C 31 31 31 2C 31 2C 32 2C 31 2E 31 2E 30 2E 37 35 \
            2C 31 2C 33 2C 31 2E 31 2E 30 2E 37 35 2C 32 2C 31 2C 31 31 31 2C 32 2C 32 2C 31 2E 31 2E 30 \
            2E 37 35 2C 32 2C 33 2C 31 2E 31 2E 30 2E 37 35 2C 33 2C 31 2C 31 31 31 2C 33 2C 32 2C 30 32 \
            36 2C 33 2C 33 2C 30 32 36
            """)
        let text = Parse.firmware(frame.payload)
        #expect(text?.hasPrefix("1,1,111,1,2,1.1.0.75,") == true)
        #expect(text?.hasSuffix("3,3,026") == true)
    }

    @Test func batteryReplyCaseClosed() {
        let frame = one("AA 0F 00 00 06 81 06 08 00 00 03 01 64 02 5A 03 00")
        #expect(Parse.batteryReply(frame.payload) == [
            BatteryReading(component: .left, percent: 100, isCharging: false),
            BatteryReading(component: .right, percent: 90, isCharging: false),
            BatteryReading(component: .caseBattery, percent: nil, isCharging: false),
        ])
    }

    @Test func batteryPushCaseOpen() {
        #expect(Push(one("AA 0F 00 00 04 02 1C 08 00 01 03 01 64 02 5A 03 28")) == .battery([
            BatteryReading(component: .left, percent: 100, isCharging: false),
            BatteryReading(component: .right, percent: 90, isCharging: false),
            BatteryReading(component: .caseBattery, percent: 40, isCharging: false),
        ]))
    }

    @Test func batteryPushWithoutRightBud() {
        // Right bud in the case with the lid closed: only left and case are listed.
        #expect(Push(one("AA 0D 00 00 04 02 1D 06 00 01 02 01 64 03 00")) == .battery([
            BatteryReading(component: .left, percent: 100, isCharging: false),
            BatteryReading(component: .caseBattery, percent: nil, isCharging: false),
        ]))
    }

    @Test func wear() {
        let reply = one("AA 0F 00 00 09 81 05 08 00 00 03 01 03 02 03 03 04")
        #expect(Parse.wearReply(reply.payload)?[.left] == .inEar)
        #expect(Parse.wearReply(reply.payload)?[.right] == .inEar)

        guard case .wear(let pushed)? = Push(one("AA 0F 00 00 04 02 18 08 00 02 03 01 03 02 01 03 04")) else {
            Issue.record("not a wear push"); return
        }
        #expect(pushed[.left] == .inEar)
        #expect(pushed[.right] == .outOfEar)

        guard case .wear(let cased)? = Push(one("AA 0F 00 00 04 02 1B 08 00 02 03 01 03 02 00 03 04")) else {
            Issue.record("not a wear push"); return
        }
        #expect(cased[.right] == .inCase)
    }

    @Test func noiseReadAllModes() {
        #expect(Parse.noiseReply(one("AA 0B 00 00 0C 81 07 04 00 00 01 01 08").payload) == 0x08)
        #expect(Parse.noiseReply(one("AA 0B 00 00 0C 81 06 04 00 00 01 01 02").payload) == 0x02)
        #expect(Parse.noiseReply(one("AA 0B 00 00 0C 81 06 04 00 00 01 01 01").payload) == 0x01)
    }

    @Test func noiseSetRequestsMatchWhatWasSent() {
        for (value, sent) in [(UInt8(0x02), "AA 0A 00 00 04 04 05 03 00 01 01 02"),
                              (0x08, "AA 0A 00 00 04 04 05 03 00 01 01 08"),
                              (0x01, "AA 0A 00 00 04 04 05 03 00 01 01 01")] {
            #expect(Frame(command: Command.noiseSet, sequence: 5, payload: Request.noiseSet(value)).encoded() == Hex.parse(sent))
        }
    }

    @Test func noiseSetAcceptedAndRejected() {
        let accepted = one("AA 08 00 00 04 84 05 01 00 00")
        #expect(accepted.command == 0x8404)
        #expect(accepted.status == 0x00)
        // ANC with one bud in, or transparency with none: refused with 0E.
        #expect(one("AA 08 00 00 04 84 05 01 00 0E").status == 0x0E)
    }

    @Test func noisePushes() {
        #expect(Push(one("AA 0B 00 00 04 02 1A 04 00 03 01 01 02")) == .noiseMode(0x02))
        #expect(Push(one("AA 0B 00 00 04 02 1D 04 00 03 01 01 08")) == .noiseMode(0x08))
        #expect(Push(one("AA 0B 00 00 04 02 20 04 00 03 01 01 01")) == .noiseMode(0x01))
    }

    @Test func requestsMatchWhatWasSent() {
        #expect(Frame(command: Command.hello, sequence: 1).encoded() == Hex.parse("AA 07 00 00 00 01 01 00 00"))
        #expect(Frame(command: Command.battery, sequence: 6).encoded() == Hex.parse("AA 07 00 00 06 01 06 00 00"))
        #expect(Frame(command: Command.noiseRead, sequence: 7, payload: Request.noiseRead).encoded()
                == Hex.parse("AA 09 00 00 0C 01 07 02 00 01 01"))
    }
}
