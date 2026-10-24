import AppKit
import BudsCore
import BudsProtocol
import SwiftUI
import Testing
@testable import AirLinkApp

/// Renders the popover in a few states to PNGs, for eyeballing layout without hardware.
/// Off unless AIRLINK_SNAPSHOT_DIR is set:
///     AIRLINK_SNAPSHOT_DIR=/tmp/snaps swift test --filter Snapshot
@Suite(.enabled(if: ProcessInfo.processInfo.environment["AIRLINK_SNAPSHOT_DIR"] != nil))
@MainActor
struct SnapshotTests {
    let directory = URL(fileURLWithPath: ProcessInfo.processInfo.environment["AIRLINK_SNAPSHOT_DIR"] ?? "/tmp")

    @Test func connected() async throws {
        let store = await connectedStore(battery: [0x01, 0x64, 0x02, 0x92, 0x03, 0x12], noise: 0x08)
        try render(store, "connected")
    }

    @Test func lowBatteryAndCaseClosed() async throws {
        let store = await connectedStore(battery: [0x01, 0x09, 0x02, 0x10, 0x03, 0x00], noise: 0x02)
        try render(store, "low-battery")
    }

    @Test func refusal() async throws {
        let store = await connectedStore(battery: [0x01, 0x64, 0x03, 0x28], noise: 0x01,
                                         wear: [0x01, 0x03, 0x02, 0x00])
        store.setNoiseMode(.noiseCancellation)
        try render(store, "refused")
    }

    @Test func connecting() throws {
        let store = DeviceStore(defaults: UserDefaults(suiteName: "airlink-snapshots-\(UUID().uuidString)")!)
        store.deviceAppeared(profile: RealmeBudsAir6Pro()) {}
        try render(store, "connecting")
    }

    @Test func failed() throws {
        let store = DeviceStore(defaults: UserDefaults(suiteName: "airlink-snapshots-\(UUID().uuidString)")!)
        store.deviceAppeared(profile: RealmeBudsAir6Pro()) {}
        store.failed("Couldn't reach the buds.")
        try render(store, "failed")
    }

    private func connectedStore(battery: [UInt8], noise: UInt8, wear: [UInt8] = [0x01, 0x03, 0x02, 0x03]) async -> DeviceStore {
        let transport = ScriptedTransport { frame in
            func reply(_ payload: [UInt8]) -> [UInt8] {
                Frame(command: Command.reply(to: frame.command), sequence: frame.sequence, payload: payload).encoded()
            }
            switch frame.command {
            case Command.battery: return reply([0x00, UInt8(battery.count / 2)] + battery)
            case Command.wearStatus: return reply([0x00, UInt8(wear.count / 2)] + wear)
            case Command.noiseRead: return reply([0x00, 0x01, 0x01, noise])
            default: return reply([0x00])
            }
        }
        let store = DeviceStore(defaults: UserDefaults(suiteName: "airlink-snapshots-\(UUID().uuidString)")!)
        store.deviceAppeared(profile: RealmeBudsAir6Pro()) {}
        let session = BudsSession(transport: transport, profile: RealmeBudsAir6Pro())
        await session.start()
        await store.attach(session)
        return store
    }

    private func render(_ store: DeviceStore, _ name: String) throws {
        try render(store, name, scheme: .dark)
        try render(store, name, scheme: .light)
    }

    /// The live panel sits on the system menu material, which ImageRenderer can't draw; the gray
    /// sampled from the system Battery menu stands in for it.
    private func render(_ store: DeviceStore, _ name: String, scheme: ColorScheme) throws {
        let backdrop = scheme == .dark ? Color(red: 0.224, green: 0.231, blue: 0.243) : Color(white: 0.93)
        let shape = RoundedRectangle(cornerRadius: Theme.panelCornerRadius, style: .continuous)
        let view = PanelContent(store: store, loginItem: LoginItem(), preferences: Preferences()) { _ in }
            .background(backdrop)
            .clipShape(shape)
            .padding(20)
            .background(scheme == .dark ? Color(red: 0.08, green: 0.12, blue: 0.2) : Color(red: 0.75, green: 0.8, blue: 0.88))
            .environment(\.colorScheme, scheme)
        let renderer = ImageRenderer(content: view)
        renderer.scale = 2
        let image = try #require(renderer.nsImage)
        let tiff = try #require(image.tiffRepresentation)
        let rep = try #require(NSBitmapImageRep(data: tiff))
        let png = try #require(rep.representation(using: .png, properties: [:]))
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try png.write(to: directory.appendingPathComponent("\(name)-\(scheme == .dark ? "dark" : "light").png"))
    }
}

@MainActor
private final class ScriptedTransport: Transport {
    let events: AsyncStream<TransportEvent>
    private let continuation: AsyncStream<TransportEvent>.Continuation
    private let answer: (Frame) -> [UInt8]

    init(answer: @escaping (Frame) -> [UInt8]) {
        (events, continuation) = AsyncStream.makeStream()
        self.answer = answer
    }

    func send(_ bytes: [UInt8]) throws {
        var decoder = FrameDecoder()
        for case .frame(let frame) in decoder.append(bytes) {
            continuation.yield(.data(answer(frame)))
        }
    }

    func close() { continuation.finish() }
}

/// Renders the menu bar icon at several battery levels on light and dark menu bars, at 4×.
@Suite(.enabled(if: ProcessInfo.processInfo.environment["AIRLINK_SNAPSHOT_DIR"] != nil))
@MainActor
struct MenuBarIconSnapshotTests {
    @Test func icons() throws {
        let levels: [Int?] = [nil, 100, 55, 18, 8]
        let cell: CGFloat = 30, scale: CGFloat = 4
        let canvas = NSImage(size: NSSize(width: cell * CGFloat(levels.count) * scale, height: cell * 2 * scale))
        canvas.lockFocus()
        for (row, dark) in [false, true].enumerated() {
            let appearance = NSAppearance(named: dark ? .darkAqua : .aqua)!
            appearance.performAsCurrentDrawingAppearance {
                (dark ? NSColor(white: 0.12, alpha: 1) : NSColor(white: 0.93, alpha: 1)).setFill()
                NSRect(x: 0, y: CGFloat(1 - row) * cell * scale, width: canvas.size.width, height: cell * scale).fill()
                for (column, level) in levels.enumerated() {
                    guard let icon = MenuBarIcon.image(batteryPercent: level) else { continue }
                    let size = NSSize(width: icon.size.width * scale, height: icon.size.height * scale)
                    let origin = NSPoint(x: CGFloat(column) * cell * scale + (cell * scale - size.width) / 2,
                                         y: CGFloat(1 - row) * cell * scale + (cell * scale - size.height) / 2)
                    let tinted = NSImage(size: size, flipped: false) { rect in
                        icon.draw(in: rect)
                        if icon.isTemplate {
                            (dark ? NSColor.white : NSColor.black).set()
                            rect.fill(using: .sourceAtop)
                        }
                        return true
                    }
                    tinted.draw(in: NSRect(origin: origin, size: size))
                }
            }
        }
        canvas.unlockFocus()
        let dir = URL(fileURLWithPath: ProcessInfo.processInfo.environment["AIRLINK_SNAPSHOT_DIR"]!)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let tiff = try #require(canvas.tiffRepresentation)
        let rep = try #require(NSBitmapImageRep(data: tiff))
        let png = try #require(rep.representation(using: .png, properties: [:]))
        try png.write(to: dir.appendingPathComponent("menubar-icons.png"))
    }
}
