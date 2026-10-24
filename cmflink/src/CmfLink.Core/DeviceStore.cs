using System.ComponentModel;
using System.Runtime.CompilerServices;
using CmfLink.Protocol;
using Component = CmfLink.Protocol.Component;

namespace CmfLink.Core;

public enum LinkState
{
    /// <summary>No supported buds connected. The tray icon is hidden.</summary>
    Dormant,
    Connecting,
    /// <summary>The control channel is open.</summary>
    Connected,
    /// <summary>
    /// Connected for audio, control channel released so another device can use it. The values
    /// shown are the last ones read; the channel reopens as soon as anything needs it.
    /// </summary>
    Idle,
    /// <summary>Another device (the Nothing X app on a phone) holds the buds' control channel.</summary>
    InUse,
    /// <summary>The buds are connected for audio but the control channel didn't open.</summary>
    Unreachable,
    /// <summary>Another process on this PC (the probe) holds the control channel.</summary>
    Busy,
}

/// <summary>
/// Opens the buds' control channel on demand. The buds accept one controller at a time,
/// so CMF Link holds the channel only while it's using it.
/// </summary>
public interface IControlChannel
{
    /// <summary>The open session, opening it if needed. Null when it can't be opened right now.</summary>
    Task<BudsSession?> AcquireAsync();

    /// <summary>Something just used the channel: restart the countdown to releasing it.</summary>
    void Touch();
}

/// <summary>
/// Observable state for the UI. Every property changes on the UI thread. It never guesses:
/// anything the buds haven't reported is null and shows as "—".
/// </summary>
public sealed class DeviceStore : INotifyPropertyChanged
{
    private readonly SynchronizationContext? _ui;
    private volatile BudsSession? _session;

    public DeviceStore(SynchronizationContext? ui = null) => _ui = ui;

    /// <summary>Set by the connection controller. Actions use it to open the channel when it's released.</summary>
    public IControlChannel? Channel { get; set; }

    public event PropertyChangedEventHandler? PropertyChanged;
    /// <summary>After any battery report, on the UI thread. The tray icon and low-battery alert listen.</summary>
    public event Action? BatteryUpdated;

    public LinkState State { get; private set => Set(ref field, value); }
    public string? StatusMessage { get; private set => Set(ref field, value); }
    public DeviceProfile? Profile { get; private set => Set(ref field, value); }
    public string? DeviceName { get; private set => Set(ref field, value); }
    public string? Firmware { get; private set => Set(ref field, value); }

    public BatteryLevel? Left { get; private set => Set(ref field, value); }
    public BatteryLevel? Right { get; private set => Set(ref field, value); }
    /// <summary>
    /// Keeps its last reported level. The buds report the case only while its lid is open with a
    /// bud inside, so most reads don't include it.
    /// </summary>
    public BatteryLevel? Case { get; private set => Set(ref field, value); }
    /// <summary>When <see cref="Case"/> was reported.</summary>
    public DateTime? CaseSeenAt { get; private set => Set(ref field, value); }
    /// <summary>Whether the latest battery report included the case (the lid is open right now).</summary>
    public bool CaseIsCurrent { get; private set => Set(ref field, value); }

    /// <summary>A case level remembered from an earlier run.</summary>
    public void RestoreCase(BatteryLevel level, DateTime seenAt) => Post(() =>
    {
        if (CaseSeenAt is { } current && current >= seenAt) return;
        Case = level;
        CaseSeenAt = seenAt;
    });

    /// <summary>What the buds last confirmed.</summary>
    public NoiseMode? NoiseMode { get; private set => Set(ref field, value); }
    /// <summary>What the user picked and the buds haven't confirmed yet. The UI shows this while it's set.</summary>
    public NoiseMode? PendingNoiseMode { get; private set => Set(ref field, value); }
    public string? NoiseError { get; private set => Set(ref field, value); }

    public EqPreset? EqPreset { get; private set => Set(ref field, value); }
    public bool? UltraBassEnabled { get; private set => Set(ref field, value); }
    public int UltraBassLevel { get; private set => Set(ref field, value); } = 2;
    public bool? LowLatency { get; private set => Set(ref field, value); }
    public IReadOnlyList<Gesture> Gestures { get; private set => Set(ref field, value); } = [];
    public IReadOnlyList<EqBand> CustomEq { get; private set => Set(ref field, value); } = [];
    public bool? DualConnection { get; private set => Set(ref field, value); }
    public IReadOnlyList<DualDevice> DualDevices { get; private set => Set(ref field, value); } = [];

