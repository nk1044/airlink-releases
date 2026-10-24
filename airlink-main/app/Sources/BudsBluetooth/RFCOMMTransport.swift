import BudsCore
import Foundation
@preconcurrency import IOBluetooth

public enum RFCOMMError: Error, CustomStringConvertible {
    case notConnected
    case busy(holder: String)
    case serviceNotFound([ServiceInfo])
    case firmwareUpdateChannel(String)
    case openFailed(IOReturn)
    case openTimedOut
    case notOpen
    case writeFailed(IOReturn)

    public var description: String {
        switch self {
        case .notConnected:
            "the buds are not connected to this Mac (connect them for audio first)"
        case .busy(let holder):
            "the control channel is already held by \(holder). Only one process can use it; quit that one first"
        case .serviceNotFound(let services):
            "no control service in SDP records (\(services.count) records seen; run `airlink-probe list`)"
        case .firmwareUpdateChannel(let name):
            "refusing to open \"\(name)\": it is the firmware-update channel"
        case .openFailed(let status):
            "opening the RFCOMM channel failed (IOReturn \(Self.hex(status))). Another app may be holding it"
        case .openTimedOut:
            "opening the RFCOMM channel timed out. Another app may hold it, or macOS's Bluetooth layer is stuck (a new process clears it)"
        case .notOpen:
            "the channel is not open"
        case .writeFailed(let status):
            "write failed (IOReturn \(Self.hex(status)))"
        }
    }

    private static func hex(_ status: IOReturn) -> String { "0x" + String(UInt32(bitPattern: status), radix: 16) }
}

/// Classic Bluetooth RFCOMM transport over IOBluetooth.
///
/// IOBluetooth delivers channel callbacks on the main run loop, so this class is main-actor bound.
/// It holds the cross-process `ChannelLock` for as long as the channel is open.
@MainActor
public final class RFCOMMTransport: NSObject, Transport {
    public let events: AsyncStream<TransportEvent>
    private let continuation: AsyncStream<TransportEvent>.Continuation

    public let channelID: UInt8
    public let serviceName: String?

    private var channel: IOBluetoothRFCOMMChannel?
    private var lock: ChannelLock?
    private var openContinuation: CheckedContinuation<Void, any Error>?
    private var finished = false

    private init(channelID: UInt8, serviceName: String?, lock: ChannelLock) {
        (events, continuation) = AsyncStream.makeStream(bufferingPolicy: .unbounded)
        self.channelID = channelID
        self.serviceName = serviceName
        self.lock = lock
    }

    /// Receives one line per connection step, for diagnostics.
    public typealias StepLog = @MainActor (String) -> Void

    /// Opens the control channel on a device that is already connected.
    ///
    /// - Parameters:
    ///   - services: SDP service UUIDs to look for, most preferred first.
    ///   - channelOverride: use this RFCOMM channel number instead of looking it up.
    ///   - timeout: how long to wait for the open. There is deliberately no retry: closing a pending
    ///     open leaves bluetoothd reporting "Already connected" to every later open from this
    ///     process (seen on macOS 27), so a stalled open is reported, not retried.
    public static func open(device: IOBluetoothDevice, services: [ServiceUUID], channelOverride: UInt8? = nil,
                            timeout: Duration = .seconds(15),
                            log: StepLog? = nil) async throws -> RFCOMMTransport {
        guard device.isConnected() else { throw RFCOMMError.notConnected }
        let lock: ChannelLock
        switch ChannelLock.acquire() {
        case .success(let acquired): lock = acquired
        case .failure(let busy): throw RFCOMMError.busy(holder: busy.holder)
        }

        // On macOS 27 IOBluetooth is a shim over CoreBluetooth, which drops an open sent before
        // it has seen the "powered on" state ("API MISUSE … powered on state" in the log).
        if await BluetoothDevices.waitUntilStackReady() {
            log?("waited for the Bluetooth stack to settle")
        }

        // The open never completes unless this process has fetched the SDP records itself.
        let sdpOK = await BluetoothDevices.refreshServices(device)
        log?("SDP query " + (sdpOK ? "completed" : "did not complete; using cached records"))

        let records = BluetoothDevices.cachedServices(device)
        let channelID: UInt8
        let serviceName: String?
        if let channelOverride {
            if let ota = records.first(where: { $0.rfcommChannel == channelOverride && $0.isFirmwareUpdate }) {
                lock.release()
                throw RFCOMMError.firmwareUpdateChannel(ota.name ?? "channel \(channelOverride)")
            }
            channelID = channelOverride
            serviceName = records.first { $0.rfcommChannel == channelOverride }?.name
        } else {
            let found = services.lazy.compactMap { uuid in
                records.first { $0.provides(uuid) && $0.rfcommChannel != nil && !$0.isFirmwareUpdate }
            }.first
            guard let found, let id = found.rfcommChannel else {
                lock.release()
                throw RFCOMMError.serviceNotFound(records)
            }
            channelID = id
            serviceName = found.name
        }

        let transport = RFCOMMTransport(channelID: channelID, serviceName: serviceName, lock: lock)
        log?("opening RFCOMM channel \(channelID)" + (serviceName.map { " (\"\($0)\")" } ?? ""))
        do {
            try await transport.openChannel(on: device, timeout: timeout)
        } catch {
            transport.close()
            throw error
        }
        return transport
    }

