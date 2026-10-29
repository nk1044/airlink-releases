#include "transfer.h"
#include "files.h"
#include "link.h"
#include "resource.h"
#include <commctrl.h>
#include <shellapi.h>
#include <shlobj.h>
#include <stdio.h>

#define TICK_TIMER  1
#define CLOSE_TIMER 2
#define TICK_MS     250
#define CLOSE_MS    10000

enum { TXE_START, TXE_END };

typedef struct {
    volatile LONG active, waiting, file, files, result, count;
    volatile LONG64 done, total, file_done, file_size;
    volatile ULONGLONG started, ended;
    WCHAR name[MAX_PATH], peer[65]; /* under g_cs */
} Slot;

static Slot g_s[2];
static CRITICAL_SECTION g_cs;

/* UI thread only */
static HWND g_dlg;
static HFONT g_bold;
static int g_dir = -1;      /* slot shown in the window */
static BOOL g_hidden[2];    /* the user hid the window for this transfer: report the end in a balloon */
static BOOL g_summary;      /* the window shows a finished transfer */
static BOOL g_stopping;     /* the user stopped a receive */
static double g_speed;      /* bytes/s, smoothed */
static LONG64 g_prev_done;
static ULONGLONG g_prev_tick;
static int g_ticks;
static WCHAR g_last_file[MAX_PATH];

/* ---- worker side ---- */

void tx_start(int dir, int files, UINT64 total)
{
    Slot *s = &g_s[dir];
    EnterCriticalSection(&g_cs);
    s->name[0] = s->peer[0] = 0;
    s->file = 0;
    s->files = files;
    s->done = s->file_done = s->file_size = 0;
    s->total = (LONG64)total;
    s->count = 0;
    s->waiting = 1;
    s->started = GetTickCount64();
    s->active = 1;
    LeaveCriticalSection(&g_cs);
    PostMessageW(g_hwnd, WM_APP_TX, (WPARAM)dir, TXE_START);
}

void tx_connected(int dir, const char *peer)
{
    Slot *s = &g_s[dir];
    EnterCriticalSection(&g_cs);
    MultiByteToWideChar(CP_UTF8, 0, peer, -1, s->peer, ARRAYSIZE(s->peer));
    s->waiting = 0;
    s->started = GetTickCount64(); /* speed and duration count from here */
    LeaveCriticalSection(&g_cs);
}

void tx_file(int dir, int index, const WCHAR *name, UINT64 size)
{
    Slot *s = &g_s[dir];
    EnterCriticalSection(&g_cs);
    wcsncpy(s->name, name, ARRAYSIZE(s->name) - 1);
    s->file = index;
    s->file_size = (LONG64)size;
    s->file_done = 0;
    LeaveCriticalSection(&g_cs);
}

void tx_add(int dir, UINT64 bytes)
{
    InterlockedExchangeAdd64(&g_s[dir].done, (LONG64)bytes);
    InterlockedExchangeAdd64(&g_s[dir].file_done, (LONG64)bytes);
}

void tx_finish(int dir, int result, int count)
{
    Slot *s = &g_s[dir];
    s->result = result;
    s->count = count;
    s->ended = GetTickCount64();
    s->active = 0;
    PostMessageW(g_hwnd, WM_APP_TX, (WPARAM)dir, TXE_END);
}

BOOL tx_active(int dir) { return g_s[dir].active; }

/* ---- formatting ---- */

static void fmt_size(WCHAR *out, size_t n, UINT64 b)
{
    if (b < 1024) swprintf(out, n, L"%u B", (unsigned)b);
    else if (b < 1024 * 1024) swprintf(out, n, L"%.0f KB", b / 1024.0);
    else if (b < 1024ULL * 1024 * 1024) swprintf(out, n, L"%.1f MB", b / 1048576.0);
    else swprintf(out, n, L"%.2f GB", b / 1073741824.0);
}

static void fmt_duration(WCHAR *out, size_t n, double sec)
{
    unsigned s = sec < 1 ? 1 : (unsigned)(sec + 0.5);
    if (s < 60) swprintf(out, n, L"%u s", s);
    else if (s < 3600) swprintf(out, n, L"%u min %u s", s / 60, s % 60);
    else swprintf(out, n, L"%u h %u min", s / 3600, s % 3600 / 60);
}

static const WCHAR *plural(LONG n) { return n == 1 ? L"" : L"s"; }

/* ---- window ---- */

static void set(int id, const WCHAR *text) { SetDlgItemTextW(g_dlg, id, text); }
static void show(int id, BOOL on) { ShowWindow(GetDlgItem(g_dlg, id), on ? SW_SHOWNA : SW_HIDE); }
static void bar(LONG64 part, LONG64 whole)
{
    SendDlgItemMessageW(g_dlg, IDC_TX_BAR, PBM_SETPOS, whole > 0 ? (WPARAM)(part * 1000 / whole) : 0, 0);
}

