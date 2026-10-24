using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Interop;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using CmfLink.Core;
using CmfLink.Protocol;
using static CmfLink.App.Native;
using Component = CmfLink.Protocol.Component;

namespace CmfLink.App;

internal interface IFlyoutHost
{
    Preferences Preferences { get; }
    void PreferencesChanged();
    Task RetryAsync();
    void Quit();
    /// <summary>Where to anchor the flyout, in physical pixels: the tray icon, or null for the cursor.</summary>
    RECT? AnchorRect();
}

/// <summary>
/// The panel that drops up from the tray. It exists only while open: the host creates one per
/// click and it closes itself when it loses focus. No Bluetooth logic here; it reads and calls DeviceStore.
/// </summary>
internal sealed partial class FlyoutWindow : Window
{
    private readonly DeviceStore _store;
    private readonly IFlyoutHost _host;
    private readonly List<(EqPreset Preset, ToggleButton Button)> _eqButtons = [];
    private readonly List<Slider> _eqSliders = [];
    private readonly List<(Component Bud, TapType Type, ComboBox Box)> _gestureBoxes = [];
    private readonly DispatcherTimer _bassDebounce = new() { Interval = TimeSpan.FromMilliseconds(350) };
    private readonly DispatcherTimer _eqDebounce = new() { Interval = TimeSpan.FromMilliseconds(400) };
    private DeviceProfile? _builtFor;
    private bool _updating;
    private bool _morePage;
    private bool _positioned;

    public FlyoutWindow(DeviceStore store, IFlyoutHost host)
    {
        _store = store;
        _host = host;
        InitializeComponent();
        Opacity = 0;
        Left = -32000;

        NoiseSegments.SegmentClicked += OnNoiseSegment;
        UltraBassSlider.ValueChanged += (_, _) =>
        {
            if (_updating) return;
            UltraBassLevelText.Text = $"Level {(int)UltraBassSlider.Value}";
            _bassDebounce.Stop();
            _bassDebounce.Start();
        };
        _bassDebounce.Tick += (_, _) =>
        {
            _bassDebounce.Stop();
            _ = _store.SetUltraBassAsync(UltraBassSwitch.IsChecked == true, (int)UltraBassSlider.Value);
        };
        _eqDebounce.Tick += (_, _) =>
        {
            _eqDebounce.Stop();
            _ = _store.SetCustomEqAsync(_eqSliders.Select(s => (float)s.Value).ToList());
        };

        BuildAppSettings();
        _store.PropertyChanged += OnStoreChanged;
        Render();

        SourceInitialized += (_, _) => Theme.ApplyFlyoutBackdrop(this);
        Loaded += (_, _) => Position();
        SizeChanged += (_, _) => Position();
        Deactivated += (_, _) => Dismiss();
        PreviewKeyDown += (_, e) =>
        {
            if (e.Key == System.Windows.Input.Key.Escape) Dismiss();
        };
    }

    private bool _closing;

    /// <summary>Close, unless already closing (closing deactivates the window, which would close it again).</summary>
    public void Dismiss()
    {
        if (_closing) return;
        _closing = true;
        Close();
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        _closing = true;
        base.OnClosing(e);
    }

    protected override void OnClosed(EventArgs e)
    {
        _store.PropertyChanged -= OnStoreChanged;
        _bassDebounce.Stop();
        _eqDebounce.Stop();
        if (RingLeft.IsChecked == true) _ = _store.FindBudAsync(Component.Left, false);
        if (RingRight.IsChecked == true) _ = _store.FindBudAsync(Component.Right, false);
        base.OnClosed(e);
    }

    private void OnStoreChanged(object? sender, PropertyChangedEventArgs e) => Render();

    // Rendering: one pass over the store, guarded so control events don't echo back as writes.

