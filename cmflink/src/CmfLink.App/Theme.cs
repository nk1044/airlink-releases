using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using static CmfLink.App.Native;

namespace CmfLink.App;

internal static class Theme
{
    public static readonly Color Green = Color.FromRgb(0x2E, 0xB8, 0x5C);
    public static readonly Color Orange = Color.FromRgb(0xF7, 0x93, 0x1E);
    public static readonly Color Red = Color.FromRgb(0xE8, 0x3B, 0x3B);

    /// <summary>Green, orange at 20 % or below, red at 10 % or below. <paramref name="normal"/> replaces green where monochrome fits better.</summary>
    public static Color BatteryColor(int percent, Color? normal = null) =>
        percent <= 10 ? Red : percent <= 20 ? Orange : normal ?? Green;

    /// <summary>A clockwise arc from 12 o'clock covering <paramref name="fraction"/> of the circle.</summary>
    public static Geometry Arc(Point center, double radius, double fraction)
    {
        fraction = Math.Clamp(fraction, 0, 1);
        if (fraction <= 0) return Geometry.Empty;
        if (fraction >= 0.9999) return new EllipseGeometry(center, radius, radius);

        double angle = fraction * 2 * Math.PI;
        var start = new Point(center.X, center.Y - radius);
        var end = new Point(center.X + radius * Math.Sin(angle), center.Y - radius * Math.Cos(angle));
        var figure = new PathFigure { StartPoint = start, IsClosed = false };
        figure.Segments.Add(new ArcSegment(end, new Size(radius, radius), 0, fraction > 0.5, SweepDirection.Clockwise, true));
        var geometry = new PathGeometry([figure]);
        geometry.Freeze();
        return geometry;
    }

    public static bool AppsUseLightTheme()
    {
        using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
        return key?.GetValue("AppsUseLightTheme") is not int v || v == 1;
    }

    /// <summary>Acrylic backdrop and rounded corners, like the system flyouts. No-op before Windows 11.</summary>
    public static void ApplyFlyoutBackdrop(Window window)
    {
        var hwnd = new WindowInteropHelper(window).Handle;
        if (HwndSource.FromHwnd(hwnd) is { CompositionTarget: { } target }) target.BackgroundColor = Colors.Transparent;
        // The backdrop only shows through where the frame extends, so extend it over the whole window.
        var margins = new MARGINS { Left = -1, Right = -1, Top = -1, Bottom = -1 };
        DwmExtendFrameIntoClientArea(hwnd, ref margins);
        int dark = AppsUseLightTheme() ? 0 : 1;
        DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, ref dark, sizeof(int));
        int corner = DWMWCP_ROUND;
        DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, ref corner, sizeof(int));
        int backdrop = DWMSBT_TRANSIENTWINDOW;
        DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, ref backdrop, sizeof(int));
    }

    public static Brush Resource(FrameworkElement scope, string key, Color fallback) =>
        scope.TryFindResource(key) as Brush ?? new SolidColorBrush(fallback);
}
