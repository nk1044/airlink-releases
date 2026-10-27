// Renders the AirLink app icon into an .icns (and optionally a 1024 px PNG).
// Usage: swift scripts/make-icon.swift <output.icns> [preview.png]
//
// macOS icon grid: an 824 pt squircle centred on a 1024 pt canvas, with a soft drop shadow.
// Graphite background, a warm glow with sound-wave rings (noise control), and glossy white buds.
import AppKit

let arguments = Array(CommandLine.arguments.dropFirst())
let output = arguments.first ?? "Packaging/AppIcon.icns"
let preview = arguments.dropFirst().first

func color(_ hex: UInt32, _ alpha: CGFloat = 1) -> NSColor {
    NSColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
}

/// A continuous-corner ("squircle") rounded rect, close to Apple's icon shape.
func squircle(_ rect: NSRect) -> NSBezierPath {
    let r = rect.width * 0.2237
    let path = NSBezierPath()
    let k: CGFloat = 0.55 * 1.28
    let (minX, minY, maxX, maxY) = (rect.minX, rect.minY, rect.maxX, rect.maxY)
    path.move(to: NSPoint(x: minX + r, y: minY))
    path.line(to: NSPoint(x: maxX - r, y: minY))
    path.curve(to: NSPoint(x: maxX, y: minY + r), controlPoint1: NSPoint(x: maxX - r + r * k, y: minY),
               controlPoint2: NSPoint(x: maxX, y: minY + r - r * k))
    path.line(to: NSPoint(x: maxX, y: maxY - r))
    path.curve(to: NSPoint(x: maxX - r, y: maxY), controlPoint1: NSPoint(x: maxX, y: maxY - r + r * k),
               controlPoint2: NSPoint(x: maxX - r + r * k, y: maxY))
    path.line(to: NSPoint(x: minX + r, y: maxY))
    path.curve(to: NSPoint(x: minX, y: maxY - r), controlPoint1: NSPoint(x: minX + r - r * k, y: maxY),
               controlPoint2: NSPoint(x: minX, y: maxY - r + r * k))
    path.line(to: NSPoint(x: minX, y: minY + r))
    path.curve(to: NSPoint(x: minX + r, y: minY), controlPoint1: NSPoint(x: minX, y: minY + r - r * k),
               controlPoint2: NSPoint(x: minX + r - r * k, y: minY))
    path.close()
    return path
}

func render(_ pixels: Int) -> NSBitmapImageRep {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: pixels, pixelsHigh: pixels, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                               bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = NSSize(width: 1024, height: 1024)   // draw in 1024-pt icon coordinates at any pixel size
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    NSGraphicsContext.current?.imageInterpolation = .high

    let tile = NSRect(x: 100, y: 100, width: 824, height: 824)
    let shape = squircle(tile)

    // Drop shadow under the tile.
    NSGraphicsContext.saveGraphicsState()
    let shadow = NSShadow()
    shadow.shadowColor = color(0x000000, 0.35)
    shadow.shadowBlurRadius = 28
    shadow.shadowOffset = NSSize(width: 0, height: -12)
    shadow.set()
    color(0x1B1D24).setFill()
    shape.fill()
    NSGraphicsContext.restoreGraphicsState()

    NSGraphicsContext.saveGraphicsState()
    shape.addClip()

    // Graphite base, lighter at the top.
    NSGradient(colors: [color(0x3A3D49), color(0x1E2028), color(0x111217)],
               atLocations: [0, 0.55, 1], colorSpace: .sRGB)!.draw(in: shape, angle: -90)

    let center = NSPoint(x: tile.midX, y: tile.midY - 18)

    // Warm glow behind the buds.
    NSGradient(colors: [color(0xFFB547, 0.55), color(0xFF7A2F, 0.18), color(0xFF7A2F, 0)],
               atLocations: [0, 0.45, 1], colorSpace: .sRGB)!
        .draw(fromCenter: center, radius: 0, toCenter: center, radius: 400, options: [])

    // Sound-wave rings, fading outwards.
    for (index, radius) in [228.0, 300.0, 372.0].enumerated() {
        let ring = NSBezierPath(ovalIn: NSRect(x: center.x - radius, y: center.y - radius,
                                               width: radius * 2, height: radius * 2))
        ring.lineWidth = 7 - CGFloat(index) * 1.5
        color(0xFFC46B, 0.42 - CGFloat(index) * 0.12).setStroke()
        ring.stroke()
    }

    // Glossy white buds with a soft shadow.
    let config = NSImage.SymbolConfiguration(pointSize: 330, weight: .regular)
        .applying(.init(paletteColors: [.white]))
    if let symbol = NSImage(systemSymbolName: "airpodspro", accessibilityDescription: nil)?.withSymbolConfiguration(config) {
        let size = symbol.size
        let rect = NSRect(x: center.x - size.width / 2, y: center.y - size.height / 2,
                          width: size.width, height: size.height)
        // Shade the glyph top-to-bottom by drawing it as a mask over a gradient.
        let shaded = NSImage(size: size, flipped: false) { bounds in
            symbol.draw(in: bounds)
            NSGradient(colors: [color(0xFFFFFF), color(0xE6E8EE), color(0xC9CDD6)],
                       atLocations: [0, 0.6, 1], colorSpace: .sRGB)!
                .draw(in: bounds, angle: -90)
            symbol.draw(in: bounds, from: .zero, operation: .destinationIn, fraction: 1)
            return true
        }
        NSGraphicsContext.saveGraphicsState()
        let budShadow = NSShadow()
        budShadow.shadowColor = color(0x000000, 0.45)
        budShadow.shadowBlurRadius = 22
        budShadow.shadowOffset = NSSize(width: 0, height: -14)
        budShadow.set()
        shaded.draw(in: rect)
        NSGraphicsContext.restoreGraphicsState()
    }

    // Top gloss and a hairline rim, like the system icons.
    NSGradient(colors: [color(0xFFFFFF, 0.14), color(0xFFFFFF, 0)], atLocations: [0, 1], colorSpace: .sRGB)!
        .draw(in: NSRect(x: tile.minX, y: tile.midY + 120, width: tile.width, height: tile.height / 2 - 120), angle: -90)
    NSGraphicsContext.restoreGraphicsState()

    let rim = squircle(tile.insetBy(dx: 1.5, dy: 1.5))
    rim.lineWidth = 3
    color(0xFFFFFF, 0.12).setStroke()
    rim.stroke()

    NSGraphicsContext.restoreGraphicsState()
    return rep
}

let fm = FileManager.default
let iconset = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("AirLink-\(getpid()).iconset")
try? fm.removeItem(at: iconset)
try fm.createDirectory(at: iconset, withIntermediateDirectories: true)

func png(_ pixels: Int) -> Data { render(pixels).representation(using: .png, properties: [:])! }

for points in [16, 32, 128, 256, 512] {
    try png(points).write(to: iconset.appendingPathComponent("icon_\(points)x\(points).png"))
    try png(points * 2).write(to: iconset.appendingPathComponent("icon_\(points)x\(points)@2x.png"))
}
if let preview { try png(1024).write(to: URL(fileURLWithPath: preview)) }

let iconutil = Process()
iconutil.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
iconutil.arguments = ["-c", "icns", iconset.path, "-o", output]
try iconutil.run()
iconutil.waitUntilExit()
try? fm.removeItem(at: iconset)
exit(iconutil.terminationStatus)