    private void Render()
    {
        _updating = true;
        try
        {
            var s = _store;
            var p = s.Profile;
            if (p != null && p != _builtFor) Build(p);

            TitleText.Text = _morePage ? "More settings" : s.DeviceName ?? "CMF Link";
            // The controls show whenever there are readings, live or last known. They work while the
            // channel is open or free (Idle reopens it on demand), not while something else has it.
            bool connected = s.State != LinkState.Dormant && s.HasReadings;
            bool usable = s.State is LinkState.Connected or LinkState.Idle;
            bool problem = s.State is LinkState.InUse or LinkState.Unreachable or LinkState.Busy;
            BackButton.Visibility = _morePage ? Visibility.Visible : Visibility.Collapsed;
            MoreButton.Visibility = _morePage ? Visibility.Collapsed : Visibility.Visible;

            StatusPanel.Visibility = (!connected || problem) && !_morePage ? Visibility.Visible : Visibility.Collapsed;
            string since = s.LastUpdated is { } at && connected ? $" Showing what the buds reported at {at:t}." : "";
            StatusText.Text = s.State switch
            {
                LinkState.Connecting => "Connecting…",
                LinkState.InUse => "Your phone's Nothing X app has the buds' controls. They allow one at a time. "
                    + "Leave the app on your phone and CMF Link picks them up within a few seconds." + since,
                LinkState.Unreachable => (s.StatusMessage ?? "Couldn't reach the buds.") + since,
                LinkState.Busy => (s.StatusMessage ?? "Another app is using the buds.") + since,
                LinkState.Dormant => "Connect your buds to this PC for audio. CMF Link shows up in the tray as soon as they connect.",
                _ => "Reading the buds…",
            };
            RetryButton.Visibility = problem ? Visibility.Visible : Visibility.Collapsed;
            MainPage.Visibility = connected && !_morePage ? Visibility.Visible : Visibility.Collapsed;
            MainPage.IsEnabled = usable;
            MainPage.Opacity = usable ? 1 : 0.5;
            MorePage.Visibility = _morePage ? Visibility.Visible : Visibility.Collapsed;
            GestureSection.Visibility = connected && _gestureBoxes.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            DualSection.Visibility = connected && s.DualConnection == true ? Visibility.Visible : Visibility.Collapsed;
            GestureSection.IsEnabled = DualSection.IsEnabled = usable;

            SetRing(LeftRing, s.Left);
            SetRing(RightRing, s.Right);
            SetRing(CaseRing, s.Case);
            // The buds report the case only while its lid is open with a bud inside; otherwise show the last level and when.
            bool caseOld = s.Case != null && !s.CaseIsCurrent;
            CaseRing.Opacity = caseOld ? 0.55 : 1;
            CaseAgeText.Visibility = caseOld && s.CaseSeenAt != null ? Visibility.Visible : Visibility.Collapsed;
            if (s.CaseSeenAt is { } seen)
                CaseAgeText.Text = seen.Date == DateTime.Today ? $"at {seen:t}" : $"on {seen:MMM d}";
            CaseRing.ToolTip = s.Case == null
                ? "The buds report the case only while its lid is open with a bud inside. Open it with this panel open."
                : caseOld ? "Last reported level. Open the lid with a bud inside to update it." : null;

            if (p != null)
            {
                var shown = s.PendingNoiseMode ?? s.NoiseMode;
                NoiseSegments.Select(shown is { } m ? p.NoiseModes.ToList().IndexOf(m) : -1, s.PendingNoiseMode != null);
            }
            ShowError(NoiseErrorText, s.NoiseError);
            ShowError(SettingsErrorText, s.SettingsError);

            foreach (var (preset, button) in _eqButtons) button.IsChecked = s.EqPreset == preset;
            CustomEqCard.Visibility = s.EqPreset == EqPreset.Custom && _eqSliders.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            for (int i = 0; i < _eqSliders.Count && i < s.CustomEq.Count; i++)
            {
                if (!_eqSliders[i].IsMouseCaptureWithin) _eqSliders[i].Value = s.CustomEq[i].Gain;
            }

            UltraBassSwitch.IsChecked = s.UltraBassEnabled == true;
            UltraBassSwitch.IsEnabled = s.UltraBassEnabled != null;
            UltraBassSlider.Visibility = s.UltraBassEnabled == true ? Visibility.Visible : Visibility.Collapsed;
            if (!UltraBassSlider.IsMouseCaptureWithin && !_bassDebounce.IsEnabled) UltraBassSlider.Value = s.UltraBassLevel;
            UltraBassLevelText.Text = s.UltraBassEnabled == true ? $"Level {(int)UltraBassSlider.Value}" : "Deeper bass";
            LatencySwitch.IsChecked = s.LowLatency == true;
            LatencySwitch.IsEnabled = s.LowLatency != null;

            foreach (var (bud, type, box) in _gestureBoxes)
            {
                var found = s.Gestures.Where(x => x.Bud == bud && x.Type == type).ToList();
                box.IsEnabled = found.Count > 0;
                if (found.Count == 0) continue;
                var g = found[0];
                if (!box.Items.OfType<ComboBoxItem>().Any(i => (TapAction)i.Tag == g.Action))
                    box.Items.Add(new ComboBoxItem { Content = Labels.Action(g.Action), Tag = g.Action });
                box.SelectedItem = box.Items.OfType<ComboBoxItem>().First(i => (TapAction)i.Tag == g.Action);
            }

            RenderDualList();
            FooterText.Text = string.Join(" · ", new[]
            {
                s.Firmware is { } fw ? $"Firmware {fw}" : null,
                $"CMF Link {typeof(FlyoutWindow).Assembly.GetName().Version?.ToString(3)}",
            }.Where(x => x != null));
        }
        finally
        {
            _updating = false;
        }
    }

