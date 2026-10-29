using CmfLink.Bluetooth;
using CmfLink.Core;
using CmfLink.Protocol;
using Windows.Devices.Bluetooth;

namespace CmfLink.Probe;

/// <summary>
/// Talks to the buds from a terminal and prints every frame. Quit CMF Link first: only one
/// process can hold the control channel. The probe never touches firmware update.
/// </summary>
internal static class Program
{
    private const string Usage = """
        cmflink-probe <command>

          list                         paired devices, connection state, RFCOMM services
          info                         firmware, battery, noise, EQ, bass, latency, gestures, dual
          noise [anc|transparency|off|0xNN]
          eq [dirac|pop|rock|classical|electronic|vocals|custom|0xNN]
          bass [on <1-5>|off]
          latency [on|off]
          find <left|right> <on|off>
          dual                         dual-connection state and device list
          customeq [<bass> <mid> <treble>]   read, or set gains in dB
          monitor [seconds]            print pushes (default 30 s)
          raw <cmd hex> [payload hex]  send any request and print the reply
        """;

    private static async Task<int> Main(string[] args)
    {
        if (args.Length == 0 || args[0] is "-h" or "--help" or "help")
        {
            Console.WriteLine(Usage);
            return 0;
        }

        BudsLog.Sink = line => Console.Error.WriteLine(line);
        try
        {
            if (args[0] == "list") return await ListAsync();
            return await WithSessionAsync(args);
        }
        catch (Exception e)
        {
            Console.Error.WriteLine($"error: {e.GetType().Name} 0x{e.HResult:X8}: {e.Message}");
            return 1;
        }
    }

    private static async Task<int> ListAsync()
    {
        var paired = await BluetoothDevices.PairedAsync();
        Console.WriteLine($"{paired.Count} paired device(s):");
        foreach (var d in paired)
        {
            var profile = DeviceRegistry.Match(d.Name);
            Console.WriteLine($"  {d.Name,-30} {d.AddressText}  {(d.IsConnected ? "connected" : "not connected")}{(profile != null ? $"  → profile {profile.Id}" : "")}");
        }

        foreach (var (d, _) in await BluetoothDevices.PairedBudsAsync())
        {
            using var device = await BluetoothDevice.FromIdAsync(d.Id);
            if (device == null) continue;
            var mode = device.ConnectionStatus == BluetoothConnectionStatus.Connected ? BluetoothCacheMode.Uncached : BluetoothCacheMode.Cached;
            Console.WriteLine($"\nRFCOMM services on {d.Name} ({mode.ToString().ToLowerInvariant()}):");
            foreach (var s in await BluetoothDevices.RfcommServicesAsync(device, mode))
            {
                var mark = s.ServiceId.Equals(DeviceProfile.ControlService.ToString(), StringComparison.OrdinalIgnoreCase) ? "  ← control" : "";
                Console.WriteLine($"  {s.ServiceName,-6} {s.ServiceId}  {s.Name ?? "(no name)"}{mark}");
            }
        }
        return 0;
    }

    private static async Task<int> WithSessionAsync(string[] args)
    {
        var buds = await BluetoothDevices.PairedBudsAsync();
        if (buds.Count == 0)
        {
            Console.Error.WriteLine("No supported buds are paired with this PC.");
            return 2;
        }
        var (paired, profile) = buds.FirstOrDefault(b => b.Device.IsConnected);
        if (paired == null)
        {
            Console.Error.WriteLine($"{buds[0].Device.Name} is paired but not connected. Connect it for audio first.");
            return 2;
        }

        using var channelLock = ChannelLock.TryAcquire("cmflink-probe", out var holder);
        if (channelLock == null)
        {
            Console.Error.WriteLine($"{holder ?? "Another app"} is using the buds. Quit it first.");
            return 3;
        }

        using var device = await BluetoothDevice.FromIdAsync(paired.Id)
            ?? throw new InvalidOperationException("Windows couldn't open the device");
        var transport = new RfcommTransport(device, DeviceProfile.ControlService);
        await using var session = new BudsSession(transport, rejectBadCrc: false);

        session.FrameSent += f => Print(">>", f);
        session.FrameReceived += f => Print("<<", f);
        session.MessageReceived += m => Console.WriteLine($"        {Describe(m, profile)}");
        session.Closed += e => Console.WriteLine($"-- channel closed{(e != null ? $": {e.Message}" : "")}");

        var opened = DateTime.Now;
        await session.OpenAsync(CancellationToken.None);
        Console.WriteLine($"-- opened {transport.Endpoint} on {paired.Name} in {(DateTime.Now - opened).TotalMilliseconds:0} ms");

        var probe = new ProbeCommands(session, profile);
        int code = await probe.RunAsync(args);
        // Late pushes (the noise push can trail its ack) still get printed.
        await Task.Delay(300);
        if (session.CrcErrors > 0) Console.WriteLine($"-- {session.CrcErrors} frame(s) failed the CRC check");
        return code;
    }

    private static readonly DateTime Start = DateTime.Now;

    private static void Print(string direction, Frame f)
    {
        var t = (DateTime.Now - Start).TotalMilliseconds;
        Console.WriteLine($"{t,7:0} {direction} {Commands.Name(f.Command),-20} {Hex.Format(f.Raw ?? f.Encode())}{(f.CrcValid ? "" : "  (CRC mismatch)")}");
    }

    internal static string Describe(BudsMessage m, DeviceProfile profile) => m switch
    {
        BatteryMessage b => "battery: " + string.Join(", ", b.Levels.Select(kv => $"{kv.Key} {kv.Value.Percent}%{(kv.Value.Charging ? " charging" : "")}")),
        NoiseModeMessage n => $"noise: 0x{n.WireValue:X2} = {profile.NoiseModeFromValue(n.WireValue)?.ToString() ?? "unknown"}",
        EqPresetMessage e => $"EQ preset: 0x{e.WireValue:X2} = {(Enum.IsDefined((EqPreset)e.WireValue) ? ((EqPreset)e.WireValue).ToString() : "unknown")}",
        FirmwareMessage f => $"firmware: {f.Version}",
        LowLatencyMessage l => $"low latency: {(l.Enabled ? "on" : "off")}",
        UltraBassMessage u => $"ultra bass: {(u.Enabled ? "on" : "off")}, level {u.Level}",
        GesturesMessage g => "gestures: " + string.Join("; ", g.Gestures.Select(x => $"{x.Bud} {x.Type} → {x.Action}")),
        CustomEqMessage c => "custom EQ: " + string.Join("; ", c.Bands.Select(b => $"{b.Filter} {b.Frequency:0} Hz Q{b.Q:0.0#} {b.Gain:+0.0;-0.0} dB")),
        DualConnectionMessage d => $"dual connection: {(d.Enabled ? "on" : "off")}",
        DualDevicesMessage d => "dual devices: " + string.Join("; ", d.Devices.Select(x => $"{x.Name} {x.Mac}{(x.IsSelf ? " (this PC)" : "")}{(x.IsConnected ? " connected" : "")}")),
        DualDeviceEventMessage d => $"dual device {d.Mac} {(d.Connected ? "connected" : "disconnected")}",
        CaseClosedMessage => "case closed",
        AckMessage a => a.Status == 0 ? "ack" : $"refused, status 0x{a.Status:X2}",
        _ => m.ToString(),
    };
}
