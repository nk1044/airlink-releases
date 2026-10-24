import Darwin
import Foundation

/// Cross-process lock held while a process owns the buds' control channel.
///
/// macOS gives one RFCOMM channel per service to one client, so AirLink and airlink-probe must
/// never compete. Both take this `flock` before opening the channel. The kernel drops it if the
/// holder crashes. The holder writes its name and pid into the file, so the loser can say who it is.
public final class ChannelLock: @unchecked Sendable {
    public static let path = NSTemporaryDirectory() + "io.github.nk1044.airlink.rfcomm.lock"

    private let fd: Int32
    private var released = false

    private init(fd: Int32) { self.fd = fd }

    /// Returns the lock, or who holds it.
    public static func acquire() -> Result<ChannelLock, LockBusy> {
        let fd = open(path, O_RDWR | O_CREAT | O_CLOEXEC, 0o644)
        guard fd >= 0 else { return .failure(LockBusy(holder: "unknown (cannot open \(path))")) }
        guard flock(fd, LOCK_EX | LOCK_NB) == 0 else {
            let holder = (try? String(contentsOfFile: path, encoding: .utf8))?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            close(fd)
            return .failure(LockBusy(holder: holder?.isEmpty == false ? holder! : "another process"))
        }
        let me = "\(ProcessInfo.processInfo.processName) (pid \(getpid()))"
        ftruncate(fd, 0)
        _ = me.withCString { pwrite(fd, $0, strlen($0), 0) }
        return .success(ChannelLock(fd: fd))
    }

    /// Idempotent. Call on the thread that acquired the lock.
    public func release() {
        guard !released else { return }
        released = true
        ftruncate(fd, 0)
        flock(fd, LOCK_UN)
        close(fd)
    }

    public struct LockBusy: Error, CustomStringConvertible {
        public let holder: String
        public var description: String { "the control channel is held by \(holder)" }
    }
}