/* The live view of the shown slot: name, bar, bytes, speed, time left. */
static void update_view(void)
{
    Slot *s = &g_s[g_dir];
    WCHAR title[128], file[MAX_PATH + 40], detail[160], done[24], whole[24], speed[24], left[40];
    BOOL send = g_dir == TX_SEND;
    LONG64 d = s->done, fd = s->file_done, fs = s->file_size, total = s->total;
    ULONGLONG now = GetTickCount64();

    /* speed: smoothed over the last few ticks, so it doesn't jump around */
    if (g_prev_tick && now > g_prev_tick) {
        double inst = (double)(d - g_prev_done) * 1000.0 / (double)(now - g_prev_tick);
        g_speed = g_speed > 0 ? 0.75 * g_speed + 0.25 * inst : inst;
    }
    g_prev_tick = now;
    g_prev_done = d;

    EnterCriticalSection(&g_cs);
    if (s->waiting)
        wcscpy(title, send ? L"Waiting for your phone\x2026" : L"Receiving\x2026");
    else
        swprintf(title, ARRAYSIZE(title), send ? L"Sending to %s" : L"Receiving from %s", s->peer[0] ? s->peer : L"phone");
    if (s->file == 0)
        file[0] = 0;
    else if (send)
        swprintf(file, ARRAYSIZE(file), L"%s   \x00B7   file %ld of %ld", s->name, s->file, s->files);
    else
        swprintf(file, ARRAYSIZE(file), L"%s   \x00B7   file %ld", s->name, s->file);
    LeaveCriticalSection(&g_cs);

    if (s->waiting) {
        wcscpy(detail, L"Files go over Wi-Fi: the phone needs the same Wi-Fi as this PC, or its hotspot.");
        bar(0, 1);
    } else {
        /* sending knows the whole batch; receiving only knows the current file */
        LONG64 part = send ? d : fd, of = send ? total : fs;
        if (of > 0 && part > of) part = of; /* a file grew while it was being sent */
        fmt_size(done, ARRAYSIZE(done), (UINT64)part);
        fmt_size(whole, ARRAYSIZE(whole), (UINT64)of);
        fmt_size(speed, ARRAYSIZE(speed), (UINT64)(g_speed > 0 ? g_speed : 0));
        if (now - s->started < 1500 || g_speed < 1) {
            wcscpy(left, L"estimating\x2026");
        } else {
            double sec = (double)(of - part) / g_speed;
            if (sec < 5) {
                wcscpy(left, L"a few seconds left");
            } else {
                WCHAR t[24];
                fmt_duration(t, ARRAYSIZE(t), sec);
                swprintf(left, ARRAYSIZE(left), L"about %s left", t);
            }
        }
        swprintf(detail, ARRAYSIZE(detail), L"%s of %s   \x00B7   %s/s   \x00B7   %s", done, whole, speed, left);
        bar(part, of);
    }
    set(IDC_TX_TITLE, title);
    set(IDC_TX_FILE, file);
    set(IDC_TX_DETAIL, detail);

    if (++g_ticks % 4 == 0) { /* tooltip once a second */
        WCHAR tip[128];
        if (transfer_tip(tip, ARRAYSIZE(tip))) tray_set_tip(tip);
    }
}

/* Text for a finished transfer: window title + detail, also used for the balloon. */
static void summary_text(int dir, WCHAR *title, size_t tn, WCHAR *detail, size_t dn)
{
    Slot *s = &g_s[dir];
    WCHAR size[24], speed[24], took[24];
    double sec = (double)(s->ended - s->started) / 1000.0;
    fmt_size(size, ARRAYSIZE(size), (UINT64)s->done);
    fmt_size(speed, ARRAYSIZE(speed), (UINT64)(sec > 0.05 ? (double)s->done / sec : 0));
    fmt_duration(took, ARRAYSIZE(took), sec);
    const WCHAR *peer = s->peer[0] ? s->peer : L"your phone";

    if (dir == TX_SEND) {
        switch (s->result) {
        case TXR_OK:
            swprintf(title, tn, L"Sent %ld file%s to %s", s->count, plural(s->count), peer);
            swprintf(detail, dn, L"%s in %s   \x00B7   %s/s average. On the phone: Downloads/ClipSync.", size, took, speed);
            break;
        case TXR_CANCELLED:
            swprintf(title, tn, L"Sending cancelled");
            swprintf(detail, dn, L"%ld of %ld files sent.", s->count, s->files);
            break;
        case TXR_NO_LINK:
            swprintf(title, tn, L"The phone didn't connect over Wi-Fi");
            swprintf(detail, dn, L"Nothing was sent. Files need the phone on the same Wi-Fi as this PC, or its hotspot.");
            break;
        default:
            swprintf(title, tn, L"Sending interrupted");
            swprintf(detail, dn, L"The phone disconnected. %ld of %ld files sent.", s->count, s->files);
        }
    } else if (s->result == TXR_OK) {
        if (s->count)
            swprintf(title, tn, L"Received %ld file%s from %s", s->count, plural(s->count), peer);
        else
            swprintf(title, tn, L"No files were received");
        swprintf(detail, dn, L"%s in %s   \x00B7   %s/s average. In Downloads\\ClipSync, and on the clipboard: "
                             L"press Ctrl+V in a folder.", size, took, speed);
    } else {
        swprintf(title, tn, g_stopping ? L"Receiving stopped" : L"Receiving interrupted");
        swprintf(detail, dn, L"%ld file%s received before it stopped.", s->count, plural(s->count));
    }
}

