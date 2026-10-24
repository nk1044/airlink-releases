import AppKit
import BudsCore
import SwiftUI

/// The drop-down panel, styled and placed like the system's menu bar panels (Battery, Wi-Fi):
/// right edge under the item's right edge, 1 pt below the menu bar, menu material, 12 pt
/// continuous corners, a hairline edge and a shadow.
///
/// The SwiftUI view tree exists only while the panel is open; closing releases the panel, its
/// hosting view and the click monitor.
@MainActor
final class PanelController: NSObject, NSWindowDelegate {
    private let store: DeviceStore
    private let loginItem: LoginItem
    private let preferences: Preferences

    private var panel: MenuPanel?
    private weak var button: NSStatusBarButton?
    private var outsideClickMonitor: Any?
    /// Workspace observers that close the panel on app or Space switches; only while open.
    private var workspaceObservers: [NSObjectProtocol] = []
    /// When the panel last closed, so a click on the menu bar item that closed it doesn't reopen it.
    private var closedAt = ContinuousClock.now.advanced(by: .seconds(-10))

    init(store: DeviceStore, loginItem: LoginItem, preferences: Preferences) {
        self.store = store
        self.loginItem = loginItem
        self.preferences = preferences
    }

    func toggle(from button: NSStatusBarButton) {
        if panel != nil {
            close(reason: "menu bar item")
        } else if ContinuousClock.now - closedAt > .milliseconds(250) {
            open(from: button)
        }
    }

    func open(from button: NSStatusBarButton) {
        guard panel == nil, let anchor = Self.screenFrame(of: button) else { return }
        self.button = button

        let content = PanelContent(store: store, loginItem: loginItem, preferences: preferences) { [weak self] height in
            self?.contentHeightChanged(height)
        }
        let hosting = NSHostingView(rootView: content)
        let size = hosting.fittingSize
        let panel = MenuPanel(contentRect: Self.frame(for: size, under: anchor))
        panel.contentView = Self.container(for: hosting, size: size)
        panel.delegate = self
        panel.onCancel = { [weak self] in self?.close(reason: "escape") }
        self.panel = panel

        panel.alphaValue = 0
        panel.makeKeyAndOrderFront(nil)
        panel.invalidateShadow()
        NSAnimationContext.runAnimationGroup { context in
            context.duration = 0.12
            panel.animator().alphaValue = 1
        }

        // Like a menu, it closes on a click in another app, an app switch or a Space switch.
        outsideClickMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown]) { [weak self] _ in
            MainActor.assumeIsolated { self?.close(reason: "click outside") }
        }
        let workspace = NSWorkspace.shared.notificationCenter
        workspaceObservers = [
            (NSWorkspace.didActivateApplicationNotification, "app switch"),
            (NSWorkspace.activeSpaceDidChangeNotification, "space switch"),
        ].map { name, reason in
            workspace.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.close(reason: reason) }
            }
        }
        button.highlight(true)
        BudsLog.ui.info("panel opened")
        store.popoverOpened()
    }

    func close(reason: String = "closed") {
        guard let panel else { return }
        self.panel = nil
        closedAt = .now
        BudsLog.ui.info("panel closed: \(reason, privacy: .public)")
        if let outsideClickMonitor { NSEvent.removeMonitor(outsideClickMonitor) }
        outsideClickMonitor = nil
        workspaceObservers.forEach { NSWorkspace.shared.notificationCenter.removeObserver($0) }
        workspaceObservers = []
        button?.highlight(false)
        panel.delegate = nil
        panel.orderOut(nil)
        panel.contentView = nil
        DispatchQueue.main.async { MemoryTrim.now() }
    }

    // MARK: NSWindowDelegate

    func windowDidResignKey(_ notification: Notification) {
        close(reason: "lost focus")
    }

    // MARK: Layout

    /// Keeps the top edge fixed while the content grows or shrinks (e.g. a refusal message).
    private func contentHeightChanged(_ height: CGFloat) {
        guard let panel, height > 0, abs(panel.frame.height - height) > 0.5 else { return }
        var frame = panel.frame
        let top = frame.maxY
        frame.size.height = height
        frame.origin.y = top - height
        panel.setFrame(frame, display: true)
        panel.invalidateShadow()
    }

    /// The menu-material background with the hosting view filling it.
    static func container(for hosting: NSView, size: NSSize) -> NSView {
        // Sized before the hosting view is added: autoresizing would otherwise grow the hosting view
        // by the whole panel size when the background is resized from zero.
        let background = NSVisualEffectView(frame: NSRect(origin: .zero, size: size))
        background.material = .menu
        background.blendingMode = .behindWindow
        background.state = .active
        background.maskImage = roundedMask(radius: Theme.panelCornerRadius)
        if let hosting = hosting as? NSHostingView<PanelContent> {
            // The controller sizes the panel; the hosting view must not resize the window itself.
            hosting.sizingOptions = []
        }
        hosting.frame = background.bounds
        hosting.autoresizingMask = [.width, .height]
        background.addSubview(hosting)
        return background
    }

    private static func screenFrame(of button: NSStatusBarButton) -> NSRect? {
        guard let window = button.window else { return nil }
        return window.convertToScreen(button.convert(button.bounds, to: nil))
    }

    private static func frame(for size: NSSize, under anchor: NSRect) -> NSRect {
        let screen = NSScreen.screens.first { $0.frame.contains(NSPoint(x: anchor.midX, y: anchor.midY)) }
            ?? NSScreen.main
        let visible = screen?.visibleFrame ?? .infinite
        var x = anchor.maxX - size.width
        x = min(max(x, visible.minX + Theme.screenInset), visible.maxX - size.width - Theme.screenInset)
        let top = min(anchor.minY, visible.maxY) - Theme.panelGap
        return NSRect(x: x, y: top - size.height, width: size.width, height: size.height)
    }

    /// A stretchable rounded-rect mask; `maskImage` is how a behind-window material gets corners.
    private static func roundedMask(radius: CGFloat) -> NSImage {
        let edge = radius * 2 + 1
        let image = NSImage(size: NSSize(width: edge, height: edge), flipped: false) { rect in
            NSColor.black.setFill()
            NSBezierPath(roundedRect: rect, xRadius: radius, yRadius: radius).fill()
            return true
        }
        image.capInsets = NSEdgeInsets(top: radius, left: radius, bottom: radius, right: radius)
        image.resizingMode = .stretch
        return image
    }
}

