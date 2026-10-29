using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace CmfLink.App.Controls;

/// <summary>
/// A row of mutually exclusive options with a pill that slides to the selection. While a
/// selection waits for confirmation the pill is drawn faint; it firms up once confirmed.
/// </summary>
internal sealed class SegmentedControl : Grid
{
    private readonly Border _track = new() { CornerRadius = new CornerRadius(8) };
    private readonly Border _pill = new() { CornerRadius = new CornerRadius(6), Margin = new Thickness(3), HorizontalAlignment = HorizontalAlignment.Left };
    private readonly TranslateTransform _offset = new();
    private readonly List<TextBlock> _labels = [];
    private int _selected = -1;
    private bool _pending;

    public event Action<int>? SegmentClicked;

    public SegmentedControl()
    {
        Height = 36;
        _pill.RenderTransform = _offset;
        Children.Add(_track);
        Children.Add(_pill);
        SetColumnSpan(_track, 99);
        SizeChanged += (_, _) => Layout(animate: false);
        Loaded += (_, _) =>
        {
            _track.Background = Theme.Resource(this, "ControlFillColorSecondaryBrush", Color.FromArgb(0x18, 0x80, 0x80, 0x80));
            _pill.Background = Theme.Resource(this, "ControlFillColorDefaultBrush", Colors.White);
            _pill.BorderBrush = Theme.Resource(this, "ControlElevationBorderBrush", Color.FromArgb(0x20, 0, 0, 0));
            _pill.BorderThickness = new Thickness(1);
        };
    }

    public void SetSegments(IReadOnlyList<string> labels)
    {
        foreach (var l in _labels) Children.Remove((UIElement)l.Parent);
        _labels.Clear();
        ColumnDefinitions.Clear();
        for (int i = 0; i < labels.Count; i++)
        {
            ColumnDefinitions.Add(new ColumnDefinition());
            var text = new TextBlock
            {
                Text = labels[i],
                FontSize = 12,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                TextTrimming = TextTrimming.CharacterEllipsis,
            };
            int index = i;
            var hit = new Border { Background = Brushes.Transparent, Child = text, Cursor = Cursors.Hand, Focusable = true };
            hit.MouseLeftButtonUp += (_, _) => SegmentClicked?.Invoke(index);
            hit.KeyDown += (_, e) =>
            {
                if (e.Key is Key.Enter or Key.Space) SegmentClicked?.Invoke(index);
            };
            System.Windows.Automation.AutomationProperties.SetName(hit, labels[i]);
            SetColumn(hit, i);
            Children.Add(hit);
            _labels.Add(text);
        }
        SetColumnSpan(_track, labels.Count);
        Layout(animate: false);
    }

    /// <param name="index">-1 for no selection (mode not reported yet).</param>
    public void Select(int index, bool pending)
    {
        bool moved = index != _selected;
        _selected = index;
        _pending = pending;
        for (int i = 0; i < _labels.Count; i++) _labels[i].FontWeight = i == index ? FontWeights.SemiBold : FontWeights.Normal;
        Layout(animate: moved);
    }

    private void Layout(bool animate)
    {
        if (_labels.Count == 0 || ActualWidth <= 0) return;
        double segment = ActualWidth / _labels.Count;
        _pill.Width = Math.Max(0, segment - 6);
        _pill.Visibility = _selected < 0 ? Visibility.Hidden : Visibility.Visible;
        _pill.Opacity = _pending ? 0.55 : 1;
        double x = Math.Max(0, _selected) * segment;
        if (animate && SystemParameters.ClientAreaAnimation)
        {
            var anim = new DoubleAnimation(x, TimeSpan.FromMilliseconds(180)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } };
            _offset.BeginAnimation(TranslateTransform.XProperty, anim);
        }
        else
        {
            _offset.BeginAnimation(TranslateTransform.XProperty, null);
            _offset.X = x;
        }
    }
}
