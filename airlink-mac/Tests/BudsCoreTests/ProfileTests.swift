import BudsProtocol
import Testing
@testable import BudsCore

@Suite struct ProfileTests {
    let profile = RealmeBudsAir6Pro()

    @Test func nameMatching() {
        #expect(DeviceRegistry.profile(forName: "realme Buds Air6 Pro")?.id == profile.id)
        #expect(DeviceRegistry.profile(forName: "realme Buds Air 6 Pro")?.id == profile.id)
        #expect(DeviceRegistry.profile(forName: "REALME BUDS AIR6 PRO-A1B2")?.id == profile.id)
        #expect(DeviceRegistry.profile(forName: "realme Buds Air6") == nil)
        #expect(DeviceRegistry.profile(forName: "AirPods Pro") == nil)
        #expect(DeviceRegistry.profile(forProductID: "063012")?.id == profile.id)
    }

    @Test func noiseValuesRoundTrip() {
        for mode in NoiseMode.allCases {
            #expect(profile.noiseMode(fromValue: profile.noiseValue(for: mode)) == mode)
        }
        #expect(profile.noiseMode(fromValue: 0x20) == .noiseCancellation)   // adaptive level
        #expect(profile.noiseMode(fromValue: 0x40) == nil)
    }

    @Test func refusalRules() {
        let both: [Component: WearState] = [.left: .inEar, .right: .inEar]
        let one: [Component: WearState] = [.left: .inEar, .right: .inCase]
        let none: [Component: WearState] = [.left: .outOfEar, .right: .inCase]
        #expect(profile.refusal(for: .noiseCancellation, wear: both) == nil)
        #expect(profile.refusal(for: .noiseCancellation, wear: one) != nil)
        #expect(profile.refusal(for: .transparency, wear: one) == nil)
        #expect(profile.refusal(for: .off, wear: none) == "Put a bud in your ear")
    }

    @Test func serviceUUIDs() {
        #expect(ServiceUUID.serialPort.description == "00001101-0000-1000-8000-00805F9B34FB")
        #expect(ServiceUUID(bytes: [0x11, 0x01]) == .serialPort)
        #expect(ServiceUUID(bytes: [0x00, 0x00, 0x11, 0x01]) == .serialPort)
        #expect(ServiceUUID(bytes: [0x01]) == nil)
        #expect(profile.controlServices.first == .opoVendor)
    }
}
