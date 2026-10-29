import AppKit
import BudsBluetooth
import BudsCore
import BudsProtocol
import Foundation
@preconcurrency import IOBluetooth

@MainActor
final class Probe {
    private let options: ProbeOptions
    private var transport: RFCOMMTransport?
    private var session: BudsSession?

    init(options: ProbeOptions) {
        self.options = options
    }

    func run() async -> Int32 {
        if case .list = options.action { return await list() }

        guard let (device, profile) = pickDevice() else { return 1 }
        guard await connect(device, profile) else { return 1 }
        defer { Out.line("") }

        let ok: Bool
        switch options.action {
        case .list: ok = true
        case .info: ok = await info(profile)
        case .battery: ok = await battery()
        case .noiseGet: ok = await noiseGet(profile)
        case .noiseSet(let mode): ok = await noiseSet(mode, profile)
        case .monitor(let seconds):
            Out.note("listening for \(Int(seconds)) s: long-press a bud, take one out, open the case…")
            await listen(seconds)
            ok = true
        }
        if case .monitor = options.action {} else if options.listenSeconds > 0 {
            Out.note("listening \(options.listenSeconds) s for pushes")
            await listen(options.listenSeconds)
        }
        await disconnect()
        return ok ? 0 : 1
    }

    func interrupted() {
        Out.note("interrupted; closing the channel")
        transport?.close()
        exit(130)
    }

    // MARK: list

    private func list() async -> Int32 {
        let paired = BluetoothDevices.paired()
        if paired.isEmpty {
            Out.line("No paired Bluetooth devices (or Bluetooth permission was denied for this terminal).")
            return 1
        }
        Out.line("Paired devices:")
        for device in paired {
            let match = BluetoothDevices.profile(for: device)
            let state = device.isConnected() ? "connected" : "not connected"
            let matched = match.map { "  → \($0.profile.displayName) (by \($0.reason.rawValue))" } ?? ""
            Out.line("• \(device.name ?? "?")  [\(device.addressString ?? "?")]  \(state)\(matched)")
            guard device.isConnected() else { continue }
            let refreshed = await BluetoothDevices.refreshServices(device)
            if !refreshed { Out.line("    (SDP query did not complete; showing cached records)") }
            for service in BluetoothDevices.cachedServices(device) {
                let channel = service.rfcommChannel.map { "RFCOMM ch \($0)" } ?? "no RFCOMM"
                let uuids = service.uuids.map { Self.label($0) }.joined(separator: ", ")
                let note = service.isFirmwareUpdate ? "  (firmware update: never opened)" : ""
                Out.line("    - \(service.name ?? "(unnamed)")  \(channel)  [\(uuids)]\(note)")
            }
        }
        return 0
    }

    private static func label(_ uuid: ServiceUUID) -> String {
        switch uuid {
        case .serialPort: return "1101 SerialPort"
        case .opoVendor: return "079A OPO vendor"
        default:
            let text = uuid.description
            return text.hasSuffix("-0000-1000-8000-00805F9B34FB") && text.hasPrefix("0000") ? String(text.dropFirst(4).prefix(4)) : text
        }
    }

    // MARK: connect

    private func pickDevice() -> (IOBluetoothDevice, any DeviceProfile)? {
        let connected = BluetoothDevices.paired().filter { $0.isConnected() }
        let device: IOBluetoothDevice?
        if let wanted = options.device?.lowercased() {
            device = connected.first {
                $0.addressString?.lowercased() == wanted || ($0.name ?? "").lowercased().contains(wanted)
            }
        } else {
            device = connected.first { BluetoothDevices.profile(for: $0) != nil }
        }
        guard let device else {
            Out.line("No matching connected device. Connected right now:")
            for d in connected { Out.line("  • \(d.name ?? "?")  [\(d.addressString ?? "?")]") }
            if connected.isEmpty { Out.line("  (none; connect the buds to this Mac for audio first)") }
            return nil
        }
        let match = BluetoothDevices.profile(for: device)
        let profile = match?.profile ?? RealmeBudsAir6Pro()
        Out.line("Device:  \(device.name ?? "?")  [\(device.addressString ?? "?")]")
        Out.line("Profile: \(profile.displayName)" + (match.map { " (matched by \($0.reason.rawValue))" } ?? " (forced, no match)"))
        return (device, profile)
    }

