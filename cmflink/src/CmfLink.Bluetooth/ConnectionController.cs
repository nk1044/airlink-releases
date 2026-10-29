
using CmfLink.Core;
using CmfLink.Protocol;
using Windows.Devices.Bluetooth;
using Windows.Devices.Enumeration;

namespace CmfLink.Bluetooth;

/// <summary>
/// The connection lifecycle. Two things are tracked separately:
/// <list type="bullet">
/// <item>Whether supported buds are connected for audio. That's what shows the tray icon.</item>
/// <item>Whether the control channel is open. The buds accept one controller at a time, phone
/// or PC, so the channel is opened only when something needs it (the panel, an action, the
/// periodic battery read) and released after <see cref="IdleRelease"/>, leaving it free
/// for the Nothing X app.</item>
/// </list>
/// It never connects the buds on its own and never scans.
/// </summary>
public sealed class ConnectionController : IControlChannel, IAsyncDisposable
{
    /// <summary>Windows is still bringing up A2DP / HFP right after the connect event.</summary>
    private static readonly TimeSpan SettleDelay = TimeSpan.FromSeconds(2);
    private static readonly TimeSpan OpenTimeout = TimeSpan.FromSeconds(15);
    /// <summary>How long the channel stays open after its last use while nothing holds it.</summary>
    public static readonly TimeSpan IdleRelease = TimeSpan.FromSeconds(15);
    /// <summary>The 2a doesn't push battery changes to the PC, so ask now and then.</summary>
    public static readonly TimeSpan BatteryRefreshInterval = TimeSpan.FromMinutes(5);
    /// <summary>While the panel is open and the phone has the channel, try again this often.</summary>
    private static readonly TimeSpan InUseRetry = TimeSpan.FromSeconds(5);
    /// <summary>
    /// Battery reads while the panel is open. The buds report the case only while its lid is open
    /// with a bud inside, so the panel has to be looking at that moment to catch it.
    /// </summary>
    private static readonly TimeSpan LiveBatteryInterval = TimeSpan.FromSeconds(3);
    /// <summary>While the panel is closed and the phone has the channel.</summary>
    private static readonly TimeSpan InUseBackgroundRetry = TimeSpan.FromSeconds(60);

    /// <summary>
    /// What Windows reports when the buds turn the connection away because another device holds
    /// the channel: WSAEHOSTDOWN (10064), seen with Nothing X open on the phone. WSAECONNREFUSED
    /// (10061) is treated the same, since the buds are connected for audio at that point.
    /// </summary>
    private static readonly int[] InUseErrors = [unchecked((int)0x80072750), unchecked((int)0x8007274D)];

    private readonly DeviceStore _store;
    private readonly string _ownerName;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly Dictionary<string, Tracked> _tracked = [];
    private readonly Timer _idleTimer;
    private readonly Timer _pollTimer;
    private readonly Timer _retryTimer;
    private readonly Timer _liveTimer;
    private DeviceWatcher? _watcher;
    private Tracked? _buds;
    private BudsSession? _session;
    private ChannelLock? _lock;
    private Task<BudsSession?>? _opening;
    private CancellationTokenSource? _budsCts;
    private int _holds;
    private bool _timeSent;
    private bool _disposed;

    private sealed record Tracked(string Id, BluetoothDevice Device, DeviceProfile Profile);

    public ConnectionController(DeviceStore store, string ownerName = "CMF Link")
    {
        _store = store;
        _ownerName = ownerName;
        _store.Channel = this;
        _idleTimer = new Timer(_ => _ = ReleaseIfIdleAsync());
        _pollTimer = new Timer(_ => _ = PollAsync());
        _retryTimer = new Timer(_ => _ = RetryUnavailableAsync());
        _liveTimer = new Timer(_ =>
        {
            // Only on a channel the panel already has open; never opens one by itself.
            if (_holds > 0 && _session != null) _ = _store.RefreshBatteryAsync();
        });
    }

    public void Start()
    {
        _watcher = DeviceInformation.CreateWatcher(
            BluetoothDevice.GetDeviceSelectorFromPairingState(true),
            ["System.Devices.Aep.IsConnected"]);
        _watcher.Added += (sender, info) => _ = TrackAsync(info);
        _watcher.Removed += (sender, update) => _ = UntrackAsync(update.Id);
        _watcher.Start();
    }

    // Holding: the panel holds the channel while it's open, so it doesn't drop mid-use.

