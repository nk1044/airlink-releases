using System.ComponentModel;
using System.IO;
using System.Diagnostics;
using System.Windows;
using System.Windows.Threading;
using CmfLink.Bluetooth;
using CmfLink.Core;
using CmfLink.Protocol;
using static CmfLink.App.Native;
using Component = CmfLink.Protocol.Component;

namespace CmfLink.App;

/// <summary>
/// Wires the store, the connection lifecycle and the tray icon together. The icon shows only
/// while the buds are connected (unless the user keeps it), and the flyout exists only while open.
/// </summary>
internal sealed partial class App : Application, IFlyoutHost
{
    private const int MenuAnc = 1, MenuTransparency = 2, MenuOff = 3, MenuOpen = 10, MenuQuit = 11;

    private Mutex? _single;
    private EventWaitHandle? _openRequest;
    private DeviceStore _store = null!;
    private ConnectionController _connection = null!;
    private TrayIcon _tray = null!;
    private FlyoutWindow? _flyout;
    private DateTime _flyoutClosedAt;
    private readonly HashSet<Component> _lowAlerted = [];
    private readonly DispatcherTimer _trim = new() { Interval = TimeSpan.FromSeconds(2) };
    private StreamWriter? _log;

    public Preferences Preferences { get; private set; } = new();

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        _single = new Mutex(true, @"Local\CmfLink.App", out bool first);
        _openRequest = new EventWaitHandle(false, EventResetMode.AutoReset, @"Local\CmfLink.Open");
        if (!first)
        {
            // Already running: launching it again opens the panel there. Let that process take the foreground.
            AllowSetForegroundWindow(ASFW_ANY);
            _openRequest.Set();
            Shutdown();
            return;
        }
        ThreadPool.RegisterWaitForSingleObject(_openRequest, (_, _) => Dispatcher.BeginInvoke(ShowFlyout), null, -1, executeOnlyOnce: false);

        Preferences = Preferences.Load();
        if (e.Args.Contains("--log")) StartFileLog();
        if (!Preferences.LoginItemInitialized)
        {
            // On by default, like the system's own tray apps; the switch in More settings turns it off for good.
            try { LoginItem.IsEnabled = true; } catch (Exception) { }
            Preferences.LoginItemInitialized = true;
            Preferences.Save();
        }

        _store = new DeviceStore(SynchronizationContext.Current);
        _store.PropertyChanged += OnStoreChanged;
        _store.BatteryUpdated += CheckLowBattery;
        if (Preferences.LastCasePercent is int casePercent && Preferences.LastCaseAt is { } caseAt)
            _store.RestoreCase(new BatteryLevel(casePercent, false), caseAt);

        _tray = new TrayIcon();
        _tray.Invoked += ToggleFlyout;
        _tray.MenuRequested += ShowMenu;
        _tray.HotkeyPressed += CycleNoise;
        _tray.AppearanceChanged += UpdateTray;
        ApplyPreferences();

        _trim.Tick += (_, _) =>
        {
            _trim.Stop();
            MemoryTrim();
        };

        _connection = new ConnectionController(_store);
        _connection.Start();
        UpdateTray();

        // Started by hand rather than at login: say hello, so it's clear the app is running.
        if (!e.Args.Contains("--background"))
        {
            await Task.Delay(1200);
            if (_flyout == null) ShowFlyout();
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _tray?.Dispose();
        _log?.Dispose();
        base.OnExit(e);
    }

    // Tray icon

