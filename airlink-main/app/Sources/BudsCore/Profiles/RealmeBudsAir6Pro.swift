import BudsProtocol

/// realme Buds Air6 Pro (product id 063012). Sources and confidence: PROTOCOL.md §7.
public struct RealmeBudsAir6Pro: DeviceProfile {
    public init() {}

    public let id = "realme-buds-air-6-pro"
    public let displayName = "realme Buds Air 6 Pro"
    public let namePrefixes = ["realme Buds Air6 Pro"]
    public let productIDs = ["063012"]
    public let identifyingService: ServiceUUID? = .opoVendor
    /// The "oppointeraction" record lists both the vendor UUID and the standard serial port, so
    /// Gadgetbridge's SPP lookup lands on it too. SPP alone is ambiguous: this model also lists it
    /// on its firmware-update ("TOTA") and "RFCOMM COM" records, so the vendor UUID comes first.
    public let controlServices: [ServiceUUID] = [.opoVendor, .serialPort]

    /// Never answered on firmware 1.1.0.75, and battery, wear and noise pushes arrive without it
    /// (probe, 2026-10-23), so subscribing would only add a 2.5 s timeout to every connect.
    public let subscriptions: [UInt8] = []

    public let noiseModes: [NoiseMode] = [.noiseCancellation, .transparency, .off]

    /// realme writes and reads the same value. `08` is ANC at its strongest level.
    public func noiseValue(for mode: NoiseMode) -> UInt8 {
        switch mode {
        case .off: 0x01
        case .transparency: 0x02
        case .noiseCancellation: 0x08
        }
    }

    /// ANC levels (`04` low, `08` high, `10` medium, `20` adaptive) all read as noise cancellation.
    public func noiseMode(fromValue value: UInt8) -> NoiseMode? {
        switch value {
        case 0x01: .off
        case 0x02: .transparency
        case 0x04, 0x08, 0x10, 0x20: .noiseCancellation
        default: nil
        }
    }

    /// ANC needs both buds in the ear; transparency and off need at least one. Gadgetbridge's rule,
    /// and the buds enforce it themselves with status `0E` (probe, 2026-10-23).
    public func refusal(for mode: NoiseMode, wear: [Component: WearState]) -> String? {
        let inEar = [Component.left, .right].filter { wear[$0] == .inEar }.count
        switch (mode, inEar) {
        case (_, 0): return "Put a bud in your ear"
        case (.noiseCancellation, 1): return "Noise cancellation needs both buds in"
        default: return nil
        }
    }
}
