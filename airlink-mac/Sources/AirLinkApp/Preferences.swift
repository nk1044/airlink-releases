import Foundation
import Observation

@MainActor
@Observable
final class Preferences {
    private static let showBatteryKey = "ShowBatteryInMenuBar"
    private static let highQualityAudioKey = "KeepHighQualityAudio"

    /// Show the lower bud's battery next to the menu bar icon. Off by default.
    var showBatteryInMenuBar: Bool {
        didSet { UserDefaults.standard.set(showBatteryInMenuBar, forKey: Self.showBatteryKey) }
    }

    /// When the buds connect and macOS makes their microphone the input, switch the input to the
    /// Mac's own microphone so the buds stay in stereo (A2DP) instead of hands-free. On by default.
    var keepHighQualityAudio: Bool {
        didSet { UserDefaults.standard.set(keepHighQualityAudio, forKey: Self.highQualityAudioKey) }
    }

    init() {
        UserDefaults.standard.register(defaults: [Self.highQualityAudioKey: true])
        showBatteryInMenuBar = UserDefaults.standard.bool(forKey: Self.showBatteryKey)
        keepHighQualityAudio = UserDefaults.standard.bool(forKey: Self.highQualityAudioKey)
    }
}
