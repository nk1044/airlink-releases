using CmfLink.Core;
using CmfLink.Core.Profiles;
using CmfLink.Protocol;

namespace CmfLink.Tests;

public class FrameDecoderTests
{
    private static readonly byte[] Battery = Hex.Parse("55 60 01 07 40 05 00 02 02 02 5F 03 5F 2C A2");
    private static readonly byte[] Eq = Hex.Parse("55 60 01 50 40 01 00 04 07 FE 82");

    [Fact]
    public void ReassemblesFramesSplitAcrossReads()
    {
        var d = new FrameDecoder();
        Assert.Empty(d.Feed(Battery.AsSpan(0, 3)));
        Assert.Empty(d.Feed(Battery.AsSpan(3, 9)));
        Assert.Single(d.Feed(Battery.AsSpan(12)));
    }

    [Fact]
    public void SplitsSeveralFramesInOneRead()
    {
        var frames = new FrameDecoder().Feed([.. Battery, .. Eq]);
        Assert.Equal([0x4007, 0x4050], frames.Select(f => (int)f.Command));
    }

    [Fact]
    public void SkipsGarbageBeforeAFrame()
    {
        var d = new FrameDecoder();
        Assert.Single(d.Feed([0x00, 0x12, 0x55, 0x99, .. Eq]));
        Assert.True(d.SkippedBytes >= 2);
    }

    [Fact]
    public void RejectsACorruptedFrameAndResyncs()
    {
        var bad = (byte[])Battery.Clone();
        bad[10] ^= 0x01; // flip a payload bit
        var d = new FrameDecoder();
        var frames = d.Feed([.. bad, .. Eq]);
        Assert.Equal(0x4050, Assert.Single(frames).Command);
        Assert.Equal(1, d.CrcErrors);
    }

    [Fact]
    public void AcceptsFullFrameCrcToo()
    {
        var request = Frame.Request(Commands.SetNoiseMode, 5, Commands.NoiseModePayload(0x01)).Encode();
        Assert.True(Assert.Single(new FrameDecoder().Feed(request)).CrcValid);
    }
}

public class SessionTests
{
    /// <summary>Answers each write with whatever the test scripted for that command.</summary>
    private sealed class FakeTransport : ITransport
    {
        public readonly Dictionary<ushort, Func<Frame, byte[]?>> Replies = [];
        public readonly List<Frame> Written = [];
        public event Action<byte[]>? DataReceived;
        public event Action<Exception?>? Closed;

        public Task OpenAsync(CancellationToken ct) => Task.CompletedTask;

        public Task WriteAsync(byte[] data, CancellationToken ct)
        {
            var frame = new FrameDecoder().Feed(data).Single();
            Written.Add(frame);
            if (Replies.TryGetValue(frame.Command, out var reply) && reply(frame) is { } bytes)
                Task.Run(() => DataReceived?.Invoke(bytes));
            return Task.CompletedTask;
        }

        public void Push(byte[] bytes) => DataReceived?.Invoke(bytes);
        public ValueTask DisposeAsync()
        {
            Closed?.Invoke(null);
            return ValueTask.CompletedTask;
        }
    }

    private static byte[] Reply(Frame request, byte[] payload, int status = 0)
    {
        var f = new Frame((ushort)(0x0160 | status), Frame.ReplyCommand(request.Command), request.Sequence, payload);
        return f.Encode();
    }

    [Fact]
    public async Task MatchesTheReplyAndRaisesTheMessage()
    {
        var t = new FakeTransport();
        t.Replies[Commands.GetEqPreset] = r => Reply(r, [0x07]);
        await using var s = new BudsSession(t);
        BudsMessage? seen = null;
        s.MessageReceived += m => seen = m;

        var reply = await s.RequestAsync(Commands.GetEqPreset);
        Assert.Equal(0x4050, reply.Command);
        Assert.Equal(new EqPresetMessage(0x07), seen);
    }

    [Fact]
    public async Task SequenceNumbersCount()
    {
        var t = new FakeTransport();
        t.Replies[Commands.GetBattery] = r => Reply(r, [0x00]);
        await using var s = new BudsSession(t);
        await s.RequestAsync(Commands.GetBattery);
        await s.RequestAsync(Commands.GetBattery);
        Assert.Equal([1, 2], t.Written.Select(f => (int)f.Sequence));
    }

    [Fact]
    public async Task TimesOut()
    {
        await using var s = new BudsSession(new FakeTransport());
        await Assert.ThrowsAsync<BudsTimeoutException>(() => s.RequestAsync(Commands.GetBattery, timeout: TimeSpan.FromMilliseconds(50)));
    }

