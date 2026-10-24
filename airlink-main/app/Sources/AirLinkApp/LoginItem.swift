import Foundation
import Observation
import ServiceManagement

/// "Launch at login" through `SMAppService.mainApp`. On by default: registered on first launch,
/// after which only the toggle changes it.
@MainActor
@Observable
final class LoginItem {
    private static let configuredKey = "LoginItemConfigured"

    private(set) var isEnabled = false
    /// macOS registered it but wants approval in System Settings → General → Login Items.
    private(set) var needsApproval = false

    init() { refresh() }

    func enableOnFirstLaunch() {
        guard !UserDefaults.standard.bool(forKey: Self.configuredKey) else { return }
        UserDefaults.standard.set(true, forKey: Self.configuredKey)
        set(true)
    }

    func set(_ enabled: Bool) {
        do {
            if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        } catch {
            // The toggle reflects the real status below either way.
        }
        refresh()
    }

    private func refresh() {
        let status = SMAppService.mainApp.status
        isEnabled = status == .enabled || status == .requiresApproval
        needsApproval = status == .requiresApproval
    }
}

