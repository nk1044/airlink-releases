import AppKit
import BudsCore
import BudsProtocol
import SwiftUI

/// The menu bar icon, in one place: AirPods Pro–style buds, drawn as a template image so it
/// follows the menu bar's appearance. With a battery level it becomes a Batteries-widget ring
/// around the buds.
///
/// To use your own artwork instead, put a template PDF at `Packaging/MenuBarIcon.pdf` and rebuild;
/// `scripts/build.sh` copies it into the app and it takes precedence over the symbol.
enum MenuBarIcon {
    static let systemSymbol = "airpodspro"

    static func image(batteryPercent: Int?) -> NSImage? {
        guard let percent = batteryPercent else { return plain }
        return ring(percent: percent)
    }

    private static var plain: NSImage? {
        let image: NSImage?
        if let custom = customArtwork(height: 18) {
            image = custom
        } else {
            image = NSImage(systemSymbolName: systemSymbol, accessibilityDescription: "AirLink")?
                .withSymbolConfiguration(NSImage.SymbolConfiguration(pointSize: 15, weight: .regular))
        }
        image?.isTemplate = true
        return image
    }

    /// Monochrome (template) like other menu bar icons; at 20 % or below the arc turns orange,
    /// then red at 10 %, the way the system battery icon turns red.
    private static func ring(percent: Int) -> NSImage {
        let isLow = percent <= 20
        let side: CGFloat = 20
        let lineWidth: CGFloat = 2
        let image = NSImage(size: NSSize(width: side, height: side), flipped: false) { rect in
            // Template images only use alpha; colored ones resolve labelColor for the menu bar's
            // current appearance at draw time.
            let ink: NSColor = isLow ? .labelColor : .black
            let arcColor: NSColor = isLow ? NSColor(Theme.batteryColor(percent)) : .black
            let circle = rect.insetBy(dx: lineWidth / 2 + 0.5, dy: lineWidth / 2 + 0.5)

            ink.withAlphaComponent(0.3).setStroke()
            let track = NSBezierPath(ovalIn: circle)
            track.lineWidth = lineWidth
            track.stroke()

            let center = NSPoint(x: circle.midX, y: circle.midY)
            let arc = NSBezierPath()
            arc.appendArc(withCenter: center, radius: circle.width / 2, startAngle: 90,
                          endAngle: 90 - 360 * CGFloat(min(max(percent, 0), 100)) / 100, clockwise: true)
            arc.lineWidth = lineWidth
            arc.lineCapStyle = .round
            arcColor.setStroke()
            arc.stroke()

            let config = NSImage.SymbolConfiguration(pointSize: 8.5, weight: .semibold)
                .applying(.init(paletteColors: [ink]))
            if let glyph = customArtwork(height: 8) ?? NSImage(systemSymbolName: systemSymbol, accessibilityDescription: nil)?
                .withSymbolConfiguration(config) {
                let size = glyph.size
                glyph.draw(in: NSRect(x: center.x - size.width / 2, y: center.y - size.height / 2,
                                      width: size.width, height: size.height))
            }
            return true
        }
        image.isTemplate = !isLow
        image.accessibilityDescription = "AirLink, \(percent) percent"
        return image
    }

    private static func customArtwork(height: CGFloat) -> NSImage? {
        guard let url = Bundle.main.url(forResource: "MenuBarIcon", withExtension: "pdf"),
              let custom = NSImage(contentsOf: url) else { return nil }
        custom.size = NSSize(width: height * custom.size.width / max(custom.size.height, 1), height: height)
        custom.isTemplate = true
        return custom
    }
}

/// Metrics and motion matching the system's menu bar panels (Wi-Fi, Bluetooth, Control Center).
enum Theme {
    static let popoverWidth: CGFloat = 300
    static let margin: CGFloat = 14
    /// Matches the system's menu bar panels on macOS 26+.
    static let panelCornerRadius: CGFloat = 12
    /// Gap between the menu bar and the panel's top edge.
    static let panelGap: CGFloat = 1
    /// Minimum distance from the screen's edge.
    static let screenInset: CGFloat = 6

    /// Critically damped, about 0.3 s.
    static let selectionSpring = Animation.spring(response: 0.3, dampingFraction: 1.0)
    static let crossFade = Animation.easeInOut(duration: 0.2)

    static func batteryColor(_ percent: Int) -> Color {
        switch percent {
        case ...10: .red
        case 11...20: .orange
        default: .green
        }
    }
}

/// Section title, as in Control Center modules.
struct SectionHeader: View {
    let title: String
    init(_ title: String) { self.title = title }

    var body: some View {
        Text(title)
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(.secondary)
            .accessibilityAddTraits(.isHeader)
    }
}

extension NoiseMode {
    var title: String {
        switch self {
        case .noiseCancellation: "Noise Cancellation"
        case .transparency: "Transparency"
        case .off: "Off"
        }
    }

    /// Fits under a 30 pt button in a third of the panel.
    var shortTitle: String {
        switch self {
        case .noiseCancellation: "Noise Cancel"
        case .transparency: "Transparency"
        case .off: "Off"
        }
    }

    var symbol: String {
        switch self {
        case .noiseCancellation: "waveform.slash"
        case .transparency: "ear.and.waveform"
        case .off: "circle.slash"
        }
    }
}

extension Component {
    var title: String {
        switch self {
        case .left: "Left"
        case .right: "Right"
        case .caseBattery: "Case"
        }
    }

    var symbol: String {
        switch self {
        case .left: "airpodpro.left"
        case .right: "airpodpro.right"
        case .caseBattery: "airpodspro.chargingcase.wireless.fill"
        }
    }
}
