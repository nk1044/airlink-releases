using System.Buffers.Binary;
using System.Text;

namespace CmfLink.Protocol;

/// <summary>What a reply or push means, decoded from its payload.</summary>
public abstract record BudsMessage;

/// <summary>Each report is a full snapshot: a component that's missing isn't being reported now.</summary>
public sealed record BatteryMessage(IReadOnlyDictionary<Component, BatteryLevel> Levels) : BudsMessage;
/// <summary>The raw wire value; the device profile maps it to a <see cref="NoiseMode"/>.</summary>
public sealed record NoiseModeMessage(byte WireValue) : BudsMessage;
public sealed record EqPresetMessage(byte WireValue) : BudsMessage;
public sealed record FirmwareMessage(string Version) : BudsMessage;
public sealed record LowLatencyMessage(bool Enabled) : BudsMessage;
public sealed record UltraBassMessage(bool Enabled, int Level) : BudsMessage;
public sealed record GesturesMessage(IReadOnlyList<Gesture> Gestures) : BudsMessage;
public sealed record CustomEqMessage(IReadOnlyList<EqBand> Bands) : BudsMessage;
public sealed record DualConnectionMessage(bool Enabled) : BudsMessage;
public sealed record DualDevicesMessage(IReadOnlyList<DualDevice> Devices) : BudsMessage;
public sealed record DualDeviceEventMessage(string Mac, bool Connected) : BudsMessage;
public sealed record CaseClosedMessage : BudsMessage;
/// <summary>The empty reply to a write, or any reply with a non-zero status.</summary>
public sealed record AckMessage(ushort Command, int Status) : BudsMessage;

public enum EqFilter : byte { LowShelf = 0, Peak = 1, HighShelf = 2 }
public readonly record struct EqBand(EqFilter Filter, float Gain, float Frequency, float Q);

public static class Parsers
{
    /// <summary>Returns null for frames the app doesn't use; the caller logs those.</summary>
    public static BudsMessage? Parse(Frame frame)
    {
        if (frame.Status != 0) return new AckMessage(frame.Command, frame.Status);
        var p = frame.Payload;
        ushort id = Commands.Normalize(frame.Command);
        return id switch
        {
            Commands.GetBattery or Commands.PushBattery => ParseBattery(p),
            Commands.GetNoiseMode or Commands.PushNoiseMode => ParseNoiseMode(p),
            Commands.GetEqPreset or Commands.SetEqPreset when p.Length >= 1 => new EqPresetMessage(p[0]),
            Commands.GetFirmware => new FirmwareMessage(Encoding.ASCII.GetString(p).TrimEnd('\0')),
            Commands.GetLowLatency when p.Length >= 1 => new LowLatencyMessage(p[0] == 0x01),
            Commands.GetUltraBass or Commands.SetUltraBass when p.Length >= 1 => ParseUltraBass(p),
            Commands.GetGestures => ParseGestures(p),
            Commands.GetCustomEq => ParseCustomEq(p),
            Commands.GetDualConnection when p.Length >= 1 => new DualConnectionMessage(p[0] == 0x01),
            Commands.GetDualDevices => ParseDualDevices(p),
            Commands.PushDualDevice => ParseDualEvent(p),
            Commands.PushCaseClosed => new CaseClosedMessage(),
            _ when (frame.Command & 0x8000) == 0 && p.Length == 0 => new AckMessage(frame.Command, 0),
            _ => null,
        };
    }

    /// <summary><c>n, (id, raw)×n</c>. Level = raw &amp; 0x7F, charging = raw &amp; 0x80.</summary>
    public static BatteryMessage? ParseBattery(byte[] p)
    {
        if (p.Length < 1) return null;
        var levels = new Dictionary<Component, BatteryLevel>();
        int count = p[0];
        for (int i = 0; i < count && 2 + 2 * i < p.Length; i++)
        {
            var id = (Component)p[1 + 2 * i];
            byte raw = p[2 + 2 * i];
            if (!Enum.IsDefined(id)) continue;
            levels[id] = new BatteryLevel(raw & 0x7F, (raw & 0x80) != 0);
        }
        return new BatteryMessage(levels);
    }

