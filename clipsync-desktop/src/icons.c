#include "icons.h"

#define SS 4 /* supersampling: drawn at 4x, then averaged for smooth edges */

static BOOL light_taskbar(void)
{
    DWORD v = 0, size = sizeof v;
    RegGetValueW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                 L"SystemUsesLightTheme", RRF_RT_REG_DWORD, NULL, &v, &size);
    return v != 0;
}

/* Is (x, y) inside the rounded rectangle? Coordinates in 16ths of the icon. */
static BOOL in_rrect(float x, float y, float x0, float y0, float x1, float y1, float r)
{
    if (x < x0 || x > x1 || y < y0 || y > y1) return FALSE;
    float cx = x < x0 + r ? x0 + r : x > x1 - r ? x1 - r : x;
    float cy = y < y0 + r ? y0 + r : y > y1 - r ? y1 - r : y;
    return (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r;
}

/* Color of the clipboard glyph at (x, y), or 0 for transparent. */
static UINT32 shade(int state, float x, float y, UINT32 fg, UINT32 accent)
{
    BOOL clip = in_rrect(x, y, 5.6f, 1.2f, 10.4f, 4.6f, 1.2f);
    BOOL body = in_rrect(x, y, 2.8f, 2.8f, 13.2f, 15.2f, 2.0f);
    BOOL inner = in_rrect(x, y, 4.2f, 4.2f, 11.8f, 13.8f, 0.8f);
    switch (state) {
    case ICON_WIFI:
    case ICON_BT: {
        UINT32 c = state == ICON_BT ? accent : fg;
        BOOL line = x > 5.5f && x < 10.5f && ((y > 7.2f && y < 8.6f) || (y > 10.2f && y < 11.6f));
        if (clip) return c;
        if (body && !line && !(y < 4.9f && x > 5.0f && x < 11.0f)) return c; /* gap under the clip */
        return 0;
    }
    case ICON_WAITING:
        return clip || (body && !inner) ? (fg & 0x00FFFFFF) | 0xA0000000 : 0;
    default: /* ICON_PAUSED */
        if (clip || (body && !inner)) return fg;
        if ((x > 5.9f && x < 7.3f && y > 7.0f && y < 12.6f) || (x > 8.7f && x < 10.1f && y > 7.0f && y < 12.6f)) return fg;
        return 0;
    }
}

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
    UINT32 fg = light_taskbar() ? 0xFF1F1F1F : 0xFFFFFFFF, accent = 0xFF3B82F6;
    float unit = 16.0f / (float)(s * SS);

    for (int y = 0; y < s; y++) {
        for (int x = 0; x < s; x++) {
            UINT32 a = 0, r = 0, g = 0, b = 0;
            for (int sy = 0; sy < SS; sy++) {
                for (int sx = 0; sx < SS; sx++) {
                    UINT32 c = shade(state, ((float)(x * SS + sx) + 0.5f) * unit, ((float)(y * SS + sy) + 0.5f) * unit, fg, accent);
                    UINT32 ca = c >> 24;
                    a += ca;
                    r += ((c >> 16) & 0xFF) * ca;
                    g += ((c >> 8) & 0xFF) * ca;
                    b += (c & 0xFF) * ca;
                }
            }
            px[y * s + x] = a ? (a / (SS * SS)) << 24 | (r / a) << 16 | (g / a) << 8 | (b / a) : 0;
        }
    }

    BYTE *mask_bits = calloc((size_t)((s + 15) / 16) * 2 * s, 1);
    HBITMAP mask = CreateBitmap(s, s, 1, 1, mask_bits);
    free(mask_bits);
    ICONINFO ii = { TRUE, 0, 0, mask, color };
    HICON icon = CreateIconIndirect(&ii);
    DeleteObject(mask);
    DeleteObject(color);
    return icon;
}