/// Borderless, non-activating panel at menu level that can still take keys (Esc, ⌘Q).
final class MenuPanel: NSPanel {
    var onCancel: (() -> Void)?

    init(contentRect: NSRect) {
        super.init(contentRect: contentRect, styleMask: [.borderless, .nonactivatingPanel],
                   backing: .buffered, defer: false)
        isOpaque = false
        backgroundColor = .clear
        hasShadow = true
        level = .popUpMenu
        isReleasedWhenClosed = false
        hidesOnDeactivate = false
        isMovable = false
        // Belongs to the Space it opened on (it closes on a Space switch); `.canJoinAllSpaces` made
        // it reappear on the new Space for a moment before closing.
        collectionBehavior = [.fullScreenAuxiliary, .transient, .ignoresCycle]
        animationBehavior = .none
    }

    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }

    override func cancelOperation(_ sender: Any?) {
        onCancel?()
    }
}

/// The SwiftUI side of the panel: content, hairline edge and height reporting.
struct PanelContent: View {
    let store: DeviceStore
    let loginItem: LoginItem
    let preferences: Preferences
    let heightChanged: (CGFloat) -> Void

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        PopoverView(store: store, loginItem: loginItem, preferences: preferences)
            .frame(width: Theme.popoverWidth)
            .fixedSize(horizontal: false, vertical: true)
            .reportingHeight(heightChanged)
            .overlay {
                RoundedRectangle(cornerRadius: Theme.panelCornerRadius, style: .continuous)
                    .strokeBorder(colorScheme == .dark ? Color.white.opacity(0.17) : Color.black.opacity(0.12),
                                  lineWidth: 1)
            }
    }
}
