import AppKit
import BudsCore
import Observation

/// Owns the menu bar item. It exists only while the buds are connected: the item is created
/// when `store.isVisible` turns on and removed (after closing the panel) when it turns off.
/// Driven by observation, so nothing runs while nothing changes.
@MainActor
final class StatusItemController: NSObject {
    private let store: DeviceStore
    private let preferences: Preferences
    private let panel: PanelController
    private var item: NSStatusItem?

    init(store: DeviceStore, loginItem: LoginItem, preferences: Preferences) {
        self.store = store
        self.preferences = preferences
        panel = PanelController(store: store, loginItem: loginItem, preferences: preferences)
        super.init()
    }

    func start() { track() }

    /// Applies the current state, and re-applies after any observed property changes.
    private func track() {
        withObservationTracking {
            apply()
        } onChange: { [weak self] in
            Task { @MainActor in self?.track() }
        }
    }

    private func apply() {
        guard store.isVisible else {
            // Close the panel first, so it never outlives its menu bar item.
            panel.close(reason: "buds disconnected")
            if let item {
                NSStatusBar.system.removeStatusItem(item)
                self.item = nil
            }
            return
        }
        let button = (item ?? install()).button
        let percent = preferences.showBatteryInMenuBar ? store.lowestBudPercent : nil
        button?.image = MenuBarIcon.image(batteryPercent: percent)
        button?.setAccessibilityLabel(percent.map { "AirLink, lowest bud \($0) percent" } ?? "AirLink")
    }

    private func install() -> NSStatusItem {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let button = item.button {
            button.imagePosition = .imageOnly
            button.toolTip = store.deviceName
            button.target = self
            button.action = #selector(buttonPressed(_:))
            // Open on mouse-down, like the system's menu bar items.
            button.sendAction(on: [.leftMouseDown, .rightMouseDown])
        }
        self.item = item
        return item
    }

    @objc private func buttonPressed(_ sender: NSStatusBarButton) {
        panel.toggle(from: sender)
    }
}