    /// <summary>
    /// A list of <c>(kind, value, 00)</c> triples; kind 01 carries the mode. Older models send that
    /// one triple, the Buds 2a sends <c>02 01 00 01 &lt;mode&gt; 00</c>.
    /// </summary>
    public static NoiseModeMessage? ParseNoiseMode(byte[] p)
    {
        for (int o = 0; o + 2 < p.Length; o += 3)
        {
            if (p[o] == 0x01) return new NoiseModeMessage(p[o + 1]);
        }
        return null;
    }

    public static UltraBassMessage ParseUltraBass(byte[] p)
    {
        int level = p.Length >= 2 ? Math.Clamp((p[1] + 1) / 2, UltraBass.MinLevel, UltraBass.MaxLevel) : UltraBass.MinLevel;
        return new UltraBassMessage(p[0] == 0x01, level);
    }

    /// <summary><c>n, (bud, button, type, action)×n</c>.</summary>
    public static GesturesMessage ParseGestures(byte[] p)
    {
        var list = new List<Gesture>();
        if (p.Length >= 1)
        {
            for (int i = 0; i < p[0] && 4 + 4 * i < p.Length; i++)
            {
                int o = 1 + 4 * i;
                list.Add(new Gesture((Component)p[o], p[o + 1], (TapType)p[o + 2], (TapAction)p[o + 3]));
            }
        }
        return new GesturesMessage(list);
    }

    /// <summary><c>n, total gain f32, (filter u8, gain f32, freq f32, q f32)×n, …</c>, floats little-endian.</summary>
    public static CustomEqMessage? ParseCustomEq(byte[] p)
    {
        if (p.Length < 5) return null;
        var bands = new List<EqBand>();
        for (int i = 0; i < p[0]; i++)
        {
            int o = 5 + 13 * i;
            if (o + 13 > p.Length) break;
            bands.Add(new EqBand((EqFilter)p[o], F(p, o + 1), F(p, o + 5), F(p, o + 9)));
        }
        return new CustomEqMessage(bands);
    }

    public static byte[] CustomEqPayload(IReadOnlyList<EqBand> bands)
    {
        float max = Math.Max(0, bands.Count == 0 ? 0 : bands.Max(b => b.Gain));
        var buf = new byte[1 + 4 + bands.Count * 16];
        buf[0] = (byte)bands.Count;
        BinaryPrimitives.WriteSingleLittleEndian(buf.AsSpan(1), -max);
        int o = 5;
        foreach (var b in bands)
        {
            buf[o++] = (byte)b.Filter;
            BinaryPrimitives.WriteSingleLittleEndian(buf.AsSpan(o), b.Gain); o += 4;
            BinaryPrimitives.WriteSingleLittleEndian(buf.AsSpan(o), b.Frequency); o += 4;
            BinaryPrimitives.WriteSingleLittleEndian(buf.AsSpan(o), b.Q); o += 4;
        }
        // Three zero bytes per band follow the band list.
        return buf;
    }

    /// <summary><c>?, ?, n, (flags, mac×6, nameLen, name)×n</c>. Flags: high nibble = this PC, low nibble = connected.</summary>
    public static DualDevicesMessage ParseDualDevices(byte[] p)
    {
        var list = new List<DualDevice>();
        if (p.Length >= 3)
        {
            int o = 3;
            for (int i = 0; i < p[2] && o + 8 <= p.Length; i++)
            {
                byte flags = p[o];
                string mac = string.Join("-", p[(o + 1)..(o + 7)].Select(b => b.ToString("X2")));
                int nameLength = Math.Min(p[o + 7] & 0x7F, p.Length - o - 8);
                string name = Encoding.UTF8.GetString(p, o + 8, nameLength);
                list.Add(new DualDevice(mac, name, (flags & 0xF0) != 0, (flags & 0x0F) != 0));
                o += 8 + nameLength;
            }
        }
        return new DualDevicesMessage(list);
    }

    public static DualDeviceEventMessage? ParseDualEvent(byte[] p) =>
        p.Length >= 7 ? new DualDeviceEventMessage(string.Join("-", p[1..7].Select(b => b.ToString("X2"))), p[0] == 0x01) : null;

    private static float F(byte[] p, int o) => BinaryPrimitives.ReadSingleLittleEndian(p.AsSpan(o));
}
