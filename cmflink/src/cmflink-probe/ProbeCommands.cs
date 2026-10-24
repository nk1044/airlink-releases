using System.Globalization;
using CmfLink.Core;
using CmfLink.Protocol;

namespace CmfLink.Probe;

internal sealed class ProbeCommands(BudsSession session, DeviceProfile profile)
{
    public async Task<int> RunAsync(string[] args)
    {
        string? arg1 = args.Length > 1 ? args[1].ToLowerInvariant() : null;
        string? arg2 = args.Length > 2 ? args[2].ToLowerInvariant() : null;

        switch (args[0])
        {
            case "info":
                foreach (var cmd in new[]
                {
                    Commands.GetFirmware, Commands.GetBattery, Commands.GetNoiseMode, Commands.GetEqPreset,
                    Commands.GetUltraBass, Commands.GetLowLatency, Commands.GetGestures, Commands.GetCustomEq,
                    Commands.GetDualConnection,
                })
                {
                    await TryAsync(cmd);
                }
                return 0;

            case "noise":
                if (arg1 == null) return await TryAsync(Commands.GetNoiseMode);
                byte value = arg1 switch
                {
                    "anc" => profile.NoiseValue(NoiseMode.NoiseCancellation),
                    "transparency" => profile.NoiseValue(NoiseMode.Transparency),
                    "off" => profile.NoiseValue(NoiseMode.Off),
                    _ => ParseByte(arg1),
                };
                await TryAsync(Commands.SetNoiseMode, Commands.NoiseModePayload(value));
                await Task.Delay(500);
                return await TryAsync(Commands.GetNoiseMode);

            case "eq":
                if (arg1 == null) return await TryAsync(Commands.GetEqPreset);
                var preset = arg1 switch
                {
                    "dirac" => EqPreset.Dirac,
                    "pop" => EqPreset.Pop,
                    "rock" => EqPreset.Rock,
                    "classical" => EqPreset.Classical,
                    "electronic" => EqPreset.Electronic,
                    "vocals" => EqPreset.EnhanceVocals,
                    "custom" => EqPreset.Custom,
                    _ => (EqPreset)ParseByte(arg1),
                };
                await TryAsync(Commands.SetEqPreset, Commands.EqPresetPayload(preset));
                return await TryAsync(Commands.GetEqPreset);

            case "bass":
                if (arg1 == null) return await TryAsync(Commands.GetUltraBass);
                bool on = arg1 == "on";
                int level = arg2 != null ? int.Parse(arg2, CultureInfo.InvariantCulture) : 2;
                await TryAsync(Commands.SetUltraBass, Commands.UltraBassPayload(on, level));
                return await TryAsync(Commands.GetUltraBass);

            case "latency":
                if (arg1 == null) return await TryAsync(Commands.GetLowLatency);
                await TryAsync(Commands.SetLowLatency, Commands.LowLatencyPayload(arg1 == "on"));
                return await TryAsync(Commands.GetLowLatency);

            case "find":
                if (arg1 is not ("left" or "right") || arg2 is not ("on" or "off")) return Fail("find <left|right> <on|off>");
                return await TryAsync(Commands.FindBuds, Commands.FindBudsPayload(arg1 == "left" ? Component.Left : Component.Right, arg2 == "on"));

            case "dual":
                await TryAsync(Commands.GetDualConnection);
                return await TryAsync(Commands.GetDualDevices, Commands.DualDevicesPayload(0));

            case "customeq":
                if (arg1 == null) return await TryAsync(Commands.GetCustomEq);
                if (args.Length < 4) return Fail("customeq <bass> <mid> <treble>");
                var gains = args[1..4].Select(s => float.Parse(s, CultureInfo.InvariantCulture)).ToArray();
                var bands = profile.CustomEqBands.Select((b, i) => b with { Gain = gains[i] }).ToList();
                await TryAsync(Commands.SetCustomEq, Parsers.CustomEqPayload(bands));
                return await TryAsync(Commands.GetCustomEq);

            case "monitor":
                int seconds = arg1 != null ? int.Parse(arg1, CultureInfo.InvariantCulture) : 30;
                if (arg2 == "init")
                {
                    // What the official app sends right after connecting.
                    await TryAsync(Commands.GetBattery);
                    await TryAsync(Commands.GetNoiseMode);
                    await TryAsync(Commands.SetTime, Commands.SetTimePayload(DateTimeOffset.UtcNow));
                }
                Console.WriteLine($"-- listening for {seconds} s: long-press a bud, take one out, open the case…");
                await Task.Delay(TimeSpan.FromSeconds(seconds));
                return 0;

            case "watch":
                // Battery every 2 s, for following what the buds report while you move them around.
                int watchFor = arg1 != null ? int.Parse(arg1, CultureInfo.InvariantCulture) : 60;
                var until = DateTime.Now.AddSeconds(watchFor);
                while (DateTime.Now < until)
                {
                    await TryAsync(Commands.GetBattery);
                    await Task.Delay(2000);
                }
                return 0;

            case "raw":
                if (arg1 == null) return Fail("raw <cmd hex> [payload hex]");
                ushort command = Convert.ToUInt16(arg1.Replace("0x", ""), 16);
                byte[] payload = args.Length > 2 ? Hex.Parse(string.Join("", args[2..])) : [];
                return await TryAsync(command, payload);

            default:
                return Fail($"unknown command '{args[0]}'");
        }
    }

    private async Task<int> TryAsync(ushort command, byte[]? payload = null)
    {
        var started = DateTime.Now;
        try
        {
            await session.RequestAsync(command, payload);
            Console.WriteLine($"        ({(DateTime.Now - started).TotalMilliseconds:0} ms)");
            return 0;
        }
        catch (BudsTimeoutException)
        {
            Console.WriteLine($"        no reply to {Commands.Name(command)} within {BudsSession.DefaultTimeout.TotalSeconds} s");
            return 4;
        }
        catch (BudsRefusedException e)
        {
            Console.WriteLine($"        {e.Message}");
            return 5;
        }
    }

    private static byte ParseByte(string s) =>
        s.StartsWith("0x") ? Convert.ToByte(s[2..], 16) : byte.Parse(s, CultureInfo.InvariantCulture);

    private static int Fail(string message)
    {
        Console.Error.WriteLine(message);
        return 64;
    }
}
