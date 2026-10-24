using CmfLink.Core.Profiles;
using CmfLink.Protocol;

namespace CmfLink.Tests;

/// <summary>
/// Frames captured with cmflink-probe from a CMF Buds 2a (firmware 1.0.1.60) on Windows 11,
/// 2026-10-24. These pin the protocol to what real hardware sends.
/// </summary>
public class CapturedFramesTests
{
    private static Frame Decode(string hex)
    {
        var frames = new FrameDecoder().Feed(Hex.Parse(hex));
        var frame = Assert.Single(frames);
        Assert.True(frame.CrcValid);
        return frame;
    }

    [Theory]
    [InlineData(Commands.GetFirmware, 1, "55 60 01 42 C0 00 00 01 61 10")]
    [InlineData(Commands.GetBattery, 2, "55 60 01 07 C0 00 00 02 EC DE")]
    [InlineData(Commands.GetNoiseMode, 3, "55 60 01 1E C0 00 00 03 30 DC")]
    [InlineData(Commands.GetEqPreset, 4, "55 60 01 50 C0 00 00 04 19 10")]
    public void RequestsEncodeLikeTheOnesTheBudsAnswered(ushort command, byte seq, string expected)
    {
        Assert.Equal(Hex.Parse(expected), Frame.Request(command, seq).Encode());
    }

    [Fact]
    public void SetNoiseModeRequest()
    {
        var frame = Frame.Request(Commands.SetNoiseMode, 1, Commands.NoiseModePayload(0x07));
        Assert.Equal(Hex.Parse("55 60 01 0F F0 03 00 01 01 07 00 FA 77"), frame.Encode());
    }

    [Fact]
    public void RepliesCarryACrcOverThePayloadOnly()
    {
        var frame = Decode("55 60 01 42 40 08 00 01 31 2E 30 2E 31 2E 36 30 D8 66");
        Assert.Equal(0x4042, frame.Command);
        Assert.Equal(1, frame.Sequence);
        Assert.Equal(new FirmwareMessage("1.0.1.60"), Parsers.Parse(frame));
    }

    [Fact]
    public void EmptyAckHasCrcFFFF()
    {
        var frame = Decode("55 60 01 0F 70 00 00 01 FF FF");
        Assert.Equal(Frame.ReplyCommand(Commands.SetNoiseMode), frame.Command);
        Assert.Equal(new AckMessage(0x700F, 0), Parsers.Parse(frame));
    }

    [Fact]
    public void BatteryWithoutCase()
    {
        var m = Assert.IsType<BatteryMessage>(Parsers.Parse(Decode("55 60 01 07 40 05 00 02 02 02 5F 03 5F 2C A2")));
        Assert.Equal(2, m.Levels.Count);
        Assert.Equal(new BatteryLevel(95, false), m.Levels[Component.Left]);
        Assert.Equal(new BatteryLevel(95, false), m.Levels[Component.Right]);
    }

    [Fact]
    public void BatteryWithCaseOpen()
    {
        // Payload from the app's frame log; the log doesn't keep the CRC, so it's recomputed here.
        byte[] payload = Hex.Parse("03 02 55 03 55 04 64");
        ushort crc = Crc16.Compute(payload);
        byte[] raw = [.. Hex.Parse("55 60 01 07 40 07 00 02"), .. payload, (byte)crc, (byte)(crc >> 8)];
        var m = Assert.IsType<BatteryMessage>(Parsers.Parse(Decode(Hex.Format(raw))));
        Assert.Equal(new BatteryLevel(100, false), m.Levels[Component.Case]);
        Assert.Equal(85, m.Levels[Component.Left].Percent);
    }

    [Theory]
    [InlineData("55 60 01 1E 40 06 00 03 02 01 00 01 01 00 6C 69", NoiseMode.NoiseCancellation)]
    [InlineData("55 60 01 1E 40 06 00 02 02 01 00 01 07 00 6F C9", NoiseMode.Transparency)]
    [InlineData("55 60 01 1E 40 06 00 02 02 01 00 01 05 00 6E A9", NoiseMode.Off)]
    public void NoiseModeIsTheKindOneTripleNotTheFirstByte(string hex, NoiseMode expected)
    {
        var m = Assert.IsType<NoiseModeMessage>(Parsers.Parse(Decode(hex)));
        Assert.Equal(expected, new CmfBuds2a().NoiseModeFromValue(m.WireValue));
    }

