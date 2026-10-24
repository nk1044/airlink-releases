import BudsProtocol
import Testing
@testable import BudsCore

/// In-memory transport. `respond` sees each outgoing frame and returns frames to send back.
@MainActor
final class MockTransport: Transport {
    let events: AsyncStream<TransportEvent>
    private let continuation: AsyncStream<TransportEvent>.Continuation
    private(set) var sent: [Frame] = []
    var respond: (Frame) -> [Frame] = { _ in [] }

    init() {
        (events, continuation) = AsyncStream.makeStream()
    }

    func send(_ bytes: [UInt8]) throws {
        var decoder = FrameDecoder()
        for case .frame(let frame) in decoder.append(bytes) {
            sent.append(frame)
            for reply in respond(frame) { deliver(reply.encoded()) }
        }
    }

    func deliver(_ bytes: [UInt8]) { continuation.yield(.data(bytes)) }

    func close() {
        continuation.yield(.closed)
        continuation.finish()
    }
}

private func reply(to frame: Frame, _ payload: [UInt8]) -> Frame {
    Frame(command: Command.reply(to: frame.command), sequence: frame.sequence, payload: payload)
}

/// Answers hello and subscribe like real buds.
@MainActor
private func cooperativeBuds(_ transport: MockTransport, extra: @escaping (Frame) -> [Frame]? = { _ in nil }) {
    transport.respond = { frame in
        if let custom = extra(frame) { return custom }
        switch frame.command {
        case Command.hello, Command.subscribe: return [reply(to: frame, [0x00])]
        default: return []
        }
    }
}

@Suite struct SessionTests {
    @Test @MainActor func handshakeThenBattery() async throws {
        let transport = MockTransport()
        cooperativeBuds(transport) { frame in
            frame.command == Command.battery ? [reply(to: frame, [0x00, 0x03, 0x01, 0x55, 0x02, 0x57, 0x03, 0x00])] : nil
        }
        let session = BudsSession(transport: transport, profile: RealmeBudsAir6Pro())
        let handshake = await session.start()
        #expect(handshake.helloAnswered == true)
        #expect(handshake.subscribed == nil)

        let battery = try await session.battery()
        #expect(battery.map(\.percent) == [85, 87, nil])
        #expect(transport.sent.map(\.command) == [Command.hello, Command.battery])
        await session.close()
    }

    @Test @MainActor func silentHelloIsNotFatal() async throws {
        let transport = MockTransport()
        transport.respond = { frame in frame.command == Command.subscribe ? [reply(to: frame, [0x00])] : [] }
        let session = BudsSession(transport: transport, profile: RealmeBudsAir6Pro(), requestTimeout: .milliseconds(50))
        let handshake = await session.start()
        #expect(handshake.helloAnswered == false)
        await session.close()
    }

    @Test @MainActor func timeout() async {
        let transport = MockTransport()
        let session = BudsSession(transport: transport, profile: RealmeBudsAir6Pro(), requestTimeout: .milliseconds(50))
        await #expect(throws: SessionError.timeout(command: Command.noiseRead)) {
            _ = try await session.noiseValue()
        }
        await session.close()
    }

    @Test @MainActor func rejectedNoiseSet() async {
        let transport = MockTransport()
        transport.respond = { frame in frame.command == Command.noiseSet ? [reply(to: frame, [0x0E])] : [] }
        let session = BudsSession(transport: transport, profile: RealmeBudsAir6Pro())
        await #expect(throws: SessionError.rejected(command: Command.noiseSet, status: 0x0E)) {
            try await session.setNoise(0x08)
        }
        await session.close()
    }

    @Test @MainActor func requestsAreSerialised() async throws {
        let transport = MockTransport()
        var inFlight = 0
        var maxInFlight = 0
        transport.respond = { frame in
            inFlight += 1
            maxInFlight = max(maxInFlight, inFlight)
            // Reply later, so overlapping requests would be visible.
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(10))
                inFlight -= 1
                transport.deliver(reply(to: frame, [0x00, 0x01, 0x01, 0x02]).encoded())
            }
            return []
        }
        let session = BudsSession(transport: transport, profile: RealmeBudsAir6Pro())
        async let a = session.noiseValue()
        async let b = session.noiseValue()
        async let c = session.noiseValue()
        let values = try await [a, b, c]
        #expect(values == [0x02, 0x02, 0x02])
        #expect(maxInFlight == 1)
        await session.close()
    }

    @Test @MainActor func pushesAndTimeRequest() async throws {
        let transport = MockTransport()
        cooperativeBuds(transport)
        let session = BudsSession(transport: transport, profile: RealmeBudsAir6Pro())
        await session.start()
        transport.deliver(Frame(command: Command.eventPush, sequence: 0xFF, payload: [0x03, 0x01, 0x01, 0x02]).encoded())
        transport.deliver(Frame(command: Command.timeRequest, sequence: 0x07).encoded())

        var iterator = session.pushes.makeAsyncIterator()
        #expect(await iterator.next() == .noiseMode(0x02))

        // Let the time reply go out.
        try await Task.sleep(for: .milliseconds(20))
        let timeReply = transport.sent.first { $0.command == 0x8500 }
        #expect(timeReply?.sequence == 0x07)
        #expect(timeReply?.payload.count == 5)
        await session.close()
    }

    @Test @MainActor func closeFailsPendingRequest() async {
        let transport = MockTransport()
        let session = BudsSession(transport: transport, profile: RealmeBudsAir6Pro())
        let request = Task { try await session.noiseValue() }
        try? await Task.sleep(for: .milliseconds(20))
        transport.close()
        await #expect(throws: SessionError.closed) { _ = try await request.value }
    }
}
