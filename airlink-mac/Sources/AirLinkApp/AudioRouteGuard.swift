import BudsCore
import CoreAudio
import Foundation
import Observation
import os

/// Keeps the buds in stereo (A2DP) by keeping their microphone from being the default input.
///
/// Over classic Bluetooth, using the buds' microphone switches them to the hands-free profile:
/// mono, 16 kHz, and noisy. macOS often makes the buds the default input as they connect, so
/// music sounds like a phone call even though nothing is recording on purpose.
///
/// The guard steps in only around a connection (and once when the option is turned on): if the
/// buds' input becomes the default within `window` of the buds connecting, it moves the input to
/// the Mac's built-in microphone. Choosing the buds' microphone by hand later is left alone, so
/// calls can still use it.
@MainActor
final class AudioRouteGuard {
    private let store: DeviceStore
    private let preferences: Preferences
    private static let log = Logger(subsystem: "io.github.nk1044.airlink", category: "audio")
    /// How long after the buds connect (or their audio device appears) automatic switches are undone.
    private let window: Duration = .seconds(20)

    private var armedUntil: ContinuousClock.Instant?
    private var listening = false
    private var activeAddress: String?
    private var wasEnabled = false
    private var budsInputPresent = false

    private let listenerBlock: AudioObjectPropertyListenerBlock

    init(store: DeviceStore, preferences: Preferences) {
        self.store = store
        self.preferences = preferences
        var handler: (@MainActor () -> Void)?
        listenerBlock = { _, _ in
            MainActor.assumeIsolated { handler?() }
        }
        handler = { [weak self] in self?.audioConfigurationChanged() }
    }

    func start() { track() }

    func stop() { setListening(false) }

    private func track() {
        withObservationTracking {
            apply(address: store.deviceAddress, enabled: preferences.keepHighQualityAudio)
        } onChange: { [weak self] in
            Task { @MainActor in self?.track() }
        }
    }

    private func apply(address: String?, enabled: Bool) {
        let addressChanged = address != activeAddress
        let justEnabled = enabled && !wasEnabled
        activeAddress = address
        wasEnabled = enabled

        guard enabled, address != nil else {
            setListening(false)
            armedUntil = nil
            return
        }
        setListening(true)
        if addressChanged || justEnabled {
            arm()
            budsInputPresent = budsInputDevice() != nil
            enforce()
        }
    }

    private func arm() { armedUntil = .now + window }

    private var isArmed: Bool { armedUntil.map { .now < $0 } ?? false }

    /// The device list or the default input changed.
    private func audioConfigurationChanged() {
        // The buds' audio devices are added a few seconds after the link comes up; treat that
        // as part of connecting.
        let present = budsInputDevice() != nil
        if present && !budsInputPresent { arm() }
        budsInputPresent = present
        if isArmed { enforce() }
    }

    /// Moves the default input off the buds, if it is on them.
    private func enforce() {
        guard let buds = budsInputDevice(), Self.defaultInput() == buds else { return }
        guard let builtIn = Self.builtInMicrophone() else {
            Self.log.info("no built-in microphone; leaving the input on the buds")
            return
        }
        var device = builtIn
        var address = Self.address(kAudioHardwarePropertyDefaultInputDevice)
        let status = AudioObjectSetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil,
                                                UInt32(MemoryLayout<AudioDeviceID>.size), &device)
        Self.log.info("moved default input from the buds to the built-in microphone (status \(status))")
    }

    // MARK: Listening

    private static let watched: [AudioObjectPropertySelector] = [
        kAudioHardwarePropertyDevices, kAudioHardwarePropertyDefaultInputDevice,
    ]

    private func setListening(_ on: Bool) {
        guard on != listening else { return }
        listening = on
        for selector in Self.watched {
            var address = Self.address(selector)
            if on {
                AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, .main, listenerBlock)
            } else {
                AudioObjectRemovePropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, .main, listenerBlock)
            }
        }
    }

    // MARK: CoreAudio lookups

    /// The buds' input device. CoreAudio names Bluetooth devices "<address>:input", with the
    /// address in the same dashed form IOBluetooth uses.
    private func budsInputDevice() -> AudioDeviceID? {
        guard let budsAddress = activeAddress?.uppercased() else { return nil }
        return Self.devices().first { id in
            guard let uid = Self.uid(of: id)?.uppercased() else { return false }
            return uid.hasPrefix(budsAddress) && Self.hasInput(id)
        }
    }

    private static func builtInMicrophone() -> AudioDeviceID? {
        devices().first { transportType(of: $0) == kAudioDeviceTransportTypeBuiltIn && hasInput($0) }
    }

    private static func address(_ selector: AudioObjectPropertySelector,
                                scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
    }

    private static func devices() -> [AudioDeviceID] {
        var address = address(kAudioHardwarePropertyDevices)
        var size: UInt32 = 0
        let system = AudioObjectID(kAudioObjectSystemObject)
        guard AudioObjectGetPropertyDataSize(system, &address, 0, nil, &size) == noErr, size > 0 else { return [] }
        var ids = [AudioDeviceID](repeating: 0, count: Int(size) / MemoryLayout<AudioDeviceID>.size)
        guard AudioObjectGetPropertyData(system, &address, 0, nil, &size, &ids) == noErr else { return [] }
        return ids
    }

    private static func defaultInput() -> AudioDeviceID? {
        var address = address(kAudioHardwarePropertyDefaultInputDevice)
        var id = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        let status = AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &id)
        return status == noErr && id != 0 ? id : nil
    }

    private static func uid(of id: AudioDeviceID) -> String? {
        var address = address(kAudioDevicePropertyDeviceUID)
        var uid: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(id, &address, 0, nil, &size, &uid) == noErr, let uid else { return nil }
        return uid.takeRetainedValue() as String
    }

    private static func transportType(of id: AudioDeviceID) -> UInt32 {
        var address = address(kAudioDevicePropertyTransportType)
        var type: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        AudioObjectGetPropertyData(id, &address, 0, nil, &size, &type)
        return type
    }

    private static func hasInput(_ id: AudioDeviceID) -> Bool {
        var address = address(kAudioDevicePropertyStreamConfiguration, scope: kAudioObjectPropertyScopeInput)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(id, &address, 0, nil, &size) == noErr, size > 0 else { return false }
        let list = UnsafeMutableRawPointer.allocate(byteCount: Int(size), alignment: MemoryLayout<AudioBufferList>.alignment)
        defer { list.deallocate() }
        guard AudioObjectGetPropertyData(id, &address, 0, nil, &size, list) == noErr else { return false }
        let buffers = UnsafeMutableAudioBufferListPointer(list.assumingMemoryBound(to: AudioBufferList.self))
        return buffers.contains { $0.mNumberChannels > 0 }
    }
}