    private void OnStoreChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(DeviceStore.State) or nameof(DeviceStore.LowestBud) or nameof(DeviceStore.NoiseMode)
            or nameof(DeviceStore.Left) or nameof(DeviceStore.Right) or nameof(DeviceStore.DeviceName) or nameof(DeviceStore.HasReadings))
        {
            UpdateTray();
        }
        if (e.PropertyName == nameof(DeviceStore.CaseSeenAt)) RememberCase();
        if (e.PropertyName == nameof(DeviceStore.State))
        {
            if (_store.State == LinkState.Dormant) _lowAlerted.Clear();
            // Connecting and the first read load most of the code we'll ever run; give the pages back after.
            if (_flyout == null) RestartTrim(TimeSpan.FromSeconds(6));
        }
    }

    /// <summary>Saves the case level when it changes, and otherwise at most every 10 minutes (it's read every 3 s while the lid is open).</summary>
    private void RememberCase()
    {
        if (_store.Case is not { } c || _store.CaseSeenAt is not { } at) return;
        bool changed = Preferences.LastCasePercent != c.Percent;
        bool stale = Preferences.LastCaseAt is not { } saved || at - saved > TimeSpan.FromMinutes(10);
        if (!changed && !stale) return;
        Preferences.LastCasePercent = c.Percent;
        Preferences.LastCaseAt = at;
        Preferences.Save();
    }

    private void RestartTrim(TimeSpan delay)
    {
        _trim.Stop();
        _trim.Interval = delay;
        _trim.Start();
    }

    private void UpdateTray()
    {
        bool show = _store.State != LinkState.Dormant || Preferences.AlwaysShowIcon;
        if (!show)
        {
            // The buds just left: close the panel with the icon. (Not when we started dormant and said hello.)
            if (_tray.IsVisible) _flyout?.Dismiss();
            _tray.Hide();
            return;
        }
        var icon = TrayIconRenderer.Render(_tray.Dpi, _store.LowestBud, Preferences.ShowBatteryInTray, dimmed: !_store.HasReadings);
        _tray.Show(icon, Tooltip());
    }

    private string Tooltip()
    {
        var name = _store.DeviceName ?? "CMF Link";
        if (_store.State == LinkState.Dormant) return "CMF Link\nBuds not connected";
        if (!_store.HasReadings)
            return _store.State == LinkState.Connecting ? $"{name}\nConnecting…" : $"{name}\n{_store.StatusMessage}";

        string Level(BatteryLevel? b) => b is { } l ? $"{l.Percent}%{(l.Charging ? "⚡" : "")}" : "—";
        var lines = new List<string> { name, $"Left {Level(_store.Left)} · Right {Level(_store.Right)}" };
        if (_store.NoiseMode is { } m) lines.Add(Labels.Noise(m));
        if (_store.State == LinkState.InUse) lines.Add("Controls in use by your phone");
        return string.Join("\n", lines);
    }

    private void CheckLowBattery()
    {
        if (!Preferences.LowBatteryAlert) return;
        int threshold = Preferences.LowBatteryThreshold;
        foreach (var (bud, level) in new[] { (Component.Left, _store.Left), (Component.Right, _store.Right) })
        {
            if (level is not { } l) continue;
            if (l.Charging || l.Percent > threshold + 5) _lowAlerted.Remove(bud);
            else if (l.Percent <= threshold && _lowAlerted.Add(bud))
                _tray.Notify($"{_store.DeviceName ?? "Buds"} battery low", $"{bud} bud is at {l.Percent} %.");
        }
    }

    private void ShowMenu(int x, int y)
    {
        var items = new List<(int, string?, bool, bool)>();
        if (_store.State != LinkState.Dormant && _store.HasReadings && _store.Profile is { } p)
        {
            foreach (var mode in p.NoiseModes)
            {
                int id = mode switch { NoiseMode.NoiseCancellation => MenuAnc, NoiseMode.Transparency => MenuTransparency, _ => MenuOff };
                items.Add((id, Labels.Noise(mode), _store.NoiseMode == mode, true));
            }
            if (_store.State == LinkState.InUse) items.Add((0, null, false, true));
            if (_store.State == LinkState.InUse) items.Add((99, "Your phone has the controls", false, false));
            items.Add((0, null, false, true));
        }
        items.Add((MenuOpen, "Open CMF Link", false, true));
        items.Add((MenuQuit, "Quit", false, true));

        switch (_tray.ShowMenu(x, y, items))
        {
            case MenuAnc: _ = _store.SetNoiseModeAsync(NoiseMode.NoiseCancellation); break;
            case MenuTransparency: _ = _store.SetNoiseModeAsync(NoiseMode.Transparency); break;
            case MenuOff: _ = _store.SetNoiseModeAsync(NoiseMode.Off); break;
            case MenuOpen: ShowFlyout(); break;
            case MenuQuit: Quit(); break;
        }
    }

    /// <summary>Ctrl+Alt+N: the next mode in the profile's order.</summary>
    private void CycleNoise()
    {
        if (_store.State == LinkState.Dormant || _store.Profile is not { } p) return;
        var modes = p.NoiseModes;
        var current = _store.PendingNoiseMode ?? _store.NoiseMode;
        var next = modes[(current is { } c ? modes.ToList().IndexOf(c) + 1 : 0) % modes.Count];
        _ = _store.SetNoiseModeAsync(next);
        if (_flyout == null) _tray.Notify(Labels.Noise(next), _store.DeviceName ?? "");
    }

    // Flyout

    private void ToggleFlyout()
    {
        if (_flyout != null)
        {
            _flyout.Dismiss();
            return;
        }
        // The click that took focus from the flyout also lands here; don't reopen it.
        if (DateTime.UtcNow - _flyoutClosedAt < TimeSpan.FromMilliseconds(350)) return;
        ShowFlyout();
    }

    private void ShowFlyout()
    {
        if (_flyout != null)
        {
            _flyout.Activate();
            return;
        }
        _trim.Stop();
        // The panel holds the control channel while it's open and reads everything fresh;
        // closing it lets the channel go again, so the phone can have it.
        _connection.Hold(true);

        var flyout = new FlyoutWindow(_store, this);
        flyout.Closed += (_, _) =>
        {
            _connection.Hold(false);
            _flyout = null;
            _flyoutClosedAt = DateTime.UtcNow;
            _trim.Start();
        };
        _flyout = flyout;
        SetForegroundWindow(_tray.Handle);
        flyout.Show();
    }

    public RECT? AnchorRect() => _tray.IsVisible ? _tray.IconRect() : null;

    public Task RetryAsync() => _connection.RetryAsync();

    public void PreferencesChanged()
    {
        Preferences.Save();
        ApplyPreferences();
        UpdateTray();
    }

    private void ApplyPreferences()
    {
        if (!_tray.SetHotkey(Preferences.NoiseHotkey))
            BudsLog.Warn("Ctrl+Alt+N is taken by another app");
    }

    public async void Quit()
    {
        _flyout?.Dismiss();
        _tray.Hide();
        await _connection.DisposeAsync();
        Shutdown();
    }

    /// <summary>Gives back the panel's memory once it's closed. The tray-only steady state is small.</summary>
    private static void MemoryTrim()
    {
        GC.Collect(GC.MaxGeneration, GCCollectionMode.Aggressive, blocking: true, compacting: true);
        SetProcessWorkingSetSize(Process.GetCurrentProcess().Handle, -1, -1);
    }

    private void StartFileLog()
    {
        Directory.CreateDirectory(Preferences.Folder);
        _log = new StreamWriter(Path.Combine(Preferences.Folder, "cmflink.log"), append: true) { AutoFlush = true };
        BudsLog.LogFrames = true;
        BudsLog.Sink = line =>
        {
            lock (_log) _log.WriteLine(line);
        };
    }
}
