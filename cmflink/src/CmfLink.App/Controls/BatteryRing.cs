using System.Globalization;
using System.Windows;
using System.Windows.Media;

namespace CmfLink.App.Controls;

/// <summary>A ring for one battery: the level as an arc, the percentage inside, a bolt while charging.</summary>
internal sealed class BatteryRing : FrameworkElement
{
    private const double Thickness = 4.5;

    public static readonly DependencyProperty PercentProperty = DependencyProperty.Register(
        nameof(Percent), typeof(int?), typeof(BatteryRing),
        new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty ChargingProperty = DependencyProperty.Register(
        nameof(Charging), typeof(bool), typeof(BatteryRing),
        new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.AffectsRender));

    public int? Percent
    {
        get => (int?)GetValue(PercentProperty);
        set => SetValue(PercentProperty, value);
    }

    public bool Charging
    {
        get => (bool)GetValue(ChargingProperty);
        set => SetValue(ChargingProperty, value);
    }

    protected override Size MeasureOverride(Size available) => new(56, 56);

    protected override void OnRender(DrawingContext dc)
    {
        double size = Math.Min(ActualWidth, ActualHeight);
        var center = new Point(ActualWidth / 2, ActualHeight / 2);
        double radius = size / 2 - Thickness / 2;
        var text = Theme.Resource(this, "TextFillColorPrimaryBrush", Colors.Gray);
        var secondary = Theme.Resource(this, "TextFillColorTertiaryBrush", Color.FromArgb(0x60, 0x80, 0x80, 0x80));

        dc.DrawEllipse(null, new Pen(Theme.Resource(this, "ControlStrongStrokeColorDisabledBrush", Color.FromArgb(0x40, 0x80, 0x80, 0x80)), Thickness), center, radius, radius);

        string label;
        if (Percent is int p)
        {
            var pen = new Pen(new SolidColorBrush(Theme.BatteryColor(p)), Thickness) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round };
            dc.DrawGeometry(null, pen, Theme.Arc(center, radius, p / 100.0));
            label = p.ToString(CultureInfo.InvariantCulture);
        }
        else
        {
            label = "—";
        }

        double dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;
        var number = new FormattedText(label, CultureInfo.CurrentUICulture, FlowDirection.LeftToRight,
            new Typeface(new FontFamily("Segoe UI Variable Display, Segoe UI"), FontStyles.Normal, FontWeights.SemiBold, FontStretches.Normal),
            15, Percent == null ? secondary : text, dpi);
        var unit = new FormattedText(Percent == null ? "" : "%", CultureInfo.CurrentUICulture, FlowDirection.LeftToRight,
            new Typeface("Segoe UI"), 9, secondary, dpi);
        double width = number.Width + unit.Width;
        double y = center.Y - number.Height / 2 + (Charging ? 3 : 0);
        dc.DrawText(number, new Point(center.X - width / 2, y));
        dc.DrawText(unit, new Point(center.X - width / 2 + number.Width, y + number.Baseline - unit.Baseline));

        if (Charging)
        {
            var bolt = new FormattedText("", CultureInfo.InvariantCulture, FlowDirection.LeftToRight,
                new Typeface("Segoe Fluent Icons, Segoe MDL2 Assets"), 9, new SolidColorBrush(Theme.Green), dpi);
            dc.DrawText(bolt, new Point(center.X - bolt.Width / 2, y - bolt.Height + 3));
        }
    }
}
