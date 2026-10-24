import Foundation
import Observation

@MainActor
@Observable
final class Preferences {
    private static let showBatteryKey = "ShowBatteryInMenuBar"

    /// Show the lower bud's battery next to the menu bar icon. Off by default.
    var showBatteryInMenuBar: Bool {
        didSet { UserDefaults.standard.set(showBatteryInMenuBar, forKey: Self.showBatteryKey) }
    }

    init() {
        showBatteryInMenuBar = UserDefaults.standard.bool(forKey: Self.showBatteryKey)
    }
}
