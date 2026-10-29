import Testing
@testable import BudsProtocol

private func hex(_ s: String) -> [UInt8] { Hex.parse(s)! }

@Suite struct ParserTests {
    @Test func batteryReply() {
        // L 85 %, R 87 % charging, case reported as 0 (lid closed / unknown).
        let readings = Parse.batteryReply(hex("00 03 01 55 02 D7 03 00"))
        #expect(readings == [
            BatteryReading(component: .left, percent: 85, isCharging: false),
            BatteryReading(component: .right, percent: 87, isCharging: true),
            BatteryReading(component: .caseBattery, percent: nil, isCharging: false),
        ])
    }

    @Test func batteryReplyWithoutCase() {
        let readings = Parse.batteryReply(hex("00 02 01 64 02 63"))
        #expect(readings?.map(\.component) == [.left, .right])
        #expect(readings?.map(\.percent) == [100, 99])
    }

    @Test func batterySkipsUnknownComponents() {
        let readings = Parse.batteryReply(hex("00 02 FF 00 03 E4"))
        #expect(readings == [BatteryReading(component: .caseBattery, percent: 100, isCharging: true)])
    }

    @Test func batteryRejectsBadInput() {
        #expect(Parse.batteryReply(hex("01 03 01 55")) == nil)      // non-zero status
        #expect(Parse.batteryReply(hex("00 03 01 55")) == nil)      // truncated
        #expect(Parse.batteryReply([]) == nil)
    }

    @Test func noiseReplyBothLayouts() {
        #expect(Parse.noiseReply(hex("00 01 01 02")) == 0x02)
        #expect(Parse.noiseReply(hex("00 01 08")) == 0x08)
        #expect(Parse.noiseReply(hex("00 02 01 03")) == nil)        // touch-cycle type, not mode
        #expect(Parse.noiseReply(hex("01 01 01 02")) == nil)        // rejected
    }

    @Test func wearReply() {
        let wear = Parse.wearReply(hex("00 02 01 03 02 01"))
        #expect(wear == [.left: .inEar, .right: .outOfEar])
    }

    @Test func productID() {
        #expect(Parse.productID(hex("00 12 30 06")) == "063012")
        #expect(Parse.productID(hex("00 12 30")) == nil)
    }

    @Test func firmware() {
        #expect(Parse.firmware(hex("00 03") + Array("1,2,1.1.0.66".utf8)) == "1,2,1.1.0.66")
    }

    @Test func pushes() {
        func push(_ payload: String) -> Push? {
            Push(Frame(command: Command.eventPush, sequence: 0xFF, payload: hex(payload)))
        }
        #expect(push("01 03 01 50 02 51 03 E2") == .battery([
            BatteryReading(component: .left, percent: 80, isCharging: false),
            BatteryReading(component: .right, percent: 81, isCharging: false),
            BatteryReading(component: .caseBattery, percent: 98, isCharging: true),
        ]))
        #expect(push("02 02 01 03 02 00") == .wear([.left: .inEar, .right: .inCase]))
        #expect(push("03 01 01 02") == .noiseMode(0x02))
        #expect(push("F1 01 01 02 08") == .other(event: 0xF1, body: hex("01 01 02 08")))
        #expect(Push(Frame(command: Command.battery, sequence: 1)) == nil)
    }

    @Test func rejectionStatus() {
        let ack = Frame(command: 0x8404, sequence: 3, payload: [0x0E])
        #expect(ack.isReply)
        #expect(ack.status == 0x0E)
        #expect(Frame(command: 0x0204, sequence: 0xFF, payload: [0x01]).status == nil)
    }

    @Test func hexParsing() {
        #expect(Hex.parse("AA 07 00") == [0xAA, 0x07, 0x00])
        #expect(Hex.parse("aa0700") == [0xAA, 0x07, 0x00])
        #expect(Hex.parse("0xAA,0x07") == [0xAA, 0x07])
        #expect(Hex.parse("A") == nil)
        #expect(Hex.string([0xAA, 0x0F]) == "AA 0F")
        #expect(Hex.word(0x8106) == "0x8106")
    }
}