static void show_summary(int dir)
{
    WCHAR title[128], detail[200];
    Slot *s = &g_s[dir];
    summary_text(dir, title, ARRAYSIZE(title), detail, ARRAYSIZE(detail));
    g_summary = TRUE;
    KillTimer(g_dlg, TICK_TIMER);
    set(IDC_TX_TITLE, title);
    set(IDC_TX_FILE, L"");
    set(IDC_TX_DETAIL, detail);
    if (s->result == TXR_OK) bar(1, 1);
    show(IDC_TX_STOP, FALSE);
    show(IDC_TX_OPEN, dir == TX_RECV && s->count > 0);
    set(IDCANCEL, L"Close");
    if (s->result == TXR_OK) SetTimer(g_dlg, CLOSE_TIMER, CLOSE_MS, NULL);
}

/* Bottom-right, above other windows (it has no taskbar button, so it mustn't get lost behind them), no focus. */
static void bring_up(BOOL move)
{
    RECT wa, r;
    UINT flags = SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW;
    int x = 0, y = 0;
    if (move) {
        SystemParametersInfoW(SPI_GETWORKAREA, 0, &wa, 0);
        GetWindowRect(g_dlg, &r);
        int margin = MulDiv(12, GetDpiForWindow(g_dlg), 96);
        x = wa.right - (r.right - r.left) - margin;
        y = wa.bottom - (r.bottom - r.top) - margin;
    } else {
        flags |= SWP_NOMOVE; /* keep it where the user dragged it */
    }
    SetWindowPos(g_dlg, HWND_TOPMOST, x, y, 0, 0, flags);
}

static void open_folder(void)
{
    PIDLIST_ABSOLUTE pidl = g_last_file[0] ? ILCreateFromPathW(g_last_file) : NULL;
    if (pidl) {
        SHOpenFolderAndSelectItems(pidl, 0, NULL, 0); /* the folder, with the file selected */
        ILFree(pidl);
    } else {
        WCHAR dir[MAX_PATH];
        if (files_download_dir(dir, MAX_PATH)) ShellExecuteW(NULL, L"open", dir, NULL, NULL, SW_SHOWNORMAL);
    }
}

static void hide_window(void)
{
    KillTimer(g_dlg, CLOSE_TIMER);
    if (!g_summary && g_dir >= 0) g_hidden[g_dir] = TRUE;
    ShowWindow(g_dlg, SW_HIDE);
    if (g_summary) KillTimer(g_dlg, TICK_TIMER);
}

static INT_PTR CALLBACK dlg_proc(HWND dlg, UINT msg, WPARAM wp, LPARAM lp)
{
    (void)lp;
    switch (msg) {
    case WM_INITDIALOG: {
        LOGFONTW lf;
        GetObjectW((HFONT)SendMessageW(dlg, WM_GETFONT, 0, 0), sizeof lf, &lf);
        lf.lfWeight = FW_SEMIBOLD;
        lf.lfHeight = lf.lfHeight * 5 / 4;
        g_bold = CreateFontIndirectW(&lf);
        SendDlgItemMessageW(dlg, IDC_TX_TITLE, WM_SETFONT, (WPARAM)g_bold, FALSE);
        SendDlgItemMessageW(dlg, IDC_TX_BAR, PBM_SETRANGE32, 0, 1000);
        return FALSE; /* don't take focus */
    }
    case WM_TIMER:
        if (wp == TICK_TIMER) update_view();
        else if (wp == CLOSE_TIMER) hide_window();
        return TRUE;
    case WM_COMMAND:
        switch (LOWORD(wp)) {
        case IDC_TX_STOP:
            if (g_dir == TX_SEND) {
                link_cancel_files();
            } else {
                g_stopping = TRUE;
                link_disconnect(); /* the protocol has no "stop sending"; the phone reconnects by itself */
            }
            EnableWindow(GetDlgItem(dlg, IDC_TX_STOP), FALSE);
            set(IDC_TX_STOP, L"Stopping\x2026");
            return TRUE;
        case IDC_TX_OPEN:
            open_folder();
            hide_window();
            return TRUE;
        case IDCANCEL: /* Hide / Close / Esc / the title bar's X */
            hide_window();
            return TRUE;
        }
        break;
    }
    return FALSE;
}

