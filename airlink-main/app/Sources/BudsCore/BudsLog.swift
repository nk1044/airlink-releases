import BudsProtocol
import Foundation
import os

/// Unified logging under the `io.github.nk1044.airlink` subsystem.
///
/// Frame (hex) logging is compiled in but off. Turn it on with
/// `defaults write io.github.nk1044.airlink LogFrames -bool YES` and read it with
/// `log stream --level debug --predicate 'subsystem == "io.github.nk1044.airlink"'`.
public enum BudsLog {
    public static let subsystem = "io.github.nk1044.airlink"
    public static let connection = Logger(subsystem: subsystem, category: "connection")
    public static let ui = Logger(subsystem: subsystem, category: "ui")
    static let frames = Logger(subsystem: subsystem, category: "frames")

    /// A trace for `BudsSession`, or `nil` when frame logging is off so no hex is ever formatted.
    /// Read once per connection.
    public static func frameTrace() -> FrameTrace? {
        guard UserDefaults.standard.bool(forKey: "LogFrames") else { return nil }
        return { direction, item in
            let arrow = direction == .outgoing ? "→" : "←"
            switch item {
            case .frame(let frame, let raw):
                frames.debug("\(arrow, privacy: .public) \(Command.name(frame.command), privacy: .public) \(Hex.string(raw), privacy: .public)")
            case .discarded(let bytes):
                frames.debug("\(arrow, privacy: .public) junk \(Hex.string(bytes), privacy: .public)")
            }
        }
    }
}