    [Fact]
    public void OlderSingleTripleNoisePayloadStillParses()
    {
        Assert.Equal(new NoiseModeMessage(0x05), Parsers.ParseNoiseMode([0x01, 0x05, 0x00]));
    }

    [Fact]
    public void EqPreset()
    {
        Assert.Equal(new EqPresetMessage(0x07), Parsers.Parse(Decode("55 60 01 50 40 01 00 04 07 FE 82")));
        Assert.Equal(new EqPresetMessage(0x03), Parsers.Parse(Decode("55 60 01 50 40 01 00 02 03 FF 41")));
    }

    [Fact]
    public void UltraBassLevelIsHalved()
    {
        Assert.Equal(new UltraBassMessage(false, 3), Parsers.Parse(Decode("55 60 01 4E 40 02 00 05 00 06 81 B2")));
        Assert.Equal(new UltraBassMessage(true, 4), Parsers.Parse(Decode("55 60 01 4E 40 02 00 02 01 08 01 E6")));
        Assert.Equal([0x01, 0x08], Commands.UltraBassPayload(true, 4));
    }

    [Fact]
    public void LowLatency()
    {
        Assert.Equal(new LowLatencyMessage(false), Parsers.Parse(Decode("55 60 01 41 40 01 00 06 02 3E 81")));
        Assert.Equal(new LowLatencyMessage(true), Parsers.Parse(Decode("55 60 01 41 40 01 00 02 01 7E 80")));
    }

    [Fact]
    public void Gestures()
    {
        var m = Assert.IsType<GesturesMessage>(Parsers.Parse(Decode(
            "55 60 01 18 40 21 00 07 08 02 01 02 09 02 01 03 08 02 01 07 01 02 01 09 01 03 01 02 02 03 01 03 09 03 01 07 01 03 01 09 01 4D 0D")));
        Assert.Equal(8, m.Gestures.Count);
        Assert.Contains(new Gesture(Component.Left, 1, TapType.DoubleTap, TapAction.NextTrack), m.Gestures);
        Assert.Contains(new Gesture(Component.Right, 1, TapType.DoubleTap, TapAction.PlayPause), m.Gestures);
        Assert.Contains(new Gesture(Component.Right, 1, TapType.DoubleTapAndHold, TapAction.None), m.Gestures);
    }

    [Fact]
    public void CustomEqBandsComeFromTheBuds()
    {
        var m = Assert.IsType<CustomEqMessage>(Parsers.Parse(Decode(
            "55 60 01 44 40 2C 00 08 03 00 00 40 C0 00 00 00 00 00 00 00 0C 43 CD CC 4C 3F 01 00 00 40 40 00 00 75 44 C3 F5 28 3F 02 00 00 00 40 00 C0 5A 45 00 00 80 3F 1B A0")));
        Assert.Equal(3, m.Bands.Count);
        Assert.Equal(new EqBand(EqFilter.LowShelf, 0f, 140f, 0.8f), m.Bands[0]);
        Assert.Equal(3f, m.Bands[1].Gain);
        Assert.Equal(980f, m.Bands[1].Frequency);
        // The treble shelf sits at 3500 Hz on this firmware, not the 6900 Hz the app config lists.
        Assert.Equal(3500f, m.Bands[2].Frequency);
    }

    [Fact]
    public void CustomEqPayloadRoundTrips()
    {
        var bands = new[] { new EqBand(EqFilter.LowShelf, -2f, 140f, 0.8f), new EqBand(EqFilter.Peak, 3f, 980f, 0.7f) };
        var payload = Parsers.CustomEqPayload(bands);
        Assert.Equal(1 + 4 + 2 * 16, payload.Length);
        Assert.Equal(bands, Parsers.ParseCustomEq(payload)!.Bands);
    }

    [Fact]
    public void DualDevices()
    {
        var m = Assert.IsType<DualDevicesMessage>(Parsers.Parse(Decode(
            "55 60 01 28 40 1C 00 02 01 00 02 01 28 59 23 6D EC 3F 07 50 4F 43 4F 20 58 37 11 14 D4 24 55 99 68 02 48 50 08 1B")));
        Assert.Equal(
            [new DualDevice("28-59-23-6D-EC-3F", "POCO X7", false, true), new DualDevice("14-D4-24-55-99-68", "HP", true, true)],
            m.Devices);
    }

    [Fact]
    public void DualConnection() =>
        Assert.Equal(new DualConnectionMessage(true), Parsers.Parse(Decode("55 60 01 27 40 01 00 09 01 7E 80")));
}