    private static void SetRing(Controls.BatteryRing ring, BatteryLevel? level)
    {
        ring.Percent = level?.Percent;
        ring.Charging = level?.Charging == true;
        System.Windows.Automation.AutomationProperties.SetName(ring, level is { } l ? $"{l.Percent} percent{(l.Charging ? ", charging" : "")}" : "Not reported");
    }

    private static void ShowError(TextBlock block, string? text)
    {
        block.Text = text ?? "";
        block.Visibility = text == null ? Visibility.Collapsed : Visibility.Visible;
    }

    /// <summary>The parts that depend on the model, built once per profile.</summary>
    private void Build(DeviceProfile p)
    {
        _builtFor = p;
        NoiseSegments.SetSegments(p.NoiseModes.Select(Labels.Noise).ToList());

        EqPresets.Children.Clear();
        _eqButtons.Clear();
        foreach (var preset in p.EqPresets)
        {
            var b = new ToggleButton { Content = Labels.Preset(preset), Style = (Style)FindResource("Chip") };
            b.Click += (_, _) =>
            {
                b.IsChecked = true;
                _ = _store.SetEqPresetAsync(preset);
            };
            EqPresets.Children.Add(b);
            _eqButtons.Add((preset, b));
        }
        EqSection.Visibility = p.EqPresets.Count > 0 ? Visibility.Visible : Visibility.Collapsed;

        CustomEqGrid.Children.Clear();
        CustomEqGrid.RowDefinitions.Clear();
        _eqSliders.Clear();
        string[] names = ["Bass", "Mid", "Treble"];
        for (int i = 0; i < p.CustomEqBands.Count; i++)
        {
            CustomEqGrid.RowDefinitions.Add(new RowDefinition());
            var label = new TextBlock { Text = i < names.Length ? names[i] : $"Band {i + 1}", VerticalAlignment = VerticalAlignment.Center, FontSize = 12 };
            var slider = new Slider
            {
                Minimum = -p.CustomEqMaxGain,
                Maximum = p.CustomEqMaxGain,
                TickFrequency = 0.5,
                IsSnapToTickEnabled = true,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 2, 0, 2),
            };
            var value = new TextBlock { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Right, FontSize = 12 };
            slider.ValueChanged += (_, _) =>
            {
                value.Text = $"{slider.Value:+0.0;-0.0;0} dB";
                if (_updating) return;
                _eqDebounce.Stop();
                _eqDebounce.Start();
            };
            System.Windows.Automation.AutomationProperties.SetName(slider, label.Text);
            Grid.SetRow(label, i);
            Grid.SetRow(slider, i);
            Grid.SetColumn(slider, 1);
            Grid.SetRow(value, i);
            Grid.SetColumn(value, 2);
            CustomEqGrid.Children.Add(label);
            CustomEqGrid.Children.Add(slider);
            CustomEqGrid.Children.Add(value);
            _eqSliders.Add(slider);
        }

        UltraBassRow.Visibility = p.SupportsUltraBass ? Visibility.Visible : Visibility.Collapsed;
        LatencyRow.Visibility = p.SupportsLowLatency ? Visibility.Visible : Visibility.Collapsed;
        LatencySeparator.Visibility = p.SupportsUltraBass && p.SupportsLowLatency ? Visibility.Visible : Visibility.Collapsed;

