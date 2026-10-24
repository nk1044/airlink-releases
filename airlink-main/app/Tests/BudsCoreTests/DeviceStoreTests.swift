import BudsProtocol
import Foundation
import Testing
@testable import BudsCore

/// Simulated Air 6 Pro answering with the layouts captured from real buds.
@MainActor
private final class FakeBuds {
    let transport = MockTransport()
    var noise: UInt8 = 0x08
    var wear: [UInt8] = [0x01, 0x03, 0x02, 0x03]           // both in ear
    var battery: [UInt8] = [0x01, 0x64, 0x02, 0x5A, 0x03, 0x00]
    var noiseSetStatus: UInt8? = 0x00                        // nil: never answer

    init() {
        transport.respond = { [unowned self] frame in
            func reply(_ payload: [UInt8]) -> [Frame] {
                [Frame(command: Command.reply(to: frame.command), sequence: frame.sequence, payload: payload)]
            }
            switch frame.command {
            case Command.hello: return reply([0x00, 0xBF, 0x2F, 0x5A, 0x64])
            case Command.battery: return reply([0x00, UInt8(battery.count / 2)] + battery)
            case Command.wearStatus: return reply([0x00, UInt8(wear.count / 2)] + wear)
            case Command.noiseRead: return reply([0x00, 0x01, 0x01, noise])
            case Command.noiseSet:
                guard let status = noiseSetStatus else { return [] }
                if status == 0x00 { noise = frame.payload[2] }
                return reply([status]) + (status == 0x00 ? [Self.push([0x03, 0x01, 0x01, noise])] : [])
            default: return []
            }
        }
    }

    static func push(_ payload: [UInt8]) -> Frame {
        Frame(command: Command.eventPush, sequence: 0x20, payload: payload)
    }

    func send(push payload: [UInt8]) { transport.deliver(Self.push(payload).encoded()) }

    func connect(_ store: DeviceStore, timeout: Duration = .milliseconds(2500)) async {
        store.deviceAppeared(profile: RealmeBudsAir6Pro()) {}
        let session = BudsSession(transport: transport, profile: RealmeBudsAir6Pro(), requestTimeout: timeout)
        await session.start()
        await store.attach(session)
    }
}

/// A throwaway defaults domain, so the remembered case level never leaks between tests.
private func freshDefaults() -> UserDefaults {
    let name = "airlink-tests-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: name)!
    defaults.removePersistentDomain(forName: name)
    return defaults
}

/// Lets queued main-actor work (push handling, command completions) run.
@MainActor
private func settle() async {
    for _ in 0..<20 { await Task.yield() }
    try? await Task.sleep(for: .milliseconds(20))
}

@Suite @MainActor struct DeviceStoreTests {
    @Test func lifecycle() async {
        let store = DeviceStore(defaults: freshDefaults())
        #expect(!store.isVisible)

        store.deviceAppeared(profile: RealmeBudsAir6Pro()) {}
        #expect(store.isVisible)
        #expect(store.connection == .connecting)
        #expect(store.deviceName == "realme Buds Air 6 Pro")

        let buds = FakeBuds()
        let session = BudsSession(transport: buds.transport, profile: RealmeBudsAir6Pro())
        await session.start()
        await store.attach(session)
        #expect(store.connection == .connected)
        #expect(store.battery[.left]?.percent == 100)
        #expect(store.battery[.right]?.percent == 90)
        #expect(store.battery[.caseBattery] == nil)
        #expect(store.lowestBudPercent == 90)
        #expect(store.wear[.left] == .inEar)
        #expect(store.noiseMode == .noiseCancellation)

        store.deviceGone()
        #expect(!store.isVisible)
        #expect(store.battery[.left] == nil)
        #expect(store.noiseMode == nil)
    }

    @Test func noiseChangeSettlesOnConfirmation() async {
        let store = DeviceStore(defaults: freshDefaults())
        let buds = FakeBuds()
        await buds.connect(store)

        store.setNoiseMode(.transparency)
        #expect(store.pendingMode == .transparency)
        #expect(store.displayedMode == .transparency)
        #expect(store.noiseMode == .noiseCancellation)

        await settle()
        #expect(store.pendingMode == nil)
        #expect(store.noiseMode == .transparency)
        #expect(store.message == nil)
        #expect(buds.transport.sent.last?.payload == [0x01, 0x01, 0x02])
    }