    private func openChannel(on device: IOBluetoothDevice, timeout: Duration) async throws {
        var opened: IOBluetoothRFCOMMChannel?
        let status = device.openRFCOMMChannelAsync(&opened, withChannelID: channelID, delegate: self)
        guard status == kIOReturnSuccess, let opened else { throw RFCOMMError.openFailed(status) }
        channel = opened
        if opened.isOpen() { return }

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
            openContinuation = continuation
            Task { [weak self] in
                try? await Task.sleep(for: timeout)
                guard let self, self.openContinuation != nil else { return }
                // The completion callback is unreliable; trust isOpen() if it disagrees.
                self.finishOpen(self.channel?.isOpen() == true ? nil : RFCOMMError.openTimedOut)
            }
        }
    }

    private func finishOpen(_ error: (any Error)?) {
        guard let openContinuation else { return }
        self.openContinuation = nil
        if let error { openContinuation.resume(throwing: error) } else { openContinuation.resume() }
    }

    public func send(_ bytes: [UInt8]) throws {
        guard let channel, channel.isOpen() else { throw RFCOMMError.notOpen }
        let mtu = max(Int(channel.getMTU()), 1)
        var offset = 0
        while offset < bytes.count {
            var chunk = Array(bytes[offset..<min(offset + mtu, bytes.count)])
            let status = chunk.withUnsafeMutableBytes { buffer in
                channel.writeSync(buffer.baseAddress, length: UInt16(buffer.count))
            }
            guard status == kIOReturnSuccess else { throw RFCOMMError.writeFailed(status) }
            offset += chunk.count
        }
    }

    public func close() {
        if let channel {
            channel.setDelegate(nil)
            channel.close()
            self.channel = nil
        }
        finish()
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        finishOpen(RFCOMMError.notOpen)
        continuation.yield(.closed)
        continuation.finish()
        lock?.release()
        lock = nil
    }
}

extension RFCOMMTransport: @preconcurrency IOBluetoothRFCOMMChannelDelegate {
    public func rfcommChannelOpenComplete(_ rfcommChannel: IOBluetoothRFCOMMChannel!, status error: IOReturn) {
        if error == kIOReturnSuccess || rfcommChannel?.isOpen() == true {
            finishOpen(nil)
        } else {
            finishOpen(RFCOMMError.openFailed(error))
        }
    }

    public func rfcommChannelData(_ rfcommChannel: IOBluetoothRFCOMMChannel!, data dataPointer: UnsafeMutableRawPointer!,
                                  length dataLength: Int) {
        guard let dataPointer, dataLength > 0 else { return }
        // The buffer is only valid during this call.
        let bytes = Array(UnsafeRawBufferPointer(start: dataPointer, count: dataLength))
        continuation.yield(.data(bytes))
    }

    public func rfcommChannelClosed(_ rfcommChannel: IOBluetoothRFCOMMChannel!) {
        channel = nil
        finish()
    }
}
