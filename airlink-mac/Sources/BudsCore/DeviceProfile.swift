import BudsProtocol

/// The three noise-control modes the UI offers.
public enum NoiseMode: String, Sendable, CaseIterable, Identifiable {
    case noiseCancellation, transparency, off
    public var id: String { rawValue }
}

/// A 128-bit Bluetooth service UUID.
public struct ServiceUUID: Hashable, Sendable, CustomStringConvertible {
    public let bytes: [UInt8]

    /// `"0000079A-D102-11E1-9B23-00025B00A5A5"`
    public init(_ string: String) {
        let parsed = Hex.parse(string.replacingDashes()) ?? []
        precondition(parsed.count == 16, "invalid UUID \(string)")
        bytes = parsed
    }

    /// Expands 16- and 32-bit SIG UUIDs onto the Bluetooth base UUID; 16 bytes pass through.
    public init?(bytes raw: [UInt8]) {
        switch raw.count {
        case 16: bytes = raw
        case 2, 4: bytes = Array(repeating: 0, count: 4 - raw.count) + raw + Self.baseSuffix
        default: return nil
        }
    }

    private static let baseSuffix: [UInt8] = [0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0x80, 0x5F, 0x9B, 0x34, 0xFB]

    public var description: String {
        let h = bytes.map(Hex.byte)
        return [h[0..<4], h[4..<6], h[6..<8], h[8..<10], h[10..<16]].map { $0.joined() }.joined(separator: "-")
    }

    /// Standard Serial Port Profile.
    public static let serialPort = ServiceUUID("00001101-0000-1000-8000-00805F9B34FB")
    /// OPPO/realme vendor control service ("oppointeraction").
    public static let opoVendor = ServiceUUID("0000079A-D102-11E1-9B23-00025B00A5A5")
}

private extension String {
    func replacingDashes() -> String { String(filter { $0 != "-" }) }
}

/// Everything model-specific. Adding a model means adding one type conforming to this,
/// and listing it in `DeviceRegistry.all`.
public protocol DeviceProfile: Sendable {
    var id: String { get }
    var displayName: String { get }

    /// Bluetooth name prefixes. Compared case-insensitively with spaces removed, so
    /// "realme Buds Air6 Pro" also matches "realme Buds Air 6 Pro".
    var namePrefixes: [String] { get }
    /// `0x0103` product ids (`"063012"`), used to confirm a match after connecting.
    var productIDs: [String] { get }
    /// A vendor-specific SDP service that identifies the model family when the name was changed.
    var identifyingService: ServiceUUID? { get }
    /// RFCOMM services that carry the control protocol, most preferred first.
    var controlServices: [ServiceUUID] { get }

    /// Whether to send `0x0100` before other commands. A missing reply is never fatal.
    var sendsHello: Bool { get }
    /// Event ids to subscribe to with `0x0205`. Empty skips the subscription.
    var subscriptions: [UInt8] { get }

    var noiseModes: [NoiseMode] { get }
    func noiseValue(for mode: NoiseMode) -> UInt8
    func noiseMode(fromValue value: UInt8) -> NoiseMode?

    var batteryComponents: [Component] { get }

    /// A reason the buds would refuse `mode` given the wear state, or `nil` if it should be sent.
    func refusal(for mode: NoiseMode, wear: [Component: WearState]) -> String?
    /// User-facing text for a non-zero status in a `0x8404` reply.
    func rejectionMessage(status: UInt8) -> String
}

extension DeviceProfile {
    public var sendsHello: Bool { true }
    public var subscriptions: [UInt8] { [Event.battery, Event.wearStatus, Event.noiseMode] }
    public var batteryComponents: [Component] { [.left, .right, .caseBattery] }

    public func refusal(for mode: NoiseMode, wear: [Component: WearState]) -> String? { nil }
    public func rejectionMessage(status: UInt8) -> String { "Put a bud in your ear" }

    public func matches(name: String) -> Bool {
        let normalized = DeviceRegistry.normalize(name)
        return namePrefixes.contains { normalized.hasPrefix(DeviceRegistry.normalize($0)) }
    }
}

public enum DeviceRegistry {
    public static let all: [any DeviceProfile] = [RealmeBudsAir6Pro()]

    public static func profile(forName name: String) -> (any DeviceProfile)? {
        all.first { $0.matches(name: name) }
    }

    public static func profile(forProductID productID: String) -> (any DeviceProfile)? {
        all.first { $0.productIDs.contains(productID.uppercased()) }
    }

    static func normalize(_ name: String) -> String {
        String(name.lowercased().filter { !$0.isWhitespace })
    }
}
