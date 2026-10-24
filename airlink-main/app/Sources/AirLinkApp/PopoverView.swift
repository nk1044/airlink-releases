import AppKit
import BudsCore
import SwiftUI

struct PopoverView: View {
    let store: DeviceStore
    let loginItem: LoginItem
    let preferences: Preferences

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            DeviceHeader(store: store)
                .padding(.bottom, 12)

            switch store.connection {
            case .failed(let reason):
                FailureNotice(reason: reason) { store.retry() }
                    .padding(.bottom, 12)
            case .connected, .connecting, .dormant:
                BatterySection(store: store)
                    .padding(.bottom, 14)
                PanelDivider()
                NoiseControlView(store: store)
                    .padding(.vertical, 10)
            }

            PanelDivider()
            SettingsSection(loginItem: loginItem, preferences: preferences)
                .padding(.vertical, 6)
            PanelDivider()
            MenuRow(title: "Quit AirLink", shortcut: "⌘Q") { NSApp.terminate(nil) }
                .keyboardShortcut("q")
                .padding(.top, 5)
                .accessibilityHint("Closes the connection to the buds and quits")
        }
        .padding(.horizontal, Theme.margin)
        .padding(.top, Theme.margin)
        .padding(.bottom, 6)
    }
}

// MARK: - Header

private struct DeviceHeader: View {
    let store: DeviceStore

    var body: some View {
        HStack(spacing: 10) {
            ZStack {
                Circle().fill(isConnected ? Color.accentColor : Color.secondary.opacity(0.25))
                Image(systemName: MenuBarIcon.systemSymbol)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(isConnected ? Color.white : Color.primary)
            }
            .frame(width: 28, height: 28)
            .animation(Theme.crossFade, value: isConnected)

            VStack(alignment: .leading, spacing: 1) {
                Text(store.deviceName)
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(1)
                Text(statusText)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if store.connection == .connecting {
                ProgressView().controlSize(.small)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var isConnected: Bool { store.connection == .connected }

    private var statusText: String {
        switch store.connection {
        case .connected: "Connected"
        case .connecting: "Connecting…"
        case .failed: "Not responding"
        case .dormant: "Not connected"
        }
    }
}

private struct FailureNotice: View {
    let reason: String
    let retry: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            Text(reason)
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            Button("Try Again", action: retry)
                .controlSize(.small)
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Color.primary.opacity(0.06)))
    }
}

// MARK: - Settings and menu rows

private struct SettingsSection: View {
    let loginItem: LoginItem
    @Bindable var preferences: Preferences

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ToggleRow(title: "Launch at Login",
                      isOn: Binding(get: { loginItem.isEnabled }, set: { loginItem.set($0) }))
            if loginItem.needsApproval {
                Text("Allow AirLink in System Settings › General › Login Items.")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .padding(.bottom, 2)
            }
            ToggleRow(title: "Show Battery in Menu Bar", isOn: $preferences.showBatteryInMenuBar)
        }
    }
}

private struct ToggleRow: View {
    let title: String
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            Text(title).font(.system(size: 13))
        }
        .toggleStyle(MenuSwitchStyle())
    }
}

/// Title on the left, mini switch on the right, as in Control Center's menus.
private struct MenuSwitchStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack {
            configuration.label
            Spacer()
            Toggle("", isOn: configuration.$isOn)
                .labelsHidden()
                .toggleStyle(.switch)
                .controlSize(.mini)
        }
        .frame(height: 24)
    }
}

/// A menu-style action with the system's rounded hover highlight.
private struct MenuRow: View {
    let title: String
    var shortcut: String?
    let action: () -> Void

    @State private var isHovered = false

    var body: some View {
        Button(action: action) {
            HStack {
                Text(title).font(.system(size: 13))
                Spacer()
                if let shortcut {
                    Text(shortcut).font(.system(size: 13)).foregroundStyle(.tertiary)
                }
            }
            .padding(.horizontal, 6)
            .frame(height: 24)
            .background(
                RoundedRectangle(cornerRadius: 5, style: .continuous)
                    .fill(isHovered ? Color.primary.opacity(0.1) : .clear)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.horizontal, -6)
        .onHover { isHovered = $0 }
    }
}

struct PanelDivider: View {
    var body: some View {
        Divider().opacity(0.7)
    }
}

// MARK: - Height reporting

extension View {
    /// Reports the view's height. `onGeometryChange` needs macOS 15, so this uses a preference.
    func reportingHeight(_ action: @escaping (CGFloat) -> Void) -> some View {
        background(GeometryReader { proxy in
            Color.clear.preference(key: HeightKey.self, value: proxy.size.height)
        })
        .onPreferenceChange(HeightKey.self) { height in
            MainActor.assumeIsolated { action(height) }
        }
    }
}

private struct HeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}