    private func connect(_ device: IOBluetoothDevice, _ profile: any DeviceProfile) async -> Bool {
        if let app = NSRunningApplication.runningApplications(withBundleIdentifier: "io.github.nk1044.airlink").first {
            Out.line("warning: AirLink.app is running (pid \(app.processIdentifier)). If it holds the channel, this will fail.")
        }
        let services = options.services(for: profile)
        if let channel = options.channel {
            Out.note("opening RFCOMM channel \(channel) (forced)")
        } else {
            Out.note("SDP query, then opening the first of: " + services.map(Self.label).joined(separator: ", "))
        }
        do {
            let transport = try await RFCOMMTransport.open(device: device, services: services, channelOverride: options.channel,
                                                           log: { Out.note($0) })
            self.transport = transport
            Out.note("channel \(transport.channelID) open" + (transport.serviceName.map { " (service \"\($0)\")" } ?? ""))
        } catch {
            Out.line("error: \(error)")
            return false
        }

        let session = BudsSession(transport: transport!, profile: options.hello ? profile : NoHello(profile),
                                  trace: { direction, item in Out.trace(direction, item) })
        self.session = session
        Task { [weak self] in
            for await push in session.pushes { self?.describe(push, profile) }
        }
        let handshake = await session.start()
        switch handshake.helloAnswered {
        case true?: Out.note("hello answered")
        case false?: Out.note("hello: no reply (not fatal)")
        case nil: Out.note("hello skipped")
        }
        switch handshake.subscribed {
        case true?: Out.note("subscribed to events")
        case false?: Out.note("subscribe: no reply / rejected")
        case nil: Out.note("subscribe skipped (profile relies on unsolicited pushes)")
        }
        return true
    }

    private func disconnect() async {
        await session?.close()
        Out.note("channel closed")
    }

    private func listen(_ seconds: Double) async {
        try? await Task.sleep(for: .seconds(seconds))
    }

    // MARK: commands

    private func info(_ profile: any DeviceProfile) async -> Bool {
        guard let session else { return false }
        var ok = true
        do {
            let id = try await session.productID()
            let known = profile.productIDs.contains(id) ? "matches \(profile.displayName)" : "NOT in profile \(profile.productIDs)"
            Out.result("product id: \(id) (\(known))")
        } catch { Out.result("product id: \(error)"); ok = false }
        do { Out.result("firmware: \(try await session.firmware())") } catch { Out.result("firmware: \(error)") }
        do { Out.result("wear: \(Self.describe(try await session.wear()))") } catch { Out.result("wear: \(error)") }
        ok = await battery() && ok
        ok = await noiseGet(profile) && ok
        return ok
    }

    private func battery() async -> Bool {
        guard let session else { return false }
        do {
            Out.result("battery: \(Self.describe(try await session.battery()))")
            return true
        } catch {
            Out.result("battery: \(error)")
            return false
        }
    }

    private func noiseGet(_ profile: any DeviceProfile) async -> Bool {
        guard let session else { return false }
        do {
            let value = try await session.noiseValue()
            Out.result("noise mode: \(Self.describe(value, profile))")
            return true
        } catch {
            Out.result("noise mode: \(error)")
            return false
        }
    }

    private func noiseSet(_ argument: String, _ profile: any DeviceProfile) async -> Bool {
        guard let session else { return false }
        let value: UInt8
        let mode: NoiseMode?
        switch argument.lowercased() {
        case "off": mode = .off
        case "transparency": mode = .transparency
        case "anc", "nc", "on": mode = .noiseCancellation
        default: mode = nil
        }
        if let mode {
            value = profile.noiseValue(for: mode)
        } else if let raw = UInt8(argument.lowercased().replacingOccurrences(of: "0x", with: ""), radix: 16) {
            value = raw
        } else {
            Out.line("error: unknown mode \(argument); use off, transparency, anc or a hex byte like 0x08")
            return false
        }

        if let wear = try? await session.wear() {
            Out.result("wear: \(Self.describe(wear))")
            if let mode, let reason = profile.refusal(for: mode, wear: wear) {
                Out.note("the app would refuse this (\(reason)); the probe sends it anyway to see what the buds do")
            }
        }
        _ = await noiseGet(profile)

        Out.note("setting noise control to 0x\(Hex.byte(value))")
        var ok = true
        do {
            try await session.setNoise(value)
            Out.result("set: ACCEPTED")
        } catch SessionError.rejected(_, let status) {
            Out.result("set: REJECTED with status 0x\(Hex.byte(status)) (\(profile.rejectionMessage(status: status)))")
            ok = false
        } catch {
            Out.result("set: \(error)")
            ok = false
        }
        Out.note("waiting 1.5 s for a push, then reading back")
        await listen(1.5)
        return await noiseGet(profile) && ok
    }

