import AppKit
import BudsBluetooth
import BudsCore

/// Agent app entry point. AppKit owns the status item and the panel (so the panel can be placed
/// and styled like the system's menu bar panels); SwiftUI draws the panel's content.
@main
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    /// `NSApplication.delegate` is weak.
    private static var shared: AppDelegate?

    let store = DeviceStore()
    let loginItem = LoginItem()
    let preferences = Preferences()
    private lazy var monitor = ConnectionMonitor(store: store)
    private lazy var statusItem = StatusItemController(store: store, loginItem: loginItem, preferences: preferences)

    static func main() {
        let app = NSApplication.shared
        let delegate = AppDelegate()
        shared = delegate
        app.delegate = delegate
        app.setActivationPolicy(.accessory)
        app.run()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        loginItem.enableOnFirstLaunch()
        statusItem.start()
        monitor.start()
        // Launch leaves a few MB of freed heap pages dirty; hand them back once things settle.
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { MemoryTrim.now() }
    }

    func applicationWillTerminate(_ notification: Notification) {
        monitor.stop()
    }
}