    /// <summary>Last error from a settings change other than noise control, shown under the controls.</summary>
    public string? SettingsError { get; private set => Set(ref field, value); }

    /// <summary>The lower of the two buds, for the tray ring. Null when neither reports.</summary>
    public int? LowestBud => (Left, Right) switch
    {
        ({ } l, { } r) => Math.Min(l.Percent, r.Percent),
        ({ } l, null) => l.Percent,
        (null, { } r) => r.Percent,
        _ => null,
    };

    /// <summary>When the buds last answered. Shown when the values are from before the channel was released.</summary>
    public DateTime? LastUpdated { get; private set => Set(ref field, value); }

    /// <summary>Something has been read since the buds connected, so the controls have values to show.</summary>
    public bool HasReadings => Left != null || Right != null || NoiseMode != null;

    // Lifecycle, driven by the connection controller. The session itself changes at once (the
    // controller reads through it right away); only the notifications go to the UI thread.

    public void SetConnecting(DeviceProfile profile, string name) => Post(() =>
    {
        Profile = profile;
        DeviceName = name;
        StatusMessage = null;
        State = LinkState.Connecting;
    });

    public void Attach(BudsSession session)
    {
        if (_session is { } old) old.MessageReceived -= OnMessage;
        _session = session;
        session.MessageReceived += OnMessage;
        Post(() =>
        {
            State = LinkState.Connected;
            StatusMessage = null;
        });
    }

    /// <summary>The channel closed. Readings are kept unless the buds are gone (<see cref="LinkState.Dormant"/>).</summary>
    public void Detach(LinkState state, string? message = null)
    {
        if (_session is { } old) old.MessageReceived -= OnMessage;
        _session = null;
        Post(() =>
        {
            State = state;
            StatusMessage = message;
            if (state == LinkState.Dormant) ClearDeviceState();
            PendingNoiseMode = null;
        });
    }

    /// <summary>The channel couldn't open (in use elsewhere, unreachable). Readings are kept.</summary>
    public void SetUnavailable(LinkState state, string message) => Post(() =>
    {
        if (_session != null) return;
        State = state;
        StatusMessage = message;
    });

    /// <summary>The open session, or one opened on demand. Null when the channel can't be had right now.</summary>
    private async Task<BudsSession?> EnsureAsync()
    {
        if (_session is { } s)
        {
            Channel?.Touch();
            return s;
        }
        return Channel is { } c ? await c.AcquireAsync() : null;
    }

    /// <summary>Reads everything the profile supports. Each reply updates the store through <see cref="OnMessage"/>.</summary>
    public async Task RefreshAsync(bool full)
    {
        if (_session is not { } s || Profile is not { } p) return;
        // Opening the panel right after connecting would read everything twice.
        if (_refreshing) return;
        if (full && DateTime.UtcNow - _lastFullRefresh < TimeSpan.FromSeconds(10)) full = false;
        _refreshing = true;
        if (full) _lastFullRefresh = DateTime.UtcNow;
        try
        {
            await ReadAllAsync(s, p, full).ConfigureAwait(false);
        }
        finally
        {
            _refreshing = false;
        }
    }

    private volatile bool _refreshing;
    private DateTime _lastFullRefresh;

    private static async Task ReadAllAsync(BudsSession s, DeviceProfile p, bool full)
    {
        var reads = new List<ushort> { Commands.GetBattery, Commands.GetNoiseMode };
        if (full)
        {
            reads.Insert(0, Commands.GetFirmware);
            if (p.EqPresets.Count > 0) reads.Add(Commands.GetEqPreset);
            if (p.SupportsUltraBass) reads.Add(Commands.GetUltraBass);
            if (p.SupportsLowLatency) reads.Add(Commands.GetLowLatency);
            if (p.TapTypes.Count > 0) reads.Add(Commands.GetGestures);
            if (p.CustomEqBands.Count > 0) reads.Add(Commands.GetCustomEq);
            if (p.SupportsDualConnection) reads.Add(Commands.GetDualConnection);
        }
        foreach (var command in reads)
        {
            try
            {
                await s.RequestAsync(command).ConfigureAwait(false);
            }
            catch (Exception e) when (e is BudsTimeoutException or BudsRefusedException)
            {
                BudsLog.Warn(e.Message);
            }
        }
    }

