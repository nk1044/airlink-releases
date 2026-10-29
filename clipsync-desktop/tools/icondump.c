/* Renders the four tray icons side by side, magnified, to a PNG for checking how they look. */
#include "../src/common.h"
#include "../src/icons.h"
#include "../src/image.h"
#include <objbase.h>
#include <stdio.h>

HWND g_hwnd;

int wmain(int argc, wchar_t **argv)
{
    if (argc < 2) return 2;
    CoInitializeEx(NULL, COINIT_MULTITHREADED);
    int s = GetSystemMetrics(SM_CXSMICON), zoom = 8, cell = s * zoom + 16, w = cell * 4, h = cell * 2;
    size_t stride = (size_t)w * 4;
    BYTE *dib = calloc(sizeof(BITMAPINFOHEADER) + stride * h, 1);
    BITMAPINFOHEADER *bh = (BITMAPINFOHEADER *)dib;
    bh->biSize = sizeof *bh;
    bh->biWidth = w;
    bh->biHeight = -h;
    bh->biPlanes = 1;
    bh->biBitCount = 32;
    UINT32 *px = (UINT32 *)(dib + sizeof *bh);
    /* top row: dark taskbar background, bottom row: light (as Windows would show them) */
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) px[y * w + x] = y < cell ? 0xFF202020 : 0xFFF3F3F3;

    for (int state = 0; state < 4; state++) {
        HICON icon = icon_make(state);
        ICONINFO ii;
        GetIconInfo(icon, &ii);
        BITMAPINFO bi = { 0 };
        bi.bmiHeader.biSize = sizeof bi.bmiHeader;
        bi.bmiHeader.biWidth = s;
        bi.bmiHeader.biHeight = -s;
        bi.bmiHeader.biPlanes = 1;
        bi.bmiHeader.biBitCount = 32;
        UINT32 *ip = malloc((size_t)s * s * 4);
        HDC dc = GetDC(NULL);
        GetDIBits(dc, ii.hbmColor, 0, (UINT)s, ip, &bi, DIB_RGB_COLORS);
        ReleaseDC(NULL, dc);
        for (int row = 0; row < 2; row++) {
            for (int y = 0; y < s * zoom; y++) {
                for (int x = 0; x < s * zoom; x++) {
                    UINT32 c = ip[(y / zoom) * s + x / zoom], a = c >> 24;
                    UINT32 *d = &px[(row * cell + 8 + y) * w + state * cell + 8 + x];
                    UINT32 out = 0xFF000000;
                    for (int k = 0; k < 24; k += 8) {
                        UINT32 fc = (c >> k) & 0xFF, bc = (*d >> k) & 0xFF;
                        out |= ((fc * a + bc * (255 - a)) / 255) << k;
                    }
                    *d = out;
                }
            }
        }
        free(ip);
        DestroyIcon(icon);
    }
    BYTE *png;
    size_t len;
    if (!img_dib_to_png(dib, sizeof(BITMAPINFOHEADER) + stride * h, &png, &len)) return 1;
    FILE *f = _wfopen(argv[1], L"wb");
    fwrite(png, 1, len, f);
    fclose(f);
    printf("%d px icons -> %zu byte PNG\n", s, len);
    return 0;
}
