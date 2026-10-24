import BudsProtocol
import Foundation

public enum SessionError: Error, Equatable, Sendable {
    case timeout(command: UInt16)
    /// The buds answered with a non-zero status byte.
    case rejected(command: UInt16, status: UInt8)
    case malformedReply(command: UInt16)
    case closed
    case sendFailed(String)
}

public enum TraceDirection: Sendable { case outgoing, incoming }

public enum TraceItem: Sendable {
    case frame(Frame, raw: [UInt8])
    /// Incoming bytes that were not part of a valid frame.
    case discarded([UInt8])
}

/// Called for every frame in and out. Pass `nil` (the default) to skip all trace work.
public typealias FrameTrace = @Sendable (TraceDirection, TraceItem) -> Void

public struct HandshakeResult: Sendable {
    /// `nil` when the profile skips the hello.
    public var helloAnswered: Bool?
    /// `nil` when the profile subscribes to nothing.
    public var subscribed: Bool?
}

/// One control session over an open transport: serialised request/response with a timeout,
/// plus a stream of unsolicited pushes from the buds.
public actor BudsSession {
    public nonisolated let pushes: AsyncStream<Push>
    private let pushContinuation: AsyncStream<Push>.Continuation

    private let transport: any Transport
    private let profile: any DeviceProfile
    private let requestTimeout: Duration
    private let trace: FrameTrace?

    private var decoder = FrameDecoder()
    private var sequence: UInt8 = 0
    private var isOpen = true
    private var readTask: Task<Void, Never>?

    private struct Pending {
        let reply: UInt16
        let continuation: CheckedContinuation<Frame, any Error>
        let timeout: Task<Void, Never>
    }
    private var pending: Pending?

    /// The buds drop commands that arrive back to back, so only one request is in flight.
    private var busy = false
    private var waitingForTurn: [CheckedContinuation<Void, Never>] = []

    public init(transport: any Transport, profile: any DeviceProfile,
                requestTimeout: Duration = .milliseconds(2500), trace: FrameTrace? = nil) {
        (pushes, pushContinuation) = AsyncStream.makeStream(bufferingPolicy: .bufferingNewest(32))
        self.transport = transport
        self.profile = profile
        self.requestTimeout = requestTimeout
        self.trace = trace
    }

    /// Starts reading, then sends the optional hello and the optional event subscription.
    /// Neither failing is fatal.
    @discardableResult
    public func start() async -> HandshakeResult {
        var result = HandshakeResult(helloAnswered: nil, subscribed: nil)
        if profile.sendsHello {
            result.helloAnswered = (try? await request(Command.hello)) != nil
        }
        if !profile.subscriptions.isEmpty {
            result.subscribed = (try? await request(Command.subscribe, Request.subscribe(profile.subscriptions))) != nil
        }
        return result
    }

    public func close() async {
        await transport.close()
        shutDown()
    }

    // MARK: Requests

    /// Sends `command` and waits for its reply (`command | 0x8000`).
    /// Throws `.rejected` when the reply's status byte is non-zero.
    public func request(_ command: UInt16, _ payload: [UInt8] = []) async throws -> Frame {
        await startReading()
        await takeTurn()
        defer { passTurn() }
        guard isOpen else { throw SessionError.closed }

        let frame = Frame(command: command, sequence: nextSequence(), payload: payload)
        let bytes = frame.encoded()
        let reply = Command.reply(to: command)
        return try await withCheckedThrowingContinuation { continuation in
            let timeout = Task { [weak self, requestTimeout] in
                try? await Task.sleep(for: requestTimeout)
                guard !Task.isCancelled else { return }
                await self?.expire(reply)
            }
            pending = Pending(reply: reply, continuation: continuation, timeout: timeout)
            trace?(.outgoing, .frame(frame, raw: bytes))
            write(bytes)
        }
    }

    public func battery() async throws -> [BatteryReading] {
        let frame = try await request(Command.battery)
        guard let readings = Parse.batteryReply(frame.payload) else { throw SessionError.malformedReply(command: frame.command) }
        return readings
    }

    public func noiseValue() async throws -> UInt8 {
        let frame = try await request(Command.noiseRead, Request.noiseRead)
        guard let value = Parse.noiseReply(frame.payload) else { throw SessionError.malformedReply(command: frame.command) }
        return value
    }

    public func setNoise(_ value: UInt8) async throws {
        _ = try await request(Command.noiseSet, Request.noiseSet(value))
    }

    public func wear() async throws -> [Component: WearState] {
        let frame = try await request(Command.wearStatus)
        guard let wear = Parse.wearReply(frame.payload) else { throw SessionError.malformedReply(command: frame.command) }
        return wear
    }

    public func productID() async throws -> String {
        let frame = try await request(Command.productID)
        guard let id = Parse.productID(frame.payload) else { throw SessionError.malformedReply(command: frame.command) }
        return id
    }

    public func firmware() async throws -> String {
        let frame = try await request(Command.firmware)
        guard let text = Parse.firmware(frame.payload) else { throw SessionError.malformedReply(command: frame.command) }
        return text
    }

    // MARK: Internals

    private func startReading() async {
        guard readTask == nil, isOpen else { return }
        let events = await transport.events
        guard readTask == nil else { return }   // another caller won the race across the await
        readTask = Task { [weak self] in
            for await event in events {
                await self?.receive(event)
            }
        }
    }

    private func takeTurn() async {
        if !busy {
            busy = true
            return
        }
        await withCheckedContinuation { waitingForTurn.append($0) }
    }

    private func passTurn() {
        if waitingForTurn.isEmpty {
            busy = false
        } else {
            waitingForTurn.removeFirst().resume()
        }
    }

    private func nextSequence() -> UInt8 {
        // 0xFF marks pushes from the buds, so it is never used for requests.
        sequence = sequence >= 0xFE ? 1 : sequence + 1
        return sequence
    }

    private func write(_ bytes: [UInt8]) {
        Task { [transport] in
            do {
                try await transport.send(bytes)
            } catch {
                self.failPending(.sendFailed(String(describing: error)))
            }
        }
    }

    private func receive(_ event: TransportEvent) {
        switch event {
        case .data(let bytes):
            for decoded in decoder.append(bytes) {
                switch decoded {
                case .discarded(let junk):
                    trace?(.incoming, .discarded(junk))
                case .frame(let frame):
                    if let trace { trace(.incoming, .frame(frame, raw: frame.encoded())) }
                    route(frame)
                }
            }
        case .closed:
            shutDown()
        }
    }

    private func route(_ frame: Frame) {
        if let pending, frame.command == pending.reply {
            self.pending = nil
            pending.timeout.cancel()
            if let status = frame.status, status != 0x00 {
                pending.continuation.resume(throwing: SessionError.rejected(command: frame.command & ~Command.replyBit, status: status))
            } else {
                pending.continuation.resume(returning: frame)
            }
            return
        }
        if frame.command == Command.timeRequest {
            answerTimeRequest(frame)
            return
        }
        if let push = Push(frame) {
            pushContinuation.yield(push)
        }
    }

    /// Some OPO firmware asks for the time and drops the link if it goes unanswered.
    private func answerTimeRequest(_ request: Frame) {
        let seconds = UInt32(clamping: Int64(Date().timeIntervalSince1970))
        let reply = Frame(command: Command.reply(to: Command.timeRequest), sequence: request.sequence,
                          payload: Request.timeReply(unixSeconds: seconds))
        let bytes = reply.encoded()
        trace?(.outgoing, .frame(reply, raw: bytes))
        write(bytes)
    }

    private func expire(_ reply: UInt16) {
        guard let pending, pending.reply == reply else { return }
        self.pending = nil
        pending.continuation.resume(throwing: SessionError.timeout(command: reply & ~Command.replyBit))
    }

    private func failPending(_ error: SessionError) {
        guard let pending else { return }
        self.pending = nil
        pending.timeout.cancel()
        pending.continuation.resume(throwing: error)
    }

    private func shutDown() {
        guard isOpen else { return }
        isOpen = false
        failPending(.closed)
        readTask?.cancel()
        readTask = nil
        pushContinuation.finish()
        decoder.reset()
    }
}