    @Test func rejectionSpringsBack() async {
        let store = DeviceStore(defaults: freshDefaults())
        let buds = FakeBuds()
        buds.noiseSetStatus = 0x0E
        await buds.connect(store)

        store.setNoiseMode(.off)
        #expect(store.displayedMode == .off)
        await settle()
        #expect(store.pendingMode == nil)
        #expect(store.displayedMode == .noiseCancellation)
        #expect(store.message == "Put a bud in your ear")
    }

    @Test func timeoutSpringsBack() async {
        let store = DeviceStore(defaults: freshDefaults())
        let buds = FakeBuds()
        buds.noiseSetStatus = nil
        await buds.connect(store, timeout: .milliseconds(50))

        store.setNoiseMode(.off)
        try? await Task.sleep(for: .milliseconds(150))
        await settle()
        #expect(store.displayedMode == .noiseCancellation)
        #expect(store.message == "The buds didn't respond")
    }

    @Test func refusedLocallyWithOneBud() async {
        let store = DeviceStore(defaults: freshDefaults())
        let buds = FakeBuds()
        buds.noise = 0x01
        buds.wear = [0x01, 0x03, 0x02, 0x00]   // right bud in the case
        await buds.connect(store)
        let sentBefore = buds.transport.sent.count

        store.setNoiseMode(.noiseCancellation)
        #expect(store.pendingMode == nil)
        #expect(store.message == "Noise cancellation needs both buds in")
        #expect(buds.transport.sent.count == sentBefore)

        // Transparency is allowed with one bud.
        store.setNoiseMode(.transparency)
        #expect(store.pendingMode == .transparency)
        #expect(store.message == nil)
        await settle()
    }

    @Test func pushesUpdateState() async {
        let store = DeviceStore(defaults: freshDefaults())
        let buds = FakeBuds()
        await buds.connect(store)

        buds.send(push: [0x03, 0x01, 0x01, 0x01])                     // long-press to Off
        buds.send(push: [0x02, 0x02, 0x01, 0x03, 0x02, 0x00])         // right bud into the case
        buds.send(push: [0x01, 0x02, 0x01, 0x64, 0x03, 0x00])         // right bud no longer reported
        await settle()

        #expect(store.noiseMode == .off)
        #expect(store.wear[.right] == .inCase)
        #expect(store.battery[.right] == nil)
        #expect(store.battery[.left]?.percent == 100)
        #expect(store.lowestBudPercent == 100)
    }

    @Test func caseKeepsLastReportedLevel() async {
        let defaults = freshDefaults()
        let store = DeviceStore(defaults: defaults)
        let buds = FakeBuds()
        buds.battery = [0x01, 0x64, 0x02, 0x5A, 0x03, 0x00]       // lid closed: case unknown
        await buds.connect(store)
        #expect(store.battery[.caseBattery] == nil)               // never seen: "—"

        buds.send(push: [0x01, 0x03, 0x01, 0x64, 0x02, 0x5A, 0x03, 0x28])   // lid open: 40 %
        await settle()
        #expect(store.battery[.caseBattery]?.percent == 40)

        buds.send(push: [0x01, 0x03, 0x01, 0x64, 0x02, 0x5A, 0x03, 0x00])   // lid closed again
        buds.send(push: [0x01, 0x02, 0x01, 0x64, 0x03, 0x00])               // right bud gone too
        await settle()
        #expect(store.battery[.caseBattery]?.percent == 40)
        #expect(store.battery[.right] == nil)

        // Survives the buds going away and a new store (app relaunch).
        store.deviceGone()
        let relaunched = DeviceStore(defaults: defaults)
        relaunched.deviceAppeared(profile: RealmeBudsAir6Pro()) {}
        #expect(relaunched.battery[.caseBattery]?.percent == 40)
    }

    @Test func channelLossShowsFailure() async {
        let store = DeviceStore(defaults: freshDefaults())
        let buds = FakeBuds()
        var retried = false
        store.deviceAppeared(profile: RealmeBudsAir6Pro()) { retried = true }
        let session = BudsSession(transport: buds.transport, profile: RealmeBudsAir6Pro())
        await session.start()
        await store.attach(session)

        buds.transport.close()
        await settle()
        #expect(store.connection == .failed("Lost the connection to the buds"))
        #expect(store.isVisible)

        store.popoverOpened()
        #expect(!retried)
        store.retry()
        #expect(retried)
        #expect(store.connection == .connecting)
    }
}
