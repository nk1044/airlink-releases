import BudsCore

struct ProbeOptions {
    enum Action {
        case list
        case info
        case battery
        case noiseGet
        case noiseSet(String)
        case monitor(seconds: Double)
    }

    enum ServiceChoice { case spp, vendor }

    var action: Action
    var device: String?
    var service: ServiceChoice?
    var channel: UInt8?
    var hello = true
    var listenSeconds: Double = 2

    struct UsageError: Error, CustomStringConvertible { let description: String }

    static let usage = """
    airlink-probe: talk to realme Buds Air 6 Pro over RFCOMM and print every frame in and out.
    Quit AirLink first: only one process can hold the control channel.

    USAGE
      airlink-probe list                                  paired devices, SDP services, RFCOMM channels
      airlink-probe info                                  product id, firmware, wear, battery, noise mode
      airlink-probe battery                               left / right / case battery
      airlink-probe noise get                             current noise-control mode
      airlink-probe noise set off|transparency|anc|0xNN   change noise control, then read it back
      airlink-probe monitor [seconds]                     print pushes (long-press a bud); default 30 s

    OPTIONS
      --device <name|address>   pick a device (default: first connected device matching a profile)
      --service spp|vendor      open the standard serial port (1101) or vendor (079A) service only
      --channel <n>             open this RFCOMM channel number directly, skipping the SDP lookup
      --no-hello                skip the 0x0100 hello
      --listen <seconds>        keep printing pushes this long after the command (default 2)
    """

    init(arguments: [String]) throws {
        var positional: [String] = []
        var index = 0
        func value(_ flag: String) throws -> String {
            index += 1
            guard index < arguments.count else { throw UsageError(description: "\(flag) needs a value") }
            return arguments[index]
        }
        while index < arguments.count {
            let arg = arguments[index]
            switch arg {
            case "--device": device = try value(arg)
            case "--service":
                switch try value(arg) {
                case "spp": service = .spp
                case "vendor": service = .vendor
                case let other: throw UsageError(description: "unknown service \(other)")
                }
            case "--channel":
                guard let n = UInt8(try value(arg)), (1...30).contains(n) else {
                    throw UsageError(description: "--channel must be 1-30")
                }
                channel = n
            case "--no-hello": hello = false
            case "--listen":
                guard let s = Double(try value(arg)), s >= 0 else { throw UsageError(description: "--listen needs seconds") }
                listenSeconds = s
            case "-h", "--help", "help": throw UsageError(description: "help")
            default:
                if arg.hasPrefix("--") { throw UsageError(description: "unknown option \(arg)") }
                positional.append(arg)
            }
            index += 1
        }

        switch positional.first {
        case "list": action = .list
        case "info": action = .info
        case "battery": action = .battery
        case "noise":
            switch (positional.dropFirst().first, positional.dropFirst(2).first) {
            case ("get", _): action = .noiseGet
            case ("set", let mode?): action = .noiseSet(mode)
            default: throw UsageError(description: "use `noise get` or `noise set <mode>`")
            }
        case "monitor":
            let seconds = positional.dropFirst().first.flatMap(Double.init) ?? 30
            action = .monitor(seconds: seconds)
        case let other?: throw UsageError(description: "unknown command \(other)")
        case nil: throw UsageError(description: "missing command")
        }
    }

    func services(for profile: any DeviceProfile) -> [ServiceUUID] {
        switch service {
        case .spp: [.serialPort]
        case .vendor: [.opoVendor]
        case nil: profile.controlServices
        }
    }
}
