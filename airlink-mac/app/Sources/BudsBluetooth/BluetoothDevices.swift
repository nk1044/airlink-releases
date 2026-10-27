import BudsCore
import Foundation
@preconcurrency import IOBluetooth

/// One SDP service record, reduced to what matters here.
public struct ServiceInfo: Sendable {
    public var name: String?
    public var uuids: [ServiceUUID]
    public var rfcommChannel: UInt8?

    /// Firmware-update services (e.g. "TOTA", "BESOTA"). Never opened.
    public var isFirmwareUpdate: Bool { name?.uppercased().contains("OTA") == true }

    /// The service name realme/OPPO buds give the vendor control service.
    static let vendorControlName = "oppointeraction"

    func provides(_ uuid: ServiceUUID) -> Bool {
        uuids.contains(uuid) || (uuid == .opoVendor && name == Self.vendorControlName)
    }
}

/// Read-only helpers over paired classic Bluetooth devices. Nothing here pages a device:
/// SDP queries are only made on devices that already have a link.
@MainActor
public enum BluetoothDevices {
    public static func paired() -> [IOBluetoothDevice] {
        noteStackTouched()
        return (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? []
    }

    /// When this process first used IOBluetooth.
    private static var stackTouchedAt: ContinuousClock.Instant?
    /// How long the CoreBluetooth shim needs after first use before it accepts channel opens.
    static let stackSettleTime: Duration = .seconds(1)

    private static func noteStackTouched() {
        if stackTouchedAt == nil { stackTouchedAt = .now }
    }

    /// Waits, once per process, until IOBluetooth has been in use for `stackSettleTime`.
    /// Sleeping on the main actor lets the run loop deliver the shim's power-state event.
    /// Returns whether it had to wait.
    @discardableResult
    public static func waitUntilStackReady() async -> Bool {
        noteStackTouched()
        let remaining = stackTouchedAt!.advanced(by: stackSettleTime) - .now
        guard remaining > .zero else { return false }
        try? await Task.sleep(for: remaining)
        return true
    }

    public enum MatchReason: String, Sendable { case name, service }

    /// Matches by Bluetooth name, then by a cached identifying SDP service. Uses cached data only.
    public static func profile(for device: IOBluetoothDevice) -> (profile: any DeviceProfile, reason: MatchReason)? {
        if let name = device.name, let profile = DeviceRegistry.profile(forName: name) {
            return (profile, .name)
        }
        let uuids = Set(cachedServices(device).flatMap(\.uuids))
        for profile in DeviceRegistry.all {
            if let id = profile.identifyingService, uuids.contains(id) { return (profile, .service) }
        }
        return nil
    }

    /// The service records macOS has cached for `device`. May be stale or empty until
    /// `refreshServices` runs.
    public static func cachedServices(_ device: IOBluetoothDevice) -> [ServiceInfo] {
        let records = (device.services as? [IOBluetoothSDPServiceRecord]) ?? []
        return records.map(serviceInfo)
    }

    /// Services whose UUID is checked through IOBluetooth's own matcher as well as by parsing,
    /// in case the attribute parse comes back empty.
    private static let knownServices: [ServiceUUID] = [.serialPort, .opoVendor]

    static func serviceInfo(_ record: IOBluetoothSDPServiceRecord) -> ServiceInfo {
        var uuids: [ServiceUUID] = []
        // 0x0001 ServiceClassIDList: a sequence of UUIDs.
        let list = (record.getAttributeDataElement(0x0001)?.getArrayValue() as? [Any]) ?? []
        for case let element as IOBluetoothSDPDataElement in list {
            if let uuid = element.getUUIDValue(), let parsed = ServiceUUID(bytes: bytes(of: uuid)) {
                uuids.append(parsed)
            }
        }
        for known in knownServices where !uuids.contains(known) {
            let candidates = [IOBluetoothSDPUUID(bytes: known.bytes, length: 16)].compactMap { $0 }
                + (known == .serialPort ? [IOBluetoothSDPUUID(uuid16: 0x1101)].compactMap { $0 } : [])
            if record.hasService(from: candidates) { uuids.append(known) }
        }
        var channel: BluetoothRFCOMMChannelID = 0
        let hasChannel = record.getRFCOMMChannelID(&channel) == kIOReturnSuccess
        return ServiceInfo(name: record.getServiceName(), uuids: uuids, rfcommChannel: hasChannel ? channel : nil)
    }

    /// Reads the UUID through NSData's primitives; bridging the IOBluetoothSDPUUID subclass to
    /// `Data` is not relied on.
    private static func bytes(of uuid: IOBluetoothSDPUUID) -> [UInt8] {
        guard uuid.length > 0 else { return [] }
        return Array(UnsafeRawBufferPointer(start: uuid.bytes, count: uuid.length))
    }

    /// Re-reads the device's SDP records. Required in-process before an RFCOMM open will complete.
    /// Refuses devices without a link, so it never pages buds that are not connected.
    @discardableResult
    public static func refreshServices(_ device: IOBluetoothDevice, timeout: Duration = .seconds(5)) async -> Bool {
        guard device.isConnected() else { return false }
        let query = SDPQuery()
        return await query.run(device, timeout: timeout)
    }
}

/// Bridges `performSDPQuery(_:)`'s `sdpQueryComplete:status:` callback to async.
@MainActor
private final class SDPQuery: NSObject {
    private var continuation: CheckedContinuation<Bool, Never>?

    func run(_ device: IOBluetoothDevice, timeout: Duration) async -> Bool {
        await withCheckedContinuation { continuation in
            self.continuation = continuation
            guard device.performSDPQuery(self) == kIOReturnSuccess else {
                finish(false)
                return
            }
            Task { [weak self] in
                try? await Task.sleep(for: timeout)
                self?.finish(false)
            }
        }
    }

    @objc func sdpQueryComplete(_ device: IOBluetoothDevice!, status: IOReturn) {
        finish(status == kIOReturnSuccess)
    }

    private func finish(_ ok: Bool) {
        continuation?.resume(returning: ok)
        continuation = nil
    }
}