    [Fact]
    public async Task NonZeroStatusIsARefusal()
    {
        var t = new FakeTransport();
        t.Replies[Commands.SetNoiseMode] = r => Reply(r, [], status: 0x02);
        await using var s = new BudsSession(t);
        await Assert.ThrowsAsync<BudsRefusedException>(() => s.RequestAsync(Commands.SetNoiseMode, Commands.NoiseModePayload(0x01)));
    }

    [Fact]
    public async Task StoreReadsBackTheNoiseModeAfterSettingIt()
    {
        var t = new FakeTransport();
        byte mode = 0x01;
        t.Replies[Commands.SetNoiseMode] = r =>
        {
            mode = r.Payload[1];
            return Reply(r, []);
        };
        t.Replies[Commands.GetNoiseMode] = r => Reply(r, [0x02, 0x01, 0x00, 0x01, mode, 0x00]);
        await using var s = new BudsSession(t);
        var store = new DeviceStore();
        store.SetConnecting(new CmfBuds2a(), "CMF Buds 2a");
        store.Attach(s);

        await store.SetNoiseModeAsync(NoiseMode.Transparency);
        Assert.Equal(NoiseMode.Transparency, store.NoiseMode);
        Assert.Null(store.PendingNoiseMode);
        Assert.Null(store.NoiseError);
    }
}

public class DeviceStoreTests
{
    private static DeviceStore Store()
    {
        var store = new DeviceStore();
        store.SetConnecting(new CmfBuds2a(), "CMF Buds 2a");
        return store;
    }

    private static BatteryMessage Battery(params (Component, int)[] levels) =>
        new(levels.ToDictionary(l => l.Item1, l => new BatteryLevel(l.Item2, false)));

    [Fact]
    public void AMissingBudShowsAsNotReported()
    {
        var s = Store();
        s.Apply(Battery((Component.Left, 80), (Component.Right, 70)));
        s.Apply(Battery((Component.Left, 79)));
        Assert.Equal(79, s.Left?.Percent);
        Assert.Null(s.Right);
        Assert.Equal(79, s.LowestBud);
    }

    [Fact]
    public void TheCaseKeepsItsLastLevel()
    {
        var s = Store();
        s.Apply(Battery((Component.Left, 80), (Component.Case, 60)));
        s.Apply(Battery((Component.Left, 80)));
        Assert.Equal(60, s.Case?.Percent);
    }

    [Fact]
    public void TheCaseIsCurrentOnlyWhileReported()
    {
        var s = Store();
        s.Apply(Battery((Component.Left, 80), (Component.Right, 75), (Component.Case, 100)));
        Assert.True(s.CaseIsCurrent);
        var seen = s.CaseSeenAt;
        Assert.NotNull(seen);
        // Lid closed with the left bud inside: only the right bud is reported.
        s.Apply(Battery((Component.Right, 75)));
        Assert.False(s.CaseIsCurrent);
        Assert.Equal(100, s.Case?.Percent);
        Assert.Equal(seen, s.CaseSeenAt);
        Assert.Null(s.Left);
    }

    [Fact]
    public void ARememberedCaseDoesNotOverrideANewerReading()
    {
        var s = Store();
        s.Apply(Battery((Component.Case, 90)));
        s.RestoreCase(new BatteryLevel(40, false), DateTime.Now.AddDays(-1));
        Assert.Equal(90, s.Case?.Percent);
    }

    [Fact]
    public void ZeroMeansNotReported()
    {
        var s = Store();
        s.Apply(Battery((Component.Left, 0), (Component.Right, 50)));
        Assert.Null(s.Left);
    }

    [Theory]
    [InlineData(0x01, NoiseMode.NoiseCancellation)]
    [InlineData(0x03, NoiseMode.NoiseCancellation)]
    [InlineData(0x05, NoiseMode.Off)]
    [InlineData(0x07, NoiseMode.Transparency)]
    public void NoiseValues(byte value, NoiseMode mode)
    {
        var s = Store();
        s.Apply(new NoiseModeMessage(value));
        Assert.Equal(mode, s.NoiseMode);
    }

    [Fact]
    public void ProfileMatchesTheBluetoothName()
    {
        Assert.IsType<CmfBuds2a>(DeviceRegistry.Match("CMF Buds 2a"));
        Assert.IsType<CmfBuds2a>(DeviceRegistry.Match("cmf buds2a"));
        Assert.Null(DeviceRegistry.Match("CMF Buds 2"));
    }

    [Fact]
    public void GestureActionsFitTheirGesture()
    {
        var p = new CmfBuds2a();
        Assert.DoesNotContain(TapAction.VolumeUp, p.TapActions(TapType.DoubleTap));
        Assert.Contains(TapAction.VolumeUp, p.TapActions(TapType.DoubleTapAndHold));
        Assert.Contains(TapAction.NoiseControlAncTransparency, p.TapActions(TapType.TapAndHold));
    }
}