    public Task RefreshBatteryAsync() =>
        _session is { } s ? Swallow(s.RequestAsync(Commands.GetBattery)) : Task.CompletedTask;

    public Task RefreshDualDevicesAsync() =>
        _session is { } s && Profile?.SupportsDualConnection == true
            ? Swallow(s.RequestAsync(Commands.GetDualDevices, Commands.DualDevicesPayload(0)))
            : Task.CompletedTask;

    // Actions from the UI. Each runs on the UI thread up to its first await.

    public async Task SetNoiseModeAsync(NoiseMode mode)
    {
        if (Profile is not { } p) return;
        if (mode == NoiseMode && PendingNoiseMode == null) return;
        PendingNoiseMode = mode;
        NoiseError = null;
        try
        {
            if (await EnsureAsync() is not { } s)
            {
                NoiseError = UnavailableText;
                return;
            }
            await s.RequestAsync(Commands.SetNoiseMode, Commands.NoiseModePayload(p.NoiseValue(mode)));
            // The ack comes before the switch finishes; read back what the buds settled on.
            await Task.Delay(250);
            await s.RequestAsync(Commands.GetNoiseMode);
            if (NoiseMode != mode) NoiseError = "The buds didn't switch. Put at least one bud in your ear.";
        }
        catch (BudsRefusedException)
        {
            NoiseError = mode == Protocol.NoiseMode.NoiseCancellation
                ? "Noise cancellation needs both buds in your ears."
                : "Put a bud in your ear first.";
        }
        catch (Exception e) when (e is BudsTimeoutException or IOException or ObjectDisposedException)
        {
            NoiseError = "The buds didn't answer. Try again.";
        }
        finally
        {
            if (PendingNoiseMode == mode) PendingNoiseMode = null;
        }
    }

    public Task SetEqPresetAsync(EqPreset preset) =>
        Change(() => EqPreset = preset, Commands.SetEqPreset, Commands.EqPresetPayload(preset), Commands.GetEqPreset);

    public Task SetUltraBassAsync(bool enabled, int level) => Change(() =>
    {
        UltraBassEnabled = enabled;
        UltraBassLevel = level;
    }, Commands.SetUltraBass, Commands.UltraBassPayload(enabled, level), Commands.GetUltraBass);

    public Task SetLowLatencyAsync(bool enabled) =>
        Change(() => LowLatency = enabled, Commands.SetLowLatency, Commands.LowLatencyPayload(enabled), Commands.GetLowLatency);

    public Task SetGestureAsync(Gesture gesture) => Change(() =>
    {
        Gestures = Gestures.Select(g => g.Bud == gesture.Bud && g.Type == gesture.Type ? gesture : g).ToList();
    }, Commands.SetGesture, Commands.GesturePayload(gesture), Commands.GetGestures);

    /// <summary>Gains in band order (bass, mid, treble). Frequencies and Q come from what the buds reported.</summary>
    public Task SetCustomEqAsync(IReadOnlyList<float> gains)
    {
        var bands = (CustomEq.Count == gains.Count ? CustomEq : Profile?.CustomEqBands ?? [])
            .Select((b, i) => b with { Gain = gains[i] }).ToList();
        return Change(() => CustomEq = bands, Commands.SetCustomEq, Parsers.CustomEqPayload(bands), Commands.GetCustomEq);
    }

    public async Task FindBudAsync(Component bud, bool ring)
    {
        if (await EnsureAsync() is not { } s) return;
        await Swallow(s.RequestAsync(Commands.FindBuds, Commands.FindBudsPayload(bud, ring)));
    }

    public async Task ConnectDualDeviceAsync(DualDevice device, bool connect)
    {
        if (await EnsureAsync() is not { } s) return;
        await Swallow(s.RequestAsync(Commands.ConnectDualDevice, Commands.ConnectDualDevicePayload(device.MacBytes, connect)));
        await Task.Delay(1500);
        await RefreshDualDevicesAsync();
    }

