#include "clip.h"
#include "config.h"
#include "link.h"
#include "proto.h"
#include <shellapi.h>
#include <shlobj.h>

#define TEXT_MAX (1u << 20)

static UINT cf_png, cf_exclude, cf_history, cf_cloud, cf_effect;
static DWORD own_seq;   /* sequence number of our own last write: don't echo it back */
static UINT64 last_hash; /* last item sent or received */

void clip_init(void)
{
    cf_png = RegisterClipboardFormatW(L"PNG");
    cf_exclude = RegisterClipboardFormatW(L"ExcludeClipboardContentFromMonitorProcessing");
    cf_history = RegisterClipboardFormatW(L"CanIncludeInClipboardHistory");
    cf_cloud = RegisterClipboardFormatW(L"CanUploadToCloudClipboard");
    cf_effect = RegisterClipboardFormatW(CFSTR_PREFERREDDROPEFFECT);
}

static BOOL open_clip(HWND hwnd)
{
    for (int i = 0; i < 10; i++) {
        if (OpenClipboard(hwnd)) return TRUE;
        Sleep(20); /* another app is holding it */
    }
    return FALSE;
}

/* Password managers mark secrets with these formats set to 0. */
static BOOL flag_is_zero(UINT fmt)
{
    HANDLE h = GetClipboardData(fmt);
    if (!h || GlobalSize(h) < sizeof(DWORD)) return FALSE;
    DWORD *p = GlobalLock(h);
    BOOL zero = p && *p == 0;
    GlobalUnlock(h);
    return zero;
}

static BYTE *copy_global(HANDLE h, size_t *len)
{
    SIZE_T n = GlobalSize(h);
    void *p = GlobalLock(h);
    BYTE *c = p ? malloc(n ? n : 1) : NULL;
    if (c) memcpy(c, p, n);
    if (p) GlobalUnlock(h);
    *len = n;
    return c;
}

/* A browser's "Copy image" can also put the image URL on the clipboard as text. */
static BOOL is_lone_url(const BYTE *s, size_t n)
{
    if (n < 8 || n > 4096 || _strnicmp((const char *)s, "http", 4)) return FALSE;
    for (size_t i = 0; i < n; i++)
        if (s[i] == '\n' || s[i] == ' ') return FALSE;
    return TRUE;
}

void clip_changed(HWND hwnd)
{
    if (GetClipboardSequenceNumber() == own_seq) return;
    if (g_cfg.paused || link_state(NULL, 0) == LINK_NONE) return;
    if (!open_clip(hwnd)) return;

    BYTE *data = NULL;
    size_t len = 0;
    BOOL dib = FALSE;
    HANDLE h;
    if (IsClipboardFormatAvailable(cf_exclude) || flag_is_zero(cf_history) || flag_is_zero(cf_cloud)) {
        CloseClipboard();
        return;
    }
    if ((h = GetClipboardData(CF_UNICODETEXT))) {
        const WCHAR *w = GlobalLock(h);
        if (w) {
            int n;
            data = (BYTE *)utf8_from_wide(w, (int)wcsnlen(w, GlobalSize(h) / sizeof(WCHAR)), &n);
            len = (size_t)n;
            GlobalUnlock(h);
        }
    }
    BOOL has_image = (cf_png && IsClipboardFormatAvailable(cf_png)) || IsClipboardFormatAvailable(CF_DIB);
    if (data && has_image && is_lone_url(data, len)) {
        free(data);
        data = NULL;
    }
    BYTE type = T_TEXT;
    if (!data && has_image) {
        type = T_IMAGE;
        if (cf_png && (h = GetClipboardData(cf_png))) {
            data = copy_global(h, &len);
        } else if ((h = GetClipboardData(CF_DIB))) {
            data = copy_global(h, &len);
            dib = TRUE;
        }
    }
    CloseClipboard();

    if (!data) return;
    if (!len || (type == T_TEXT && len > TEXT_MAX)) {
        free(data);
        return;
    }
    UINT64 hash = fnv64(data, len);
    if (hash == last_hash) {
        free(data);
        return;
    }
    last_hash = hash;
    if (dib) link_send_dib(data, len);
    else link_send_frame(type, data, len);
}

static void finish_set(UINT64 hash)
{
    CloseClipboard();
    own_seq = GetClipboardSequenceNumber();
    last_hash = hash;
}

static HGLOBAL global_from(const void *p, size_t n)
{
    HGLOBAL g = GlobalAlloc(GMEM_MOVEABLE, n ? n : 1);
    if (g) {
        memcpy(GlobalLock(g), p, n);
        GlobalUnlock(g);
    }
    return g;
}

void clip_set_text(HWND hwnd, const char *utf8, size_t len)
{
    WCHAR *w = wide_from_utf8(utf8, (int)len);
    if (!w) return;
    HGLOBAL g = global_from(w, (wcslen(w) + 1) * sizeof(WCHAR));
    free(w);
    if (!g) return;
    if (!open_clip(hwnd)) {
        GlobalFree(g);
        return;
    }
    EmptyClipboard();
    if (!SetClipboardData(CF_UNICODETEXT, g)) GlobalFree(g);
    finish_set(fnv64(utf8, len));
}

void clip_set_image(HWND hwnd, RecvImage *img)
{
    if (!open_clip(hwnd)) return;
    EmptyClipboard();
    if (SetClipboardData(CF_DIB, img->dib)) img->dib = NULL;
    if (img->png && cf_png) {
        HGLOBAL g = global_from(img->png, img->png_len);
        if (g && !SetClipboardData(cf_png, g)) GlobalFree(g);
    }
    finish_set(img->hash);
}

void clip_set_files(HWND hwnd, const FileList *files)
{
    size_t chars = 1;
    for (int i = 0; i < files->n; i++) chars += wcslen(files->paths[i]) + 1;
    HGLOBAL g = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, sizeof(DROPFILES) + chars * sizeof(WCHAR));
    if (!g) return;
    DROPFILES *df = GlobalLock(g);
    df->pFiles = sizeof(DROPFILES);
    df->fWide = TRUE;
    WCHAR *p = (WCHAR *)(df + 1);
    for (int i = 0; i < files->n; i++) {
        wcscpy(p, files->paths[i]);
        p += wcslen(p) + 1;
    }
    GlobalUnlock(g);

    DWORD copy = DROPEFFECT_COPY;
    HGLOBAL e = global_from(&copy, sizeof copy);
    if (!open_clip(hwnd)) {
        GlobalFree(g);
        if (e) GlobalFree(e);
        return;
    }
    EmptyClipboard();
    if (!SetClipboardData(CF_HDROP, g)) GlobalFree(g);
    if (e && !SetClipboardData(cf_effect, e)) GlobalFree(e);
    finish_set(0);
}

BOOL clip_get_files(HWND hwnd, FileList *out)
{
    if (!open_clip(hwnd)) return FALSE;
    HDROP h = GetClipboardData(CF_HDROP);
    if (h) {
        UINT n = DragQueryFileW(h, 0xFFFFFFFF, NULL, 0);
        for (UINT i = 0; i < n; i++) {
            UINT len = DragQueryFileW(h, i, NULL, 0);
            WCHAR *path = malloc((len + 1) * sizeof(WCHAR));
            if (!path) break;
            DragQueryFileW(h, i, path, len + 1);
            file_list_add(out, path);
        }
    }
    CloseClipboard();
    return out->n > 0;
}
