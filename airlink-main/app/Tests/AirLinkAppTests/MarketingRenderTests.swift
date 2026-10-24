import AppKit
import BudsCore
import BudsProtocol
import SwiftUI
import Testing
@testable import AirLinkApp

/// Renders the website's screenshots from the real views, including native controls, by drawing
/// an offscreen window. Off unless AIRLINK_MARKETING_DIR is set:
///     AIRLINK_MARKETING_DIR=../website/assets swift test --filter Marketing
@Suite(.enabled(if: ProcessInfo.processInfo.environment["AIRLINK_MARKETING_DIR"] != nil))
@MainActor
struct MarketingRenderTests {
    let directory = URL(fileURLWithPath: ProcessInfo.processInfo.environment["AIRLINK_MARKETING_DIR"] ?? "/tmp")

    @Test func panels() async throws {
        for dark in [true, false] {
            let store = await connectedStore(battery: [0x01, 0x64, 0x02, 0x5A, 0x03, 0x2E], noise: 0x08)
            try renderPanel(store, name: "panel-\(dark ? "dark" : "light")", dark: dark)
        }
        let refused = await connectedStore(battery: [0x01, 0x52, 0x03, 0x2E], noise: 0x02, wear: [0x01, 0x03, 0x02, 0x00])
        refused.setNoiseMode(.noiseCancellation)
        try renderPanel(refused, name: "panel-refused-dark", dark: true)
    }

    @Test func menuBarIcons() throws {
        for (name, percent) in [("menubar-plain", Int?.none), ("menubar-ring", 80)] {
            guard let icon = MenuBarIcon.image(batteryPercent: percent) else { continue }
            let scale: CGFloat = 4
            let size = NSSize(width: icon.size.width * scale, height: icon.size.height * scale)
            let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width), pixelsHigh: Int(size.height),
                                       bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                       colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
            NSGraphicsContext.saveGraphicsState()
            NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
            let rect = NSRect(origin: .zero, size: size)
            icon.draw(in: rect)
            NSColor.white.set()
            rect.fill(using: .sourceAtop)          // white, for a dark menu bar
            NSGraphicsContext.restoreGraphicsState()
            try write(rep, name)
        }
    }

    // MARK: Helpers

    private func renderPanel(_ store: DeviceStore, name: String, dark: Bool) throws {
        let appearance = NSAppearance(named: dark ? .darkAqua : .aqua)!
        // The system menu material can't be captured offscreen; these are the grays it shows over
        // a neutral desktop, sampled from the system Battery menu.
        let fill = dark ? Color(red: 0.224, green: 0.231, blue: 0.243) : Color(red: 0.925, green: 0.925, blue: 0.93)
        let shape = RoundedRectangle(cornerRadius: Theme.panelCornerRadius, style: .continuous)
        let content = PanelContent(store: store, loginItem: LoginItem(), preferences: Preferences()) { _ in }
            .background(fill)
            .clipShape(shape)
            .environment(\.colorScheme, dark ? .dark : .light)

        let hosting = NSHostingView(rootView: content)
        hosting.appearance = appearance
        let size = hosting.fittingSize
        let window = NSWindow(contentRect: NSRect(origin: NSPoint(x: -4000, y: -4000), size: size),
                              styleMask: .borderless, backing: .buffered, defer: false)
        window.appearance = appearance
        window.isOpaque = false
        window.backgroundColor = .clear
        window.contentView = hosting
        hosting.frame = NSRect(origin: .zero, size: size)
        hosting.layoutSubtreeIfNeeded()
        hosting.displayIfNeeded()

        // 3× so the hero can show the panel larger than life and stay crisp.
        let scale: CGFloat = 3
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width * scale), pixelsHigh: Int(size.height * scale),
                                   bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                   colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        rep.size = size
        hosting.cacheDisplay(in: hosting.bounds, to: rep)
        try write(rep, name)
    }

    private func write(_ rep: NSBitmapImageRep, _ name: String) throws {
        let png = try #require(rep.representation(using: .png, properties: [:]))
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try png.write(to: directory.appendingPathComponent("\(name).png"))
    }

    private func connectedStore(battery: [UInt8], noise: UInt8, wear: [UInt8] = [0x01, 0x03, 0x02, 0x03]) async -> DeviceStore {
        let transport = MarketingTransport { frame in
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
        let store = DeviceStore(defaults: UserDefaults(suiteName: "airlink-marketing-\(UUID().uuidString)")!)
        store.deviceAppeared(profile: RealmeBudsAir6Pro()) {}
        let session = BudsSession(transport: transport, profile: RealmeBudsAir6Pro())
        await session.start()
        await store.attach(session)
        return store
    }
}

@MainActor
private final class MarketingTransport: Transport {
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
