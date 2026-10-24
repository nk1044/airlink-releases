using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Microsoft.Win32;
using static CmfLink.App.Native;

namespace CmfLink.App;

/// <summary>
/// Draws the tray icon: a pair of earbuds, optionally inside a battery ring for the lower bud.
/// Monochrome to match the taskbar, except the ring turns orange at 20 % and red at 10 %.
/// </summary>
internal static class TrayIconRenderer
{
    public static IntPtr Render(uint dpi, int? percent, bool showRing, bool dimmed)
    {
        int size = GetSystemMetricsForDpi(SM_CXSMICON, dpi);
        if (size <= 0) size = (int)Math.Round(16 * dpi / 96.0);
        var fg = TaskbarIsLight() ? Color.FromRgb(0x1A, 0x1A, 0x1A) : Colors.White;
        if (dimmed) fg.A = 0x90;

        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen())
        {
            double s = size / 16.0;
            dc.PushTransform(new ScaleTransform(s, s));
            bool ring = showRing && percent != null;
            if (ring)
            {
                var track = new Pen(new SolidColorBrush(Color.FromArgb(0x55, fg.R, fg.G, fg.B)), 1.6);
                dc.DrawEllipse(null, track, new Point(8, 8), 7.1, 7.1);
                var arc = new Pen(new SolidColorBrush(Theme.BatteryColor(percent!.Value, fg)), 1.6)
                {
                    StartLineCap = PenLineCap.Round,
                    EndLineCap = PenLineCap.Round,
                };
                dc.DrawGeometry(null, arc, Theme.Arc(new Point(8, 8), 7.1, percent.Value / 100.0));
                dc.PushTransform(new ScaleTransform(0.62, 0.62, 8, 8));
            }
            DrawBuds(dc, new SolidColorBrush(fg));
            if (ring) dc.Pop();
            dc.Pop();
        }

        var bitmap = new RenderTargetBitmap(size, size, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        return ToIcon(bitmap, size);
    }

    /// <summary>Two buds, heads up, stems down, in a 16×16 box.</summary>
    private static void DrawBuds(DrawingContext dc, Brush brush)
    {
        foreach (double x in new[] { 4.6, 11.4 })
        {
            dc.DrawEllipse(brush, null, new Point(x, 5.6), 3.1, 3.1);
            dc.DrawRoundedRectangle(brush, null, new Rect(x - 1.25, 6.5, 2.5, 7.6), 1.25, 1.25);
        }
    }

    public static bool TaskbarIsLight()
    {
        using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
        return key?.GetValue("SystemUsesLightTheme") is int v && v == 1;
    }

    /// <summary>32-bit icon from premultiplied BGRA. Icons want straight alpha, so un-premultiply.</summary>
    private static IntPtr ToIcon(BitmapSource bitmap, int size)
    {
        var pixels = new byte[size * size * 4];
        bitmap.CopyPixels(pixels, size * 4, 0);
        for (int i = 0; i < pixels.Length; i += 4)
        {
            byte a = pixels[i + 3];
            if (a is 0 or 255) continue;
            pixels[i] = (byte)Math.Min(255, pixels[i] * 255 / a);
            pixels[i + 1] = (byte)Math.Min(255, pixels[i + 1] * 255 / a);
            pixels[i + 2] = (byte)Math.Min(255, pixels[i + 2] * 255 / a);
        }

        var header = new BITMAPINFOHEADER
        {
            biSize = System.Runtime.InteropServices.Marshal.SizeOf<BITMAPINFOHEADER>(),
            biWidth = size,
            biHeight = -size, // top-down
            biPlanes = 1,
            biBitCount = 32,
        };
        var color = CreateDIBSection(IntPtr.Zero, ref header, 0, out var bits, IntPtr.Zero, 0);
        System.Runtime.InteropServices.Marshal.Copy(pixels, 0, bits, pixels.Length);
        var mask = CreateBitmap(size, size, 1, 1, IntPtr.Zero);
        try
        {
            var info = new ICONINFO { fIcon = true, hbmColor = color, hbmMask = mask };
            return CreateIconIndirect(ref info);
        }
        finally
        {
            DeleteObject(color);
            DeleteObject(mask);
        }
    }
}
