import BudsCore
import SwiftUI

/// Noise control as Control Center–style round buttons. The accent selection follows
/// `displayedMode`: it slides to a tapped mode at once, shimmers until the buds confirm, and
/// springs back if they refuse or time out. Changes made on the buds arrive as pushes and move it
/// the same way.
struct NoiseControlView: View {
    let store: DeviceStore

    @Namespace private var selectionSpace
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var isEnabled: Bool { store.connection == .connected }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader("Noise Control")

            HStack(spacing: 0) {
                ForEach(store.profile?.noiseModes ?? [], id: \.self) { mode in
                    NoiseOption(mode: mode,
                                isSelected: store.displayedMode == mode,
                                isPending: store.pendingMode == mode,
                                namespace: selectionSpace,
                                reduceMotion: reduceMotion) {
                        store.setNoiseMode(mode)
                    }
                }
            }
            .disabled(!isEnabled)
            .opacity(isEnabled ? 1 : 0.5)
            .animation(reduceMotion ? Theme.crossFade : Theme.selectionSpring, value: store.displayedMode)

            if let message = store.message {
                Label {
                    Text(message)
                } icon: {
                    Image(systemName: "exclamationmark.circle.fill").foregroundStyle(.orange)
                }
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .center)
                .transition(.opacity)
            }
        }
        .animation(Theme.crossFade, value: store.message)
    }
}

private struct NoiseOption: View {
    let mode: NoiseMode
    let isSelected: Bool
    let isPending: Bool
    let namespace: Namespace.ID
    let reduceMotion: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                ZStack {
                    Circle().fill(Color.primary.opacity(0.08))
                    if isSelected { selection }
                    Image(systemName: mode.symbol)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(isSelected ? Color.white : Color.primary)
                }
                .frame(width: 32, height: 32)

                Text(mode.shortTitle)
                    .font(.system(size: 11))
                    .foregroundStyle(isSelected ? .primary : .secondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(mode.title)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityValue(isPending ? "Waiting for the buds" : "")
    }

    @ViewBuilder
    private var selection: some View {
        let fill = Circle()
            .fill(Color.accentColor)
            .overlay {
                if isPending {
                    if reduceMotion {
                        Circle().fill(Color.white.opacity(0.25))
                    } else {
                        Shimmer().clipShape(Circle())
                    }
                }
            }
        if reduceMotion {
            fill.transition(.opacity)
        } else {
            fill.matchedGeometryEffect(id: "selection", in: namespace)
        }
    }
}

/// A soft highlight sweeping across the selection. It exists only while a change is pending (at
/// most the 2.5 s command timeout), so nothing animates at rest.
private struct Shimmer: View {
    @State private var phase: CGFloat = -1

    var body: some View {
        GeometryReader { proxy in
            LinearGradient(colors: [.clear, Color.white.opacity(0.45), .clear],
                           startPoint: .leading, endPoint: .trailing)
                .frame(width: proxy.size.width * 0.7)
                .offset(x: phase * proxy.size.width)
        }
        .onAppear {
            withAnimation(.linear(duration: 0.9).repeatForever(autoreverses: false)) { phase = 1.3 }
        }
        .allowsHitTesting(false)
    }
}