    /// <summary>Optimistic write: show the new value, send it, then read back the truth either way.</summary>
    private async Task Change(Action optimistic, ushort command, byte[] payload, ushort readBack)
    {
        if (await EnsureAsync() is not { } s)
        {
            SettingsError = UnavailableText;
            return;
        }
        optimistic();
        SettingsError = null;
        try
        {
            await s.RequestAsync(command, payload);
        }
        catch (Exception e) when (e is BudsTimeoutException or BudsRefusedException or IOException)
        {
            SettingsError = e is BudsRefusedException ? "The buds refused that change." : "The buds didn't answer. Try again.";
        }
        await Swallow(s.RequestAsync(readBack));
    }

    private string UnavailableText => State == LinkState.InUse
        ? "Your phone has the controls right now. Leave the Nothing X app, then try again."
        : StatusMessage ?? "Couldn't reach the buds. Try again.";

    private void OnMessage(BudsMessage message) => Post(() => Apply(message));

    /// <summary>Public for tests, which feed decoded messages straight in.</summary>
    public void Apply(BudsMessage message)
    {
        var p = Profile;
        switch (message)
        {
            case BatteryMessage b:
                Left = b.Levels.TryGetValue(Component.Left, out var l) && l.Percent > 0 ? l : null;
                Right = b.Levels.TryGetValue(Component.Right, out var r) && r.Percent > 0 ? r : null;
                CaseIsCurrent = b.Levels.TryGetValue(Component.Case, out var c) && c.Percent > 0;
                if (CaseIsCurrent)
                {
                    Case = c;
                    CaseSeenAt = DateTime.Now;
                }
                if (b.Levels.TryGetValue(Component.Stereo, out var st) && st.Percent > 0) Left = Right = st;
                OnPropertyChanged(nameof(LowestBud));
                BatteryUpdated?.Invoke();
                break;
            case NoiseModeMessage n when p != null:
                NoiseMode = p.NoiseModeFromValue(n.WireValue);
                break;
            case EqPresetMessage e:
                EqPreset = Enum.IsDefined((EqPreset)e.WireValue) ? (EqPreset)e.WireValue : null;
                break;
            case FirmwareMessage f:
                Firmware = f.Version;
                break;
            case LowLatencyMessage ll:
                LowLatency = ll.Enabled;
                break;
            case UltraBassMessage u:
                UltraBassEnabled = u.Enabled;
                UltraBassLevel = u.Level;
                break;
            case GesturesMessage g:
                Gestures = g.Gestures;
                break;
            case CustomEqMessage ce:
                CustomEq = ce.Bands;
                break;
            case DualConnectionMessage d:
                DualConnection = d.Enabled;
                if (d.Enabled) _ = RefreshDualDevicesAsync();
                break;
            case DualDevicesMessage dd:
                DualDevices = dd.Devices;
                break;
            case DualDeviceEventMessage:
                _ = RefreshDualDevicesAsync();
                break;
        }
        LastUpdated = DateTime.Now;
        OnPropertyChanged(nameof(HasReadings));
    }

    private void ClearDeviceState()
    {
        Firmware = null;
        LastUpdated = null;
        // The case's last level stays, with its time: it drains slowly and is rarely reported.
        Left = Right = null;
        CaseIsCurrent = false;
        NoiseMode = PendingNoiseMode = null;
        NoiseError = SettingsError = null;
        EqPreset = null;
        UltraBassEnabled = LowLatency = DualConnection = null;
        Gestures = [];
        CustomEq = [];
        DualDevices = [];
        OnPropertyChanged(nameof(LowestBud));
        OnPropertyChanged(nameof(HasReadings));
    }

    private static async Task Swallow(Task task)
    {
        try { await task; }
        catch (Exception e) when (e is BudsTimeoutException or BudsRefusedException or IOException or ObjectDisposedException)
        {
            BudsLog.Warn(e.Message);
        }
    }

    private void Post(Action action)
    {
        if (_ui == null || SynchronizationContext.Current == _ui) action();
        else _ui.Post(_ => action(), null);
    }

    private void Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return;
        field = value;
        OnPropertyChanged(name);
    }

    private void OnPropertyChanged(string? name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