    /// <summary>The panel opened (true) or closed (false). Opening reads everything fresh.</summary>
    public void Hold(bool hold)
    {
        if (hold)
        {
            Interlocked.Increment(ref _holds);
            _idleTimer.Change(Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
            _liveTimer.Change(LiveBatteryInterval, LiveBatteryInterval);
            _ = AcquireAndRefreshAsync(full: true);
        }
        else
        {
            if (Interlocked.Decrement(ref _holds) <= 0)
            {
                _holds = 0;
                _liveTimer.Change(Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
                // Panel closed while the phone had the channel: keep checking, but slowly.
                if (_session == null && _store.State == LinkState.InUse)
                    _retryTimer.Change(InUseBackgroundRetry, Timeout.InfiniteTimeSpan);
                else
                    _retryTimer.Change(Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
            }
            Touch();
        }
    }

    /// <summary>"Try again" in the panel.</summary>
    public Task RetryAsync() => AcquireAndRefreshAsync(full: true);

    public void Touch()
    {
        if (_holds == 0 && !_disposed) _idleTimer.Change(IdleRelease, Timeout.InfiniteTimeSpan);
    }

    // The channel

    public async Task<BudsSession?> AcquireAsync()
    {
        if (_session is { } open)
        {
            Touch();
            return open;
        }
        Task<BudsSession?> opening;
        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (_session is { } s) return s;
            if (_buds is not { } t || _disposed) return null;
            // Concurrent callers (panel + poll + action) share one open.
            opening = _opening ??= OpenAsync(t, _budsCts?.Token ?? CancellationToken.None);
        }
        finally
        {
            _gate.Release();
        }
        try
        {
            return await opening.ConfigureAwait(false);
        }
        finally
        {
            if (_opening == opening) _opening = null;
        }
    }

    private async Task<BudsSession?> OpenAsync(Tracked t, CancellationToken ct)
    {
        await Task.Yield();
        if (!IsConnected(t)) return null;

        var channelLock = ChannelLock.TryAcquire(_ownerName, out var holder);
        if (channelLock == null)
        {
            _store.SetUnavailable(LinkState.Busy, $"{holder ?? "Another app"} is using the buds.");
            return null;
        }

        var session = new BudsSession(new RfcommTransport(t.Device, DeviceProfile.ControlService));
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(OpenTimeout);
            await session.OpenAsync(timeout.Token).ConfigureAwait(false);
        }
        catch (Exception e)
        {
            await session.DisposeAsync().ConfigureAwait(false);
            channelLock.Dispose();
            if (ct.IsCancellationRequested) return null;
            if (IsHostDown(e))
            {
                BudsLog.Info("The buds' control channel is in use by another device");
                _store.SetUnavailable(LinkState.InUse, "Your phone's Nothing X app is using the buds' controls.");
                // Nothing X keeps the channel even in the background, until it's swiped away. Check back
                // quickly while the panel is open, and now and then otherwise so the tray catches up.
                _retryTimer.Change(_holds > 0 ? InUseRetry : InUseBackgroundRetry, Timeout.InfiniteTimeSpan);
            }
            else
            {
                BudsLog.Warn($"Couldn't open the control channel: {e.Message}");
                _store.SetUnavailable(LinkState.Unreachable, "Couldn't reach the buds.");
            }
            return null;
        }

        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (_buds != t || _disposed)
            {
                await session.DisposeAsync().ConfigureAwait(false);
                channelLock.Dispose();
                return null;
            }
            _session = session;
            _lock = channelLock;
            session.Closed += error => _ = OnSessionClosedAsync(session, error);
            _store.Attach(session);
            _retryTimer.Change(Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
        }
        finally
        {
            _gate.Release();
        }
        BudsLog.Info($"Control channel open on {t.Device.Name}");
        Touch();

        if (!_timeSent)
        {
            _timeSent = true;
            try
            {
                await session.RequestAsync(Commands.SetTime, Commands.SetTimePayload(DateTimeOffset.UtcNow)).ConfigureAwait(false);
            }
            catch (Exception e) when (e is BudsTimeoutException or BudsRefusedException) { }
        }
        return session;
    }

    private static bool IsHostDown(Exception e) =>
        InUseErrors.Contains(e.HResult) || (e.InnerException is { } inner && InUseErrors.Contains(inner.HResult));

    private async Task AcquireAndRefreshAsync(bool full)
    {
        if (await AcquireAsync().ConfigureAwait(false) == null) return;
        await _store.RefreshAsync(full).ConfigureAwait(false);
        Touch();
    }

    private async Task ReleaseIfIdleAsync()
    {
        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (_holds > 0 || _session == null) return;
            BudsLog.Info("Releasing the control channel");
            await CloseSessionAsync(LinkState.Idle).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }
    }

