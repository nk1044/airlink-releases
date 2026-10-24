#include "icons.h"

static BOOL light_taskbar(void)
{
    DWORD v = 0, size = sizeof v;
    RegGetValueW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                 L"SystemUsesLightTheme", RRF_RT_REG_DWORD, NULL, &v, &size);
    return v != 0;
}

static void fill(UINT32 *px, int s, int x0, int y0, int x1, int y1, UINT32 c)
{
    for (int y = max(y0, 0); y < min(y1, s); y++)
        for (int x = max(x0, 0); x < min(x1, s); x++) px[y * s + x] = c;
}

static void frame(UINT32 *px, int s, int x0, int y0, int x1, int y1, int t, UINT32 c)
{
    fill(px, s, x0, y0, x1, y0 + t, c);
    fill(px, s, x0, y1 - t, x1, y1, c);
    fill(px, s, x0, y0, x0 + t, y1, c);
    fill(px, s, x1 - t, y0, x1, y1, c);
}

/* A clipboard glyph: filled when connected (blue over Bluetooth), outlined while waiting, bars when paused. */
HICON icon_make(int state)
{
    int s = GetSystemMetrics(SM_CXSMICON);
    BITMAPINFO bi = { 0 };
    bi.bmiHeader.biSize = sizeof bi.bmiHeader;
    bi.bmiHeader.biWidth = s;
    bi.bmiHeader.biHeight = -s;
    bi.bmiHeader.biPlanes = 1;
    bi.bmiHeader.biBitCount = 32;
    bi.bmiHeader.biCompression = BI_RGB;
    void *bits;
    HBITMAP color = CreateDIBSection(NULL, &bi, DIB_RGB_COLORS, &bits, NULL, 0);
    if (!color) return NULL;
    UINT32 *px = bits;
    memset(px, 0, (size_t)s * s * 4);

    UINT32 fg = light_taskbar() ? 0xFF1F1F1F : 0xFFFFFFFF;
    UINT32 dim = (fg & 0x00FFFFFF) | 0xB0000000;
    int t = s >= 24 ? 2 : 1;
#define U(v) ((v) * s / 16)
    int bx0 = U(3), by0 = U(3), bx1 = U(13), by1 = U(15);

    switch (state) {
    case ICON_WIFI:
    case ICON_BT: {
        UINT32 c = state == ICON_BT ? 0xFF3B82F6 : fg;
        fill(px, s, bx0, by0, bx1, by1, c);
        fill(px, s, U(5), U(7), U(11), U(7) + t, 0); /* lines on the paper */
        fill(px, s, U(5), U(10), U(11), U(10) + t, 0);
        fill(px, s, U(6), U(1), U(10), U(4) + 1, c);
        break;
    }
    case ICON_WAITING:
        frame(px, s, bx0, by0, bx1, by1, t, dim);
        fill(px, s, U(6), U(1), U(10), U(4) + 1, dim);
        break;
    case ICON_PAUSED:
        frame(px, s, bx0, by0, bx1, by1, t, fg);
        fill(px, s, U(6), U(1), U(10), U(4) + 1, fg);
        fill(px, s, U(6), U(7), U(7) + t, U(13), fg);
        fill(px, s, U(9), U(7), U(10) - 1 + t, U(13), fg);
        break;
    }
#undef U

    BYTE *mask_bits = calloc((size_t)((s + 15) / 16) * 2 * s, 1);
    HBITMAP mask = CreateBitmap(s, s, 1, 1, mask_bits);
    free(mask_bits);
    ICONINFO ii = { TRUE, 0, 0, mask, color };
    HICON icon = CreateIconIndirect(&ii);
    DeleteObject(mask);
    DeleteObject(color);
    return icon;
}
