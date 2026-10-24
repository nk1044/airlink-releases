namespace CmfLink.Protocol;

/// <summary>
/// Command ids (PROTOCOL.md §4). Requests set bit 15: 0xC0xx reads, 0xF0xx writes.
/// The reply clears bit 15 (0xC007 → 0x4007). Unsolicited pushes from the buds are 0xE0xx.
/// </summary>
public static class Commands
{
    public const ushort GetBattery = 0xC007;
    public const ushort GetGestures = 0xC018;
    public const ushort GetNoiseMode = 0xC01E;
    public const ushort GetDualConnection = 0xC027;
    public const ushort GetDualDevices = 0xC028;
    public const ushort GetLowLatency = 0xC041;
    public const ushort GetFirmware = 0xC042;
    public const ushort GetCustomEq = 0xC044;
    public const ushort GetUltraBass = 0xC04E;
    public const ushort GetEqPreset = 0xC050;

    public const ushort FindBuds = 0xF002;
    public const ushort SetGesture = 0xF003;
    public const ushort SetTime = 0xF00A;
    public const ushort SetNoiseMode = 0xF00F;
    public const ushort SetDualConnection = 0xF01A;
    public const ushort ConnectDualDevice = 0xF01B;
    public const ushort SetEqPreset = 0xF01D;
    public const ushort SetLowLatency = 0xF040;
    public const ushort SetCustomEq = 0xF041;
    public const ushort SetUltraBass = 0xF051;

    public const ushort PushBattery = 0xE001;
    public const ushort PushCaseClosed = 0xE002;
    public const ushort PushNoiseMode = 0xE003;
    public const ushort PushDualDevice = 0xE00E;

    /// <summary>Maps any incoming command onto the request/push namespace: 0x4007 → 0xC007, 0x6003 → 0xE003.</summary>
    public static ushort Normalize(ushort command) => (ushort)(command | 0x8000);

    // Request payloads. The session wraps them in a frame with the next sequence number.

    public static byte[] NoiseModePayload(byte wireValue) => [0x01, wireValue, 0x00];
    public static byte[] EqPresetPayload(EqPreset preset) => [(byte)preset, 0x00];
    public static byte[] LowLatencyPayload(bool enabled) => [(byte)(enabled ? 0x01 : 0x02)];

    /// <summary>Level 1–5 goes on the wire doubled.</summary>
    public static byte[] UltraBassPayload(bool enabled, int level) =>
        [(byte)(enabled ? 0x01 : 0x00), (byte)(Math.Clamp(level, UltraBass.MinLevel, UltraBass.MaxLevel) * 2)];

    public static byte[] FindBudsPayload(Component bud, bool ring) => [(byte)bud, (byte)(ring ? 0x01 : 0x00)];
    public static byte[] GesturePayload(Gesture g) => [0x01, (byte)g.Bud, g.Button, (byte)g.Type, (byte)g.Action];
    /// <summary>Unix seconds, big-endian, as the official app sends it on connect.</summary>
    public static byte[] SetTimePayload(DateTimeOffset now)
    {
        uint s = (uint)now.ToUnixTimeSeconds();
        return [(byte)(s >> 24), (byte)(s >> 16), (byte)(s >> 8), (byte)s];
    }

    public static byte[] DualConnectionPayload(bool enabled) => [(byte)(enabled ? 0x01 : 0x00)];
    public static byte[] DualDevicesPayload(int startIndex) => [(byte)startIndex];

    public static byte[] ConnectDualDevicePayload(byte[] mac, bool connect)
    {
        if (mac.Length != 6) throw new ArgumentException("MAC must be 6 bytes", nameof(mac));
        return [(byte)(connect ? 0x01 : 0x00), .. mac];
    }

    public static string Name(ushort command) => Normalize(command) switch
    {
        GetBattery => "battery",
        GetGestures => "gestures",
        GetNoiseMode => "noise mode",
        GetDualConnection => "dual connection",
        GetDualDevices => "dual devices",
        GetLowLatency => "low latency",
        GetFirmware => "firmware",
        GetCustomEq => "custom EQ",
        GetUltraBass => "ultra bass",
        GetEqPreset => "EQ preset",
        FindBuds => "find buds",
        SetGesture => "set gesture",
        SetTime => "set time",
        SetNoiseMode => "set noise mode",
        SetDualConnection => "set dual connection",
        ConnectDualDevice => "connect dual device",
        SetEqPreset => "set EQ preset",
        SetLowLatency => "set low latency",
        SetCustomEq => "set custom EQ",
        SetUltraBass => "set ultra bass",
        PushBattery => "battery push",
        PushCaseClosed => "case closed push",
        PushNoiseMode => "noise mode push",
        PushDualDevice => "dual device push",
        _ => $"0x{command:X4}",
    };
}