    // MARK: describing

    private func describe(_ push: Push, _ profile: any DeviceProfile) {
        switch push {
        case .battery(let readings): Out.push("battery: \(Self.describe(readings))")
        case .wear(let wear): Out.push("wear: \(Self.describe(wear))")
        case .noiseMode(let value): Out.push("noise mode: \(Self.describe(value, profile))")
        case .other(let event, let body): Out.push("event 0x\(Hex.byte(event)): \(Hex.string(body))")
        }
    }

    private static func describe(_ readings: [BatteryReading]) -> String {
        let byComponent = Dictionary(readings.map { ($0.component, $0) }, uniquingKeysWith: { $1 })
        return [(Component.left, "L"), (.right, "R"), (.caseBattery, "case")].map { component, name in
            guard let reading = byComponent[component] else { return "\(name) (absent)" }
            let level = reading.percent.map { "\($0)%" } ?? "—"
            return "\(name) \(level)\(reading.isCharging ? " ⚡︎" : "")"
        }.joined(separator: " · ")
    }

    private static func describe(_ wear: [Component: WearState]) -> String {
        [(Component.left, "L"), (.right, "R")].map { component, name in
            let state: String
            switch wear[component] {
            case .inEar?: state = "in ear"
            case .outOfEar?: state = "out"
            case .inCase?: state = "in case"
            case .unknown(let raw)?: state = "0x\(Hex.byte(raw))"
            case nil: state = "absent"
            }
            return "\(name) \(state)"
        }.joined(separator: " · ")
    }

    private static func describe(_ value: UInt8, _ profile: any DeviceProfile) -> String {
        let name: String
        switch profile.noiseMode(fromValue: value) {
        case .noiseCancellation?: name = value == profile.noiseValue(for: .noiseCancellation) ? "ANC" : "ANC (level 0x\(Hex.byte(value)))"
        case .transparency?: name = "Transparency"
        case .off?: name = "Off"
        case nil: name = "UNKNOWN"
        }
        return "\(name) [0x\(Hex.byte(value))]"
    }
}

/// Wraps a profile to skip the hello, for `--no-hello`.
private struct NoHello: DeviceProfile {
    let base: any DeviceProfile
    init(_ base: any DeviceProfile) { self.base = base }
    var id: String { base.id }
    var displayName: String { base.displayName }
    var namePrefixes: [String] { base.namePrefixes }
    var productIDs: [String] { base.productIDs }
    var identifyingService: ServiceUUID? { base.identifyingService }
    var controlServices: [ServiceUUID] { base.controlServices }
    var sendsHello: Bool { false }
    var subscriptions: [UInt8] { base.subscriptions }
    var noiseModes: [NoiseMode] { base.noiseModes }
    func noiseValue(for mode: NoiseMode) -> UInt8 { base.noiseValue(for: mode) }
    func noiseMode(fromValue value: UInt8) -> NoiseMode? { base.noiseMode(fromValue: value) }
}

/// Timestamped terminal output.
enum Out {
    private static let start = ContinuousClock.now

    private static func stamp() -> String {
        let elapsed = ContinuousClock.now - start
        let ms = elapsed.components.seconds * 1000 + elapsed.components.attoseconds / 1_000_000_000_000_000
        return "[" + String(format: "%7.3f", Double(ms) / 1000) + "]"
    }

    static func line(_ text: String) { print(text) }
    static func note(_ text: String) { print("\(stamp())   · \(text)") }
    static func result(_ text: String) { print("\(stamp())   = \(text)") }
    static func push(_ text: String) { print("\(stamp())   ⇠ push \(text)") }

    static func trace(_ direction: TraceDirection, _ item: TraceItem) {
        let arrow = direction == .outgoing ? "→" : "←"
        switch item {
        case .frame(let frame, let raw):
            let label = Command.name(frame.command).padding(toLength: 20, withPad: " ", startingAt: 0)
            print("\(stamp()) \(arrow) \(label) seq \(Hex.byte(frame.sequence))  \(Hex.string(raw))")
        case .discarded(let bytes):
            print("\(stamp()) \(arrow) (not a frame)              \(Hex.string(bytes))")
        }
    }
}