        BuildGestures(p);
    }

    private void BuildGestures(DeviceProfile p)
    {
        GestureGrid.Children.Clear();
        GestureGrid.RowDefinitions.Clear();
        _gestureBoxes.Clear();
        if (p.TapTypes.Count == 0) return;

        GestureGrid.RowDefinitions.Add(new RowDefinition());
        AddCell(new TextBlock { Text = "Left", FontSize = 12, Opacity = 0.7, Margin = new Thickness(2, 0, 0, 4) }, 0, 1);
        AddCell(new TextBlock { Text = "Right", FontSize = 12, Opacity = 0.7, Margin = new Thickness(2, 0, 0, 4) }, 0, 3);
        int row = 1;
        foreach (var type in p.TapTypes)
        {
            GestureGrid.RowDefinitions.Add(new RowDefinition());
            AddCell(new TextBlock { Text = Labels.Tap(type), FontSize = 12, VerticalAlignment = VerticalAlignment.Center, TextWrapping = TextWrapping.Wrap }, row, 0);
            foreach (var (bud, column) in new[] { (Component.Left, 1), (Component.Right, 3) })
            {
                var box = new ComboBox { FontSize = 12, Margin = new Thickness(0, 2, 0, 2), MinWidth = 0, HorizontalAlignment = HorizontalAlignment.Stretch };
                foreach (var action in p.TapActions(type)) box.Items.Add(new ComboBoxItem { Content = Labels.Action(action), Tag = action });
                System.Windows.Automation.AutomationProperties.SetName(box, $"{bud} {Labels.Tap(type)}");
                var t = type;
                var b = bud;
                box.SelectionChanged += (_, _) =>
                {
                    if (_updating || box.SelectedItem is not ComboBoxItem item) return;
                    _ = _store.SetGestureAsync(new Gesture(b, 0x01, t, (TapAction)item.Tag));
                };
                AddCell(box, row, column);
                _gestureBoxes.Add((bud, type, box));
            }
            row++;
        }
    }

    private void AddCell(UIElement e, int row, int column)
    {
        Grid.SetRow(e, row);
        Grid.SetColumn(e, column);
        GestureGrid.Children.Add(e);
    }

    private IReadOnlyList<DualDevice>? _renderedDual;

    private void RenderDualList()
    {
        // Only when the list changed: the battery is read every 3 s and rebuilding would reset the buttons.
        if (ReferenceEquals(_renderedDual, _store.DualDevices) && DualList.Children.Count > 0) return;
        _renderedDual = _store.DualDevices;
        DualList.Children.Clear();
        foreach (var d in _store.DualDevices)
        {
            var row = new Grid { Margin = new Thickness(0, 3, 0, 3) };
            row.Children.Add(new StackPanel
            {
                VerticalAlignment = VerticalAlignment.Center,
                Children =
                {
                    new TextBlock { Text = d.Name.Length > 0 ? d.Name : d.Mac, FontSize = 13 },
                    new TextBlock
                    {
                        Text = d.IsSelf ? "This PC" : d.IsConnected ? "Connected" : "Not connected",
                        Style = (Style)FindResource("Secondary"),
                    },
                },
            });
            if (!d.IsSelf)
            {
                var button = new Button
                {
                    Content = d.IsConnected ? "Disconnect" : "Connect",
                    HorizontalAlignment = HorizontalAlignment.Right,
                    VerticalAlignment = VerticalAlignment.Center,
                    FontSize = 12,
                };
                var device = d;
                button.Click += (_, _) =>
                {
                    button.IsEnabled = false;
                    _ = _store.ConnectDualDeviceAsync(device, !device.IsConnected);
                };
                row.Children.Add(button);
            }
            DualList.Children.Add(row);
        }
        if (DualList.Children.Count == 0)
            DualList.Children.Add(new TextBlock { Text = "Loading…", Style = (Style)FindResource("Secondary") });
    }

    private void BuildAppSettings()
    {
        var prefs = _host.Preferences;
        AddSwitch("Battery ring on the tray icon", prefs.ShowBatteryInTray, v => prefs.ShowBatteryInTray = v);
        AddSwitch("Alert when a bud is low", prefs.LowBatteryAlert, v => prefs.LowBatteryAlert = v, $"At {prefs.LowBatteryThreshold} % or below");
        AddSwitch("Ctrl + Alt + N switches noise control", prefs.NoiseHotkey, v => prefs.NoiseHotkey = v);
        AddSwitch("Keep the icon when the buds are away", prefs.AlwaysShowIcon, v => prefs.AlwaysShowIcon = v);
        AddSwitch("Launch at login", LoginItem.IsEnabled, v => LoginItem.IsEnabled = v);
    }

    private void AddSwitch(string text, bool value, Action<bool> set, string? detail = null)
    {
        var row = new Grid { Margin = new Thickness(0, 4, 0, 4) };
        var label = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 48, 0) };
        label.Children.Add(new TextBlock { Text = text, FontSize = 13, TextWrapping = TextWrapping.Wrap });
        if (detail != null) label.Children.Add(new TextBlock { Text = detail, Style = (Style)FindResource("Secondary") });
        var toggle = new CheckBox
        {
            Style = (Style)FindResource("Switch"),
            IsChecked = value,
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Center,
        };
        System.Windows.Automation.AutomationProperties.SetName(toggle, text);
        toggle.Click += (_, _) =>
        {
            set(toggle.IsChecked == true);
            _host.PreferencesChanged();
        };
        row.Children.Add(label);
        row.Children.Add(toggle);
        AppSettings.Children.Add(row);
    }

    // Events

    private void OnNoiseSegment(int index)
    {
        if (_store.Profile is { } p && index < p.NoiseModes.Count) _ = _store.SetNoiseModeAsync(p.NoiseModes[index]);
    }

    private void OnUltraBassToggled(object sender, RoutedEventArgs e)
    {
        if (_updating) return;
        _ = _store.SetUltraBassAsync(UltraBassSwitch.IsChecked == true, (int)UltraBassSlider.Value);
    }

    private void OnLatencyToggled(object sender, RoutedEventArgs e)
    {
        if (_updating) return;
        _ = _store.SetLowLatencyAsync(LatencySwitch.IsChecked == true);
    }

    private void OnRing(object sender, RoutedEventArgs e)
    {
        var button = (ToggleButton)sender;
        var bud = (string)button.Tag == "Left" ? Component.Left : Component.Right;
        bool ring = button.IsChecked == true;
        (bud == Component.Left ? RingLeftText : RingRightText).Text = ring ? "Stop" : $"Ring {bud.ToString().ToLowerInvariant()}";
        _ = _store.FindBudAsync(bud, ring);
    }

    private void OnMore(object sender, RoutedEventArgs e)
    {
        _morePage = true;
        if (_store.State == LinkState.Connected) _ = _store.RefreshDualDevicesAsync();
        Render();
    }

    private void OnBack(object sender, RoutedEventArgs e)
    {
        _morePage = false;
        Render();
    }

    private void OnRetry(object sender, RoutedEventArgs e) => _ = _host.RetryAsync();

    private void OnQuit(object sender, RoutedEventArgs e) => _host.Quit();

    // Placement: next to the tray icon, on whichever edge the taskbar is, inside the work area.

    private void Position()
    {
        var hwnd = new WindowInteropHelper(this).Handle;
        if (hwnd == IntPtr.Zero || ActualHeight <= 0) return;
        double scale = GetDpiForWindow(hwnd) / 96.0;
        int w = (int)Math.Ceiling(ActualWidth * scale), h = (int)Math.Ceiling(ActualHeight * scale);

        POINT anchor;
        if (_host.AnchorRect() is { } r) anchor = new POINT { X = (r.Left + r.Right) / 2, Y = (r.Top + r.Bottom) / 2 };
        else GetCursorPos(out anchor);
        var info = new MONITORINFO { cbSize = System.Runtime.InteropServices.Marshal.SizeOf<MONITORINFO>() };
        GetMonitorInfoW(MonitorFromPoint(anchor, MONITOR_DEFAULTTONEAREST), ref info);
        var work = info.rcWork;
        var mon = info.rcMonitor;
        int margin = (int)(12 * scale);

        int x = Math.Clamp(anchor.X - w / 2, work.Left + margin, Math.Max(work.Left + margin, work.Right - w - margin));
        int y = work.Bottom - h - margin;
        int enterFrom = 1;
        if (work.Top > mon.Top) { y = work.Top + margin; enterFrom = -1; }
        else if (work.Left > mon.Left) { x = work.Left + margin; y = Math.Clamp(anchor.Y - h / 2, work.Top + margin, work.Bottom - h - margin); enterFrom = 0; }
        else if (work.Right < mon.Right) { x = work.Right - w - margin; y = Math.Clamp(anchor.Y - h / 2, work.Top + margin, work.Bottom - h - margin); enterFrom = 0; }
        SetWindowPos(hwnd, IntPtr.Zero, x, y, 0, 0, SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);

        if (_positioned) return;
        _positioned = true;
        Activate();
        if (SystemParameters.ClientAreaAnimation)
        {
            var duration = TimeSpan.FromMilliseconds(170);
            var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
            BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, duration));
            EnterOffset.BeginAnimation(System.Windows.Media.TranslateTransform.YProperty,
                new DoubleAnimation(14 * enterFrom, 0, duration) { EasingFunction = ease });
        }
        else
        {
            Opacity = 1;
        }
    }
}
