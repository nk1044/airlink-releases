import BudsProtocol
import Foundation
import Observation

/// UI state for the connected buds. Views read it; the Bluetooth layer drives the lifecycle
/// methods (`deviceAppeared`, `attach`, `failed`, `deviceGone`).
@MainActor
@Observable
public final class DeviceStore {
    public enum Connection: Equatable, Sendable {
        /// No matching buds connected: no status item, nothing running.
        case dormant
        case connecting
        case connected
        /// The buds are connected for audio but the control channel could not be used.
        case failed(String)
    }

    public private(set) var connection: Connection = .dormant
    public private(set) var deviceName = ""
    public private(set) var profile: (any DeviceProfile)?

    /// Latest battery levels. A bud the buds did not report is absent. The case keeps its last
    /// reported level: the buds stop reporting it whenever the lid closes, and it barely changes
    /// while the buds are out.
    public private(set) var battery: [Component: BatteryReading] = [:]
    public private(set) var wear: [Component: WearState] = [:]

    /// The mode the buds have confirmed.
    public private(set) var noiseMode: NoiseMode?
    /// A mode sent but not yet confirmed.
    public private(set) var pendingMode: NoiseMode?
    /// One line shown under the noise control after a refusal or timeout. Cleared by the next attempt.
    public private(set) var message: String?

    /// Whether the status item should be in the menu bar.
    public var isVisible: Bool { connection != .dormant }

    /// The lower of the two bud levels, for the optional menu bar label.
    public var lowestBudPercent: Int? {
        [Component.left, .right].compactMap { battery[$0]?.percent }.min()
    }

    /// What the noise control shows: the pending mode while one is in flight.
    public var displayedMode: NoiseMode? { pendingMode ?? noiseMode }

    @ObservationIgnored private var session: BudsSession?
    @ObservationIgnored private var pushTask: Task<Void, Never>?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var retryHandler: (@MainActor () -> Void)?

    /// Where the last case reading is kept across connections and relaunches.
    @ObservationIgnored private let defaults: UserDefaults
    private static let lastCaseKey = "LastCaseBattery"

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    // MARK: Lifecycle (driven by the Bluetooth layer)

    /// Matching buds connected for audio: show the status item while the channel opens.
    public func deviceAppeared(profile: any DeviceProfile, retry: @escaping @MainActor () -> Void) {
        resetDeviceState()
        deviceName = profile.displayName
        self.profile = profile
        retryHandler = retry
        connection = .connecting
    }

    /// The control session is up: load state once, then follow pushes. No polling.
    public func attach(_ session: BudsSession) async {
        generation += 1
        let current = generation
        self.session = session
        pushTask = Task { [weak self] in
            for await push in session.pushes {
                self?.apply(push)
            }
            // The stream ends when the channel closes.
            self?.sessionEnded(generation: current)
        }
        connection = .connected
        message = nil
        await loadState(generation: current)
    }

    public func failed(_ reason: String) {
        guard connection != .dormant else { return }
        detachSession()
        connection = .failed(reason)
    }

    /// The buds disconnected: drop everything and go dormant.
    public func deviceGone() {
        detachSession()
        resetDeviceState()
        retryHandler = nil
        profile = nil
        deviceName = ""
        connection = .dormant
    }

    // MARK: User actions

    /// The popover opened: refresh battery once.
    public func popoverOpened() {
        guard connection == .connected, let session else { return }
        let current = generation
        Task {
            if let readings = try? await session.battery(), current == generation {
                applyBattery(readings)
            }
        }
    }

    public func retry() {
        guard case .failed = connection, let retryHandler else { return }
        connection = .connecting
        retryHandler()
    }

    public func setNoiseMode(_ mode: NoiseMode) {
        guard connection == .connected, let session, let profile, pendingMode == nil, mode != noiseMode else { return }
        if let reason = profile.refusal(for: mode, wear: wear) {
            message = reason
            return
        }
        message = nil
        pendingMode = mode
        let current = generation
        Task {
            do {
                try await session.setNoise(profile.noiseValue(for: mode))
                guard current == generation else { return }
                noiseMode = mode
            } catch SessionError.rejected(_, let status) {
                guard current == generation else { return }
                message = profile.rejectionMessage(status: status)
            } catch SessionError.timeout {
                guard current == generation else { return }
                message = "The buds didn't respond"
            } catch {
                guard current == generation else { return }
                message = "Couldn't change noise control"
            }
            if current == generation { pendingMode = nil }
        }
    }

    // MARK: Internals

    private func loadState(generation current: Int) async {
        guard let session else { return }
        if let readings = try? await session.battery(), current == generation { applyBattery(readings) }
        if let wear = try? await session.wear(), current == generation { self.wear = wear }
        if let value = try? await session.noiseValue(), current == generation {
            noiseMode = profile?.noiseMode(fromValue: value)
        }
    }

    private func apply(_ push: Push) {
        switch push {
        case .battery(let readings): applyBattery(readings)
        case .wear(let wear): self.wear = wear
        case .noiseMode(let value):
            if let mode = profile?.noiseMode(fromValue: value) { noiseMode = mode }
        case .other: break
        }
    }

    /// Each report is a full snapshot for the buds: a bud missing from it is not reported now.
    /// The case is only updated when it reports a level; otherwise its last level stays.
    private func applyBattery(_ readings: [BatteryReading]) {
        var next = Dictionary(readings.map { ($0.component, $0) }, uniquingKeysWith: { $1 })
        if let reported = next[.caseBattery], reported.percent != nil {
            saveCase(reported)
        } else {
            next[.caseBattery] = lastCase()
        }
        battery = next
    }

    private func lastCase() -> BatteryReading? {
        guard let stored = defaults.object(forKey: Self.lastCaseKey) as? [String: Any],
              let percent = stored["percent"] as? Int, (1...100).contains(percent) else { return nil }
        return BatteryReading(component: .caseBattery, percent: percent, isCharging: stored["charging"] as? Bool ?? false)
    }

    private func saveCase(_ reading: BatteryReading) {
        guard let percent = reading.percent else { return }
        defaults.set(["percent": percent, "charging": reading.isCharging], forKey: Self.lastCaseKey)
    }

    private func sessionEnded(generation ended: Int) {
        guard ended == generation, connection == .connected else { return }
        detachSession()
        connection = .failed("Lost the connection to the buds")
    }

    private func detachSession() {
        generation += 1
        pushTask?.cancel()
        pushTask = nil
        if let session {
            Task { await session.close() }
        }
        session = nil
        pendingMode = nil
    }

    private func resetDeviceState() {
        battery = lastCase().map { [.caseBattery: $0] } ?? [:]
        wear = [:]
        noiseMode = nil
        pendingMode = nil
        message = nil
    }
}