/* Shows the live view of a slot: called when a transfer starts. */
static void show_slot(int dir)
{
    if (!g_dlg) {
        g_dlg = CreateDialogParamW(GetModuleHandleW(NULL), MAKEINTRESOURCEW(IDD_TRANSFER), NULL, dlg_proc, 0);
        if (!g_dlg) return;
    }
    g_dir = dir;
    g_summary = FALSE;
    g_stopping = FALSE;
    g_speed = 0;
    g_prev_tick = 0;
    g_prev_done = 0;
    KillTimer(g_dlg, CLOSE_TIMER);
    set(IDC_TX_STOP, dir == TX_SEND ? L"Cancel" : L"Stop");
    EnableWindow(GetDlgItem(g_dlg, IDC_TX_STOP), TRUE);
    show(IDC_TX_STOP, TRUE);
    show(IDC_TX_OPEN, FALSE);
    set(IDCANCEL, L"Hide");
    update_view();
    SetTimer(g_dlg, TICK_TIMER, TICK_MS, NULL);
    if (!g_hidden[dir]) bring_up(!IsWindowVisible(g_dlg)); /* never steals focus from what you're doing */
}

void transfer_on_message(WPARAM wp, LPARAM lp)
{
    int dir = (int)wp, other = 1 - dir;
    if (lp == TXE_START) {
        g_hidden[dir] = FALSE;
        if (g_dir < 0 || g_summary || !g_s[g_dir].active || !transfer_window_visible()) show_slot(dir);
        return;
    }
    /* TXE_END */
    BOOL shown = g_dir == dir && transfer_window_visible() && !g_hidden[dir];
    if (shown && !g_s[other].active) {
        show_summary(dir);
    } else {
        WCHAR title[128], detail[200], text[340];
        summary_text(dir, title, ARRAYSIZE(title), detail, ARRAYSIZE(detail));
        swprintf(text, ARRAYSIZE(text), L"%s. %s", title, detail);
        WCHAR folder[MAX_PATH] = L"";
        if (dir == TX_RECV) files_download_dir(folder, MAX_PATH);
        tray_balloon(text, dir == TX_RECV ? folder : NULL);
        if (g_dir == dir) {
            if (g_s[other].active) show_slot(other);
            else if (g_dlg) KillTimer(g_dlg, TICK_TIMER); /* nothing left to watch */
        }
    }
    tray_update();
}

void transfer_received(const FileList *files)
{
    if (files->n) wcsncpy(g_last_file, files->paths[files->n - 1], MAX_PATH - 1);
}

void transfer_show(void)
{
    for (int dir = 0; dir < 2; dir++) {
        if (!g_s[dir].active) continue;
        g_hidden[dir] = FALSE;
        if (g_dlg && g_dir == dir && !g_summary)
            bring_up(!IsWindowVisible(g_dlg)); /* same transfer: keep its speed reading, just bring it back */
        else
            show_slot(dir);
        return;
    }
}

BOOL transfer_window_visible(void) { return g_dlg && IsWindowVisible(g_dlg); }

BOOL transfer_dialog_message(MSG *msg) { return g_dlg && IsDialogMessageW(g_dlg, msg); }

BOOL transfer_tip(WCHAR *tip, size_t n)
{
    for (int dir = 0; dir < 2; dir++) {
        Slot *s = &g_s[dir];
        if (!s->active) continue;
        if (s->waiting) {
            swprintf(tip, n, L"ClipSync: waiting for the phone to send files");
        } else {
            WCHAR speed[24];
            fmt_size(speed, ARRAYSIZE(speed), (UINT64)(g_speed > 0 ? g_speed : 0));
            if (dir == TX_SEND)
                swprintf(tip, n, L"ClipSync: sending file %ld of %ld \x00B7 %d%% \x00B7 %s/s", s->file, s->files,
                         s->total ? (int)(s->done * 100 / s->total) : 0, speed);
            else
                swprintf(tip, n, L"ClipSync: receiving file %ld \x00B7 %d%% \x00B7 %s/s", s->file,
                         s->file_size ? (int)(s->file_done * 100 / s->file_size) : 0, speed);
        }
        return TRUE;
    }
    return FALSE;
}

void transfer_init(void)
{
    INITCOMMONCONTROLSEX icc = { sizeof icc, ICC_PROGRESS_CLASS };
    InitializeCriticalSection(&g_cs);
    InitCommonControlsEx(&icc);
}
