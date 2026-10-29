import Foundation

// IOBluetooth delivers callbacks on the main run loop, so the probe runs its async work on the
// main actor and keeps the run loop spinning until the work calls exit().
setvbuf(stdout, nil, _IOLBF, 0)

let options: ProbeOptions
do {
    options = try ProbeOptions(arguments: Array(CommandLine.arguments.dropFirst()))
} catch let error as ProbeOptions.UsageError where error.description == "help" {
    print(ProbeOptions.usage)
    exit(0)
} catch {
    FileHandle.standardError.write(Data("error: \(error)\n\n\(ProbeOptions.usage)\n".utf8))
    exit(2)
}

let probe = Probe(options: options)

signal(SIGINT, SIG_IGN)
let interrupt = DispatchSource.makeSignalSource(signal: SIGINT, queue: .main)
interrupt.setEventHandler {
    MainActor.assumeIsolated { probe.interrupted() }
}
interrupt.resume()

Task { @MainActor in
    exit(await probe.run())
}
RunLoop.main.run()
