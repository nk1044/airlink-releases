import BudsCore
import BudsProtocol
import SwiftUI

/// Batteries-widget style gauges: the part's glyph inside a ring, the level underneath.
struct BatterySection: View {
    let store: DeviceStore

    var body: some View {
        HStack(spacing: 0) {
            ForEach(store.profile?.batteryComponents ?? [], id: \.self) { component in
                BatteryGauge(component: component, reading: store.battery[component])
            }
        }
    }
}

private struct BatteryGauge: View {
    let component: Component
    let reading: BatteryReading?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var percent: Int? { reading?.percent }
    private var isCharging: Bool { reading?.isCharging == true && percent != nil }

    var body: some View {
        VStack(spacing: 6) {
            ZStack {
                Circle()
                    .stroke(Color.primary.opacity(0.1), lineWidth: 3.5)
                if let percent {
                    Circle()
                        .trim(from: 0, to: CGFloat(percent) / 100)
                        .stroke(Theme.batteryColor(percent), style: StrokeStyle(lineWidth: 3.5, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                }
                Image(systemName: component.symbol)
                    // Single-bud glyphs are narrow, so they get a larger size than the case.
                    .font(.system(size: component == .caseBattery ? 15 : 19, weight: .regular))
                    .foregroundStyle(percent == nil ? Color.secondary : Color.primary)
            }
            .frame(width: 42, height: 42)

            HStack(spacing: 2) {
                if isCharging {
                    Image(systemName: "bolt.fill")
                        .font(.system(size: 8, weight: .bold))
                        .foregroundStyle(.green)
                }
                Text(percent.map { "\($0)%" } ?? "—")
                    .font(.system(size: 11, weight: .medium).monospacedDigit())
                    .foregroundStyle(percent == nil ? .secondary : .primary)
            }
        }
        .frame(maxWidth: .infinity)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.35), value: percent)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
    }

    private var accessibilityText: String {
        let name = component == .caseBattery ? "Case" : "\(component.title) bud"
        guard let percent else { return "\(name), battery not reported" }
        return "\(name), \(percent) percent" + (isCharging ? ", charging" : "")
    }
}