    /// <summary>The periodic battery read: open, read, and let the idle timer release it again.</summary>
    private Task PollAsync() => _buds == null ? Task.CompletedTask : AcquireAndRefreshAsync(full: false);

    /// <summary>The channel was unavailable; try again. A full read if the panel is open, the battery otherwise.</summary>
    private async Task RetryUnavailableAsync()
    {
        if (_session != null || _buds == null) return;
        await AcquireAndRefreshAsync(full: _holds > 0).ConfigureAwait(false);
    }

    /// <summary>The channel dropped by itself (out of range, sleep, the buds closed it).</summary>
    private async Task OnSessionClosedAsync(BudsSession session, Exception? error)
    {
        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (_session != session) return;
            BudsLog.Warn($"Control channel closed{(error != null ? $": {error.Message}" : "")}");
            await CloseSessionAsync(LinkState.Idle).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }
        // If the panel is open, get it back.
        if (_holds > 0 && _buds != null) _retryTimer.Change(TimeSpan.FromSeconds(1), Timeout.InfiniteTimeSpan);
    }

    /// <summary>Call inside the gate.</summary>
    private async Task CloseSessionAsync(LinkState state)
    {
        _idleTimer.Change(Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
        var session = _session;
        _session = null;
        _store.Detach(state);
        if (session != null) await session.DisposeAsync().ConfigureAwait(false);
        _lock?.Dispose();
        _lock = null;
    }

    // Buds coming and going (audio connection)

    private async Task TrackAsync(DeviceInformation info)
    {
        if (DeviceRegistry.Match(info.Name) is not { } profile) return;
        var device = await BluetoothDevice.FromIdAsync(info.Id);
        if (device == null) return;

        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (_tracked.ContainsKey(info.Id) || _disposed)
            {
                device.Dispose();
                return;
            }
            var t = new Tracked(info.Id, device, profile);
            _tracked[info.Id] = t;
            device.ConnectionStatusChanged += (_, _) => _ = OnConnectionChangedAsync(t);
            BudsLog.Info($"Tracking {device.Name} ({profile.Id}), {device.ConnectionStatus}");
            if (_buds == null && IsConnected(t)) BudsArrived(t, settle: false);
        }
        finally
        {
            _gate.Release();
        }
    }

    private async Task UntrackAsync(string id)
    {
        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (!_tracked.Remove(id, out var t)) return;
            if (_buds == t) await BudsLeftAsync().ConfigureAwait(false);
            t.Device.Dispose();
        }
        finally
        {
            _gate.Release();
        }
    }

    private async Task OnConnectionChangedAsync(Tracked t)
    {
        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            bool connected = IsConnected(t);
            BudsLog.Info($"{t.Device.Name} {(connected ? "connected" : "disconnected")}");
            if (connected && _buds == null) BudsArrived(t, settle: true);
            else if (!connected && _buds == t) await BudsLeftAsync().ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }
    }

    /// <summary>Call inside the gate.</summary>
    private void BudsArrived(Tracked t, bool settle)
    {
        _buds = t;
        _timeSent = false;
        _budsCts = new CancellationTokenSource();
        _store.SetConnecting(t.Profile, t.Device.Name);
        _pollTimer.Change(BatteryRefreshInterval, BatteryRefreshInterval);
        var ct = _budsCts.Token;
        _ = Task.Run(async () =>
        {
            // One full read on arrival, for the tray icon and tooltip. Then the channel is let go.
            if (settle) await Task.Delay(SettleDelay, ct).ConfigureAwait(false);
            await AcquireAndRefreshAsync(full: true).ConfigureAwait(false);
        }, ct);
    }

    /// <summary>Call inside the gate.</summary>
    private async Task BudsLeftAsync()
    {
        _budsCts?.Cancel();
        _budsCts = null;
        _pollTimer.Change(Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
        _retryTimer.Change(Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
        _buds = null;
        await CloseSessionAsync(LinkState.Dormant).ConfigureAwait(false);
    }

    private static bool IsConnected(Tracked t) => t.Device.ConnectionStatus == BluetoothConnectionStatus.Connected;

    public async ValueTask DisposeAsync()
    {
        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            _disposed = true;
            if (_watcher is { Status: DeviceWatcherStatus.Started or DeviceWatcherStatus.EnumerationCompleted }) _watcher.Stop();
            _idleTimer.Dispose();
            _pollTimer.Dispose();
            _retryTimer.Dispose();
            _liveTimer.Dispose();
            if (_buds != null) await BudsLeftAsync().ConfigureAwait(false);
            foreach (var t in _tracked.Values) t.Device.Dispose();
            _tracked.Clear();
        }
        finally
        {
            _gate.Release();
        }
    }
}
