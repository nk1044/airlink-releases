public enum TransportEvent: Sendable {
    case data([UInt8])
    case closed
}

/// A byte pipe to the buds that is already open. Opening is transport-specific
/// (see `RFCOMMTransport.open`), so it is not part of this protocol.
@MainActor
public protocol Transport: AnyObject, Sendable {
    /// Incoming bytes, then `.closed` once. Single consumer.
    var events: AsyncStream<TransportEvent> { get }
    func send(_ bytes: [UInt8]) throws
    func close()
}
