import AppKit
import BudsCore
@preconcurrency import IOBluetooth

/// Drives the app's lifecycle from Bluetooth connection events.
///
/// Dormant, it holds only an IOBluetooth connect notification: no channel, no timers, no scanning.
/// When matching buds connect for audio it shows the status item, opens the control channel and
/// arms a disconnect notification. On disconnect it releases everything and goes dormant again.
/// It never connects to or pages buds that are not already connected.
@MainActor
public final class ConnectionMonitor: NSObject {
    private let store: DeviceStore

    private var connectNotification: IOBluetoothUserNotification?
    private var disconnectNotification: IOBluetoothUserNotification?
    private var wakeObserver: NSObjectProtocol?

    private var device: IOBluetoothDevice?
    private var profile: (any DeviceProfile)?
    private var matchReason: BluetoothDevices.MatchReason?
    private var transport: RFCOMMTransport?
    /// Bumped on every open and teardown; completions from an older attempt are ignored.
    private var attempt = 0

    public init(store: DeviceStore) {
        self.store = store
    }

    public func start() {
        guard connectNotification == nil else { return }
        // The connect notification misses buds that were already connected, so check once.
        let alreadyConnected = BluetoothDevices.paired().first {
            $0.isConnected() && BluetoothDevices.profile(for: $0) != nil
        }
        connectNotification = IOBluetoothDevice.register(forConnectNotifications: self,
                                                         selector: #selector(deviceConnected(_:device:)))
        wakeObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.checkStillConnected() }
        }
        if let alreadyConnected { adopt(alreadyConnected) }
    }

    /// Closes the channel synchronously, for app termination.
    public func stop() {
        teardown()
        connectNotification?.unregister()
        connectNotification = nil
        if let wakeObserver { NSWorkspace.shared.notificationCenter.removeObserver(wakeObserver) }
        wakeObserver = nil
    }

    // MARK: Notifications

    @objc private func deviceConnected(_ notification: IOBluetoothUserNotification, device: IOBluetoothDevice) {
        guard self.device == nil else { return }
        adopt(device, justConnected: true)
    }

    @objc private func deviceDisconnected(_ notification: IOBluetoothUserNotification, device: IOBluetoothDevice) {
        guard let current = self.device, current.addressString == device.addressString else { return }
        BudsLog.connection.info("buds disconnected")
        teardown()
    }

    /// After sleep the disconnect notification can be lost; re-read the link state once.
    private func checkStillConnected() {
        if let device, !device.isConnected() { teardown() }
    }

    // MARK: Connection

    /// - Parameter justConnected: the link came up moments ago, so macOS is still bringing up the
    ///   audio profiles (A2DP, and hands-free over its own RFCOMM channel). An RFCOMM open sent then
    ///   stalls for good, so the open waits for them to settle.
    private func adopt(_ device: IOBluetoothDevice, justConnected: Bool = false) {
        // The connect notification may also fire for buds found by the launch check.
        guard self.device == nil, let match = BluetoothDevices.profile(for: device) else { return }
        BudsLog.connection.info("matched \(match.profile.id, privacy: .public) by \(match.reason.rawValue, privacy: .public)")
        self.device = device
        profile = match.profile
        matchReason = match.reason
        // Disconnect notifications fire once, so one is armed per connection.
        disconnectNotification = device.register(forDisconnectNotification: self,
                                                 selector: #selector(deviceDisconnected(_:device:)))
        // A name match is certain enough to show the item now; a service-only match waits for the
        // product id.
        if match.reason == .name { showItem(for: match.profile) }
        openControlChannel(after: justConnected ? Self.audioSettleTime : nil)
    }

    /// How long after a fresh connection the audio profiles take to come up (A2DP alone defers
    /// its endpoint discovery by 2 s).
    static let audioSettleTime: Duration = .seconds(4)

    private func showItem(for profile: any DeviceProfile) {
        store.deviceAppeared(profile: profile) { [weak self] in
            self?.retry()
        }
    }

    private func retry() {
        // After a stalled open, this process's Bluetooth session answers "Already connected" to
        // every new open, so only a new process can recover.
        if openStalled {
            Self.relaunch()
        } else {
            openControlChannel(after: nil)
        }
    }

    /// Set once an open has stalled in this process; see `retry()`.
    private var openStalled = false

    private func openControlChannel(after delay: Duration?) {
        guard let device, let profile else { return }
        attempt += 1
        let current = attempt
        transport?.close()
        transport = nil

        Task {
            if let delay {
                try? await Task.sleep(for: delay)
                guard current == attempt else { return }
            }
            do {
                let opened = try await RFCOMMTransport.open(device: device, services: profile.controlServices)
                guard current == attempt else { opened.close(); return }
                transport = opened
                let session = BudsSession(transport: opened, profile: profile, trace: BudsLog.frameTrace())
                await session.start()
                guard current == attempt else { await session.close(); return }

                if matchReason == .service {
                    let id = try? await session.productID()
                    guard current == attempt else { await session.close(); return }
                    guard let id, profile.productIDs.contains(id) else {
                        BudsLog.connection.info("service match rejected: product id \(id ?? "none", privacy: .public)")
                        await session.close()
                        teardown()
                        return
                    }
                    showItem(for: profile)
                }
                BudsLog.connection.info("control channel \(opened.channelID) open")
                await store.attach(session)
            } catch {
                guard current == attempt else { return }
                BudsLog.connection.error("open failed: \(String(describing: error), privacy: .public)")
                if case RFCOMMError.openTimedOut = error {
                    openStalled = true
                    if device.isConnected(), Self.mayAutoRelaunch() {
                        Self.relaunch()
                        return
                    }
                }
                if matchReason == .service { teardown(); return }
                store.failed(Self.describe(error))
            }
        }
    }

    // MARK: Recovery by relaunch

    private static let lastAutoRelaunchKey = "LastAutoRelaunch"

    /// At most one automatic relaunch every two minutes, so buds that never answer can't loop.
    private static func mayAutoRelaunch() -> Bool {
        let defaults = UserDefaults.standard
        let last = defaults.double(forKey: lastAutoRelaunchKey)
        let now = Date().timeIntervalSince1970
        guard now - last > 120 else { return false }
        defaults.set(now, forKey: lastAutoRelaunchKey)
        return true
    }

    /// Starts a fresh copy of the app a moment after this one quits. The delay lets this process
    /// release the channel lock first; the shell outlives us.
    private static func relaunch() {
        BudsLog.connection.info("relaunching to reset the Bluetooth session")
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/sh")
        process.arguments = ["-c", "sleep 1; /usr/bin/open -n \"$0\"", Bundle.main.bundlePath]
        try? process.run()
        NSApp.terminate(nil)
    }

    private func teardown() {
        attempt += 1
        disconnectNotification?.unregister()
        disconnectNotification = nil
        transport?.close()
        transport = nil
        device = nil
        profile = nil
        matchReason = nil
        store.deviceGone()
        // Back to dormant: return the session's freed heap pages.
        DispatchQueue.main.async { malloc_zone_pressure_relief(nil, 0) }
    }

    private static func describe(_ error: any Error) -> String {
        switch error {
        case RFCOMMError.busy(let holder):
            let name = holder.components(separatedBy: " (").first ?? holder
            return "\(name) is using the buds. Quit it, then try again."
        case RFCOMMError.serviceNotFound:
            return "These buds don't offer a control channel"
        case RFCOMMError.notConnected:
            return "The buds aren't connected"
        default:
            return "Couldn't reach the buds."
        }
    }
}
