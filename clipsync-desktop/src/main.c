#include "common.h"
#include "clip.h"
#include "config.h"
#include "crypto.h"
#include "files.h"
#include "firewall.h"
#include "icons.h"
#include "link.h"
#include "pair.h"
#include "proto.h"
#include "settings.h"
#include "transfer.h"
#include <shellapi.h>
#include <stdio.h>

enum {
    IDM_SEND_FILES = 100, IDM_CANCEL, IDM_SHOW_TRANSFER, IDM_PAUSE, IDM_AUTOSTART, IDM_LINK, IDM_SETTINGS,
    IDM_FIREWALL, IDM_UNINSTALL, IDM_EXIT,
};

HWND g_hwnd;
static NOTIFYICONDATAW g_nid;
static UINT g_wm_taskbar;
static HICON g_icon;
static WCHAR g_balloon_folder[MAX_PATH]; /* opened when the balloon is clicked */
static BOOL g_balloon_firewall;          /* clicking the balloon fixes the firewall */
static BOOL g_firewall_blocked;          /* known to block the phone */
static BOOL g_announced;                 /* "Connected to ..." shown once per run */

/* Only the tooltip: cheap enough for the transfer window's once-a-second progress. */
void tray_set_tip(const WCHAR *tip)
{
    g_nid.uFlags = NIF_TIP | NIF_SHOWTIP;
    wcsncpy(g_nid.szTip, tip, ARRAYSIZE(g_nid.szTip) - 1);
    Shell_NotifyIconW(NIM_MODIFY, &g_nid);
}

void tray_update(void)
{
    WCHAR peer[65], tip[128];
    int st = link_state(peer, ARRAYSIZE(peer));
    int icon = g_cfg.paused ? ICON_PAUSED : st == LINK_WIFI ? ICON_WIFI : st == LINK_BT ? ICON_BT : ICON_WAITING;

    if (!g_cfg.has_key)
        wcscpy(tip, L"ClipSync: no phone linked (right-click > Link a phone)");
    else if (transfer_tip(tip, ARRAYSIZE(tip)))
        ;
    else if (g_cfg.paused)
        wcscpy(tip, L"ClipSync: paused");
    else if (st != LINK_NONE)
        swprintf(tip, ARRAYSIZE(tip), L"ClipSync: %s (%s)", peer[0] ? peer : L"phone",
                 st == LINK_WIFI ? L"Wi-Fi" : L"Bluetooth");
    else
        wcscpy(tip, L"ClipSync: waiting for phone");

    HICON old = g_icon;
    g_icon = icon_make(icon);
    g_nid.uFlags = NIF_ICON | NIF_TIP | NIF_SHOWTIP;
    g_nid.hIcon = g_icon;
    wcsncpy(g_nid.szTip, tip, ARRAYSIZE(g_nid.szTip) - 1);
    Shell_NotifyIconW(NIM_MODIFY, &g_nid);
    if (old) DestroyIcon(old);
}

static void tray_add(void)
{
    g_nid.cbSize = sizeof g_nid;
    g_nid.hWnd = g_hwnd;
    g_nid.uID = 1;
    g_nid.uFlags = NIF_MESSAGE | NIF_SHOWTIP;
    g_nid.uCallbackMessage = WM_APP_TRAY;
    Shell_NotifyIconW(NIM_ADD, &g_nid);
    g_nid.uVersion = NOTIFYICON_VERSION_4;
    Shell_NotifyIconW(NIM_SETVERSION, &g_nid);
    tray_update();
}

static void balloon(const WCHAR *text, const WCHAR *folder)
{
    NOTIFYICONDATAW n = g_nid;
    n.uFlags = NIF_INFO;
    wcscpy(n.szInfoTitle, L"ClipSync");
    wcsncpy(n.szInfo, text, ARRAYSIZE(n.szInfo) - 1);
    n.dwInfoFlags = NIIF_NONE | NIIF_RESPECT_QUIET_TIME;
    wcsncpy(g_balloon_folder, folder ? folder : L"", MAX_PATH - 1);
    g_balloon_firewall = FALSE;
    Shell_NotifyIconW(NIM_MODIFY, &n);
}

void tray_balloon(const WCHAR *text, const WCHAR *folder) { balloon(text, folder); }

static DWORD WINAPI firewall_check_main(void *unused)
{
    (void)unused;
    PostMessageW(g_hwnd, WM_APP_FIREWALL, firewall_ok(), 0);
    return 0;
}

/* One admin prompt, then the phone can reach this PC on any network (home, campus, hotspot). */
static void fix_firewall(BOOL explain)
{
    if (explain &&
        MessageBoxW(g_hwnd,
                    L"So your phone can connect, ClipSync needs to be allowed through Windows Firewall.\n\n"
                    L"Windows will ask for permission next. Click Yes.",
                    L"ClipSync", MB_ICONINFORMATION | MB_OKCANCEL) != IDOK)
        return;
    if (firewall_fix(g_hwnd)) {
        config_set_firewall_done();
        g_firewall_blocked = FALSE;
        balloon(L"Done. Your phone can now connect to this PC.", NULL);
    } else {
        balloon(L"The firewall wasn't changed. Right-click the ClipSync icon > \"Let the phone connect\" to try again.", NULL);
    }
}

/* Linking over Wi-Fi needs the firewall open first, so check that before showing the link window. */
static void link_phone(void)
{
    if (!config_firewall_done()) {
        if (firewall_ok()) config_set_firewall_done();
        else fix_firewall(TRUE);
    }
    pair_show(g_hwnd);
    tray_update();
}

/* Removes every trace: startup entry, Send-to shortcut, firewall rule, settings, then the program itself. */
static void uninstall(void)
{
    if (MessageBoxW(g_hwnd,
                    L"Remove ClipSync from this PC?\n\n"
                    L"This removes its settings, the link with your phone, the firewall permission, the startup entry "
                    L"and the \"Send to\" shortcut, then deletes the program.\n\n"
                    L"Files you received stay in Downloads\\ClipSync.",
                    L"Uninstall ClipSync", MB_ICONQUESTION | MB_YESNO | MB_DEFBUTTON2) != IDYES)
        return;
    link_stop();
    RemoveClipboardFormatListener(g_hwnd);
    autostart_set(FALSE);
    files_remove_sendto();
    BOOL firewall_cleaned = !firewall_has_rules() || firewall_remove(g_hwnd);
    config_delete_all();
    Shell_NotifyIconW(NIM_DELETE, &g_nid);
    files_delete_self_on_exit();
    MessageBoxW(NULL,
                firewall_cleaned
                    ? L"ClipSync was removed from this PC.\n\nOn your phone, uninstall the ClipSync app as usual."
                    : L"ClipSync was removed from this PC, except its firewall rule (Windows didn't allow the change). "
                      L"You can delete the \"ClipSync\" rule in Windows Defender Firewall > Advanced settings.\n\n"
                      L"On your phone, uninstall the ClipSync app as usual.",
                L"ClipSync", MB_ICONINFORMATION);
    ExitProcess(0);
}

static void set_paused(BOOL paused)
{
    config_lock();
    g_cfg.paused = paused;
    config_unlock();
    config_save();
    tray_update();
}

static void command(int id)
{
    switch (id) {
    case IDM_SEND_FILES: {
        FileList fl = { 0 };
        if (clip_get_files(g_hwnd, &fl))
            link_send_files(&fl);
        else
            balloon(L"Copy some files in Explorer first, then choose \"Send copied files to phone\".", NULL);
        break;
    }
    case IDM_CANCEL:
        link_cancel_files();
        break;
    case IDM_SHOW_TRANSFER:
        transfer_show();
        break;
    case IDM_PAUSE:
        set_paused(!g_cfg.paused);
        break;
    case IDM_AUTOSTART:
        autostart_set(!autostart_get());
        break;
    case IDM_LINK:
        link_phone();
        break;
    case IDM_SETTINGS:
        if (settings_show(g_hwnd)) link_restart();
        tray_update();
        break;
    case IDM_FIREWALL:
        fix_firewall(FALSE);
        break;
    case IDM_UNINSTALL:
        uninstall();
        break;
    case IDM_EXIT:
        DestroyWindow(g_hwnd);
        break;
    }
}

static void show_menu(void)
{
    WCHAR peer[65], status[128];
    int st = link_state(peer, ARRAYSIZE(peer));
    if (!g_cfg.has_key)
        wcscpy(status, L"No phone linked yet");
    else if (st == LINK_NONE)
        wcscpy(status, g_cfg.paused ? L"Paused" : L"Not connected: waiting for phone");
    else
        swprintf(status, ARRAYSIZE(status), L"Connected: %s (%s)%s", peer[0] ? peer : L"phone",
                 st == LINK_WIFI ? L"Wi-Fi" : L"Bluetooth", g_cfg.paused ? L", paused" : L"");

    HMENU m = CreatePopupMenu();
    AppendMenuW(m, MF_STRING | MF_GRAYED, 0, status);
    if (g_firewall_blocked) AppendMenuW(m, MF_STRING, IDM_FIREWALL, L"Let the phone connect (firewall)");
    AppendMenuW(m, MF_SEPARATOR, 0, NULL);
    if (tx_active(TX_SEND) || tx_active(TX_RECV))
        AppendMenuW(m, MF_STRING, IDM_SHOW_TRANSFER, L"Show file transfer");
    if (tx_active(TX_SEND))
        AppendMenuW(m, MF_STRING, IDM_CANCEL, L"Cancel sending files");
    else
        AppendMenuW(m, MF_STRING, IDM_SEND_FILES, L"Send copied files to phone");
    AppendMenuW(m, MF_SEPARATOR, 0, NULL);
    AppendMenuW(m, MF_STRING | (g_cfg.paused ? MF_CHECKED : 0), IDM_PAUSE, L"Pause sync");
    AppendMenuW(m, MF_STRING | (autostart_get() ? MF_CHECKED : 0), IDM_AUTOSTART, L"Start with Windows");
    AppendMenuW(m, MF_STRING | (g_cfg.has_key ? 0 : MF_DEFAULT), IDM_LINK,
                g_cfg.has_key ? L"Link another phone\x2026" : L"Link a phone\x2026");
    AppendMenuW(m, MF_STRING, IDM_SETTINGS, L"Settings\x2026");
    AppendMenuW(m, MF_SEPARATOR, 0, NULL);
    AppendMenuW(m, MF_STRING, IDM_UNINSTALL, L"Uninstall ClipSync\x2026");
    AppendMenuW(m, MF_STRING, IDM_EXIT, L"Exit");

    POINT pt;
    GetCursorPos(&pt);
    SetForegroundWindow(g_hwnd);
    int id = (int)TrackPopupMenu(m, TPM_RETURNCMD | TPM_NONOTIFY | TPM_RIGHTBUTTON, pt.x, pt.y, 0, g_hwnd, NULL);
    PostMessageW(g_hwnd, WM_NULL, 0, 0);
    DestroyMenu(m);
    if (id) command(id);
}

/* Paths from "clipsync.exe --send ...": NUL-separated, double-NUL terminated. */
static void receive_send_request(const COPYDATASTRUCT *cd)
{
    const WCHAR *p = cd->lpData, *end = p + cd->cbData / sizeof(WCHAR);
    FileList fl = { 0 };
    while (p < end && *p) {
        size_t n = wcsnlen(p, (size_t)(end - p));
        WCHAR *path = malloc((n + 1) * sizeof(WCHAR));
        if (!path) break;
        memcpy(path, p, n * sizeof(WCHAR));
        path[n] = 0;
        file_list_add(&fl, path);
        p += n + 1;
    }
    if (fl.n) link_send_files(&fl);
}

static LRESULT CALLBACK wnd_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    switch (msg) {
    case WM_APP_TRAY:
        switch (LOWORD(lp)) {
        case NIN_SELECT:
        case NIN_KEYSELECT:
            set_paused(!g_cfg.paused);
            break;
        case WM_CONTEXTMENU:
            show_menu();
            break;
        case NIN_BALLOONUSERCLICK:
            if (g_balloon_firewall) fix_firewall(FALSE);
            else if (g_balloon_folder[0]) ShellExecuteW(NULL, L"open", g_balloon_folder, NULL, NULL, SW_SHOWNORMAL);
            break;
        }
        return 0;
    case WM_CLIPBOARDUPDATE:
        SetTimer(hwnd, CLIP_TIMER, CLIP_DELAY_MS, NULL); /* restarts on every change; see clip_changed() */
        return 0;
    case WM_TIMER:
        if (wp == CLIP_TIMER) {
            KillTimer(hwnd, CLIP_TIMER);
            clip_changed(hwnd);
        }
        return 0;
    case WM_APP_STATE: {
        WCHAR peer[65], text[160];
        int st = link_state(peer, ARRAYSIZE(peer));
        tray_update();
        if (st != LINK_NONE) {
            if (!g_announced) {
                g_announced = TRUE;
                swprintf(text, ARRAYSIZE(text), L"Connected to %s over %s. Copy something to try it.",
                         peer[0] ? peer : L"your phone", st == LINK_WIFI ? L"Wi-Fi" : L"Bluetooth");
                balloon(text, NULL);
            }
            clip_on_connected(hwnd);
        }
        return 0;
    }
    case WM_APP_FIREWALL:
        g_firewall_blocked = !wp;
        if (wp) {
            config_set_firewall_done();
        } else if (g_cfg.has_key) {
            balloon(L"Your phone can't reach this PC yet. Click here to allow ClipSync through the firewall.", NULL);
            g_balloon_firewall = TRUE;
        }
        return 0;
    case WM_POWERBROADCAST:
        if (wp == PBT_APMRESUMEAUTOMATIC) link_nudge(); /* woke from sleep: get the phone back now */
        return TRUE;
    case WM_APP_RECV_TEXT:
        clip_set_text(hwnd, (const char *)lp, (size_t)wp);
        free((void *)lp);
        return 0;
    case WM_APP_RECV_IMAGE: {
        RecvImage *ri = (RecvImage *)lp;
        clip_set_image(hwnd, ri);
        if (ri->dib) GlobalFree(ri->dib);
        free(ri->png);
        free(ri);
        return 0;
    }
    case WM_APP_RECV_FILES: { /* the transfer window (or its balloon) reports it; here: paste-ready + "Open folder" */
        FileList *fl = (FileList *)lp;
        clip_set_files(hwnd, fl);
        transfer_received(fl);
        file_list_free(fl);
        free(fl);
        return 0;
    }
    case WM_APP_NOTIFY:
        balloon((const WCHAR *)lp, NULL);
        free((void *)lp);
        return 0;
    case WM_APP_TX:
        transfer_on_message(wp, lp);
        return 0;
    case WM_COMMAND:
        command(LOWORD(wp));
        return 0;
    case WM_COPYDATA: {
        const COPYDATASTRUCT *cd = (const COPYDATASTRUCT *)lp;
        if (cd->dwData != CS_COPYDATA_SEND) return FALSE;
        receive_send_request(cd);
        return TRUE;
    }
    case WM_SETTINGCHANGE:
        if (lp && !wcscmp((const WCHAR *)lp, L"ImmersiveColorSet")) tray_update();
        return 0;
    case WM_DESTROY:
        RemoveClipboardFormatListener(hwnd);
        Shell_NotifyIconW(NIM_DELETE, &g_nid);
        PostQuitMessage(0);
        return 0;
    }
    if (msg == g_wm_taskbar) { /* Explorer restarted */
        tray_add();
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

/* "clipsync.exe --send a.txt b.jpg" (Explorer's Send to menu) hands the files to the running instance. */
static int send_to_running(int n, WCHAR **paths)
{
    HWND w = FindWindowW(CS_WND_CLASS, NULL);
    if (!w) {
        MessageBoxW(NULL, L"ClipSync isn't running. Start it first.", L"ClipSync", MB_ICONWARNING);
        return 1;
    }
    size_t chars = 1;
    WCHAR **full = calloc((size_t)n, sizeof *full);
    for (int i = 0; full && i < n; i++) {
        DWORD len = GetFullPathNameW(paths[i], 0, NULL, NULL);
        if (!len || !(full[i] = malloc(len * sizeof(WCHAR)))) continue;
        GetFullPathNameW(paths[i], len, full[i], NULL);
        chars += wcslen(full[i]) + 1;
    }
    WCHAR *buf = calloc(chars, sizeof(WCHAR)), *p = buf;
    for (int i = 0; buf && full && i < n; i++) {
        if (!full[i]) continue;
        wcscpy(p, full[i]);
        p += wcslen(p) + 1;
    }
    if (buf) {
        COPYDATASTRUCT cd = { CS_COPYDATA_SEND, (DWORD)(chars * sizeof(WCHAR)), buf };
        SendMessageW(w, WM_COPYDATA, 0, (LPARAM)&cd);
    }
    return 0;
}

/* "clipsync.exe --set-password <pw>": scripted setup (no dialog). */
static int set_password(const WCHAR *pw)
{
    if (!crypto_init()) return 1;
    config_load();
    if (!config_set_password(pw)) return 1;
    config_save();
    return 0;
}

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE prev, PWSTR cmdline, int show)
{
    (void)prev;
    (void)cmdline;
    (void)show;
    int argc;
    WCHAR **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (argv && argc >= 2 && !wcscmp(argv[1], L"--send")) return send_to_running(argc - 2, argv + 2);
    if (argv && argc >= 3 && !wcscmp(argv[1], L"--set-password")) return set_password(argv[2]);

    CreateMutexW(NULL, TRUE, L"Local\\ClipSync.Desktop");
    if (GetLastError() == ERROR_ALREADY_EXISTS) return 0;

    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) || !crypto_init()) {
        MessageBoxW(NULL, L"ClipSync couldn't start networking or encryption.", L"ClipSync", MB_ICONERROR);
        return 1;
    }
    config_load();
    clip_init();

    WNDCLASSW wc = { 0 };
    wc.lpfnWndProc = wnd_proc;
    wc.hInstance = inst;
    wc.lpszClassName = CS_WND_CLASS;
    RegisterClassW(&wc);
    g_hwnd = CreateWindowExW(WS_EX_TOOLWINDOW, CS_WND_CLASS, L"ClipSync", WS_POPUP, 0, 0, 0, 0, NULL, NULL, inst, NULL);
    if (!g_hwnd) return 1;
    g_wm_taskbar = RegisterWindowMessageW(L"TaskbarCreated");

    transfer_init();
    link_start();
    tray_add();
    AddClipboardFormatListener(g_hwnd);
    files_install_sendto();
    if (!g_cfg.has_key) {
        PostMessageW(g_hwnd, WM_COMMAND, IDM_LINK, 0); /* first run: firewall, then link the phone */
    } else if (!config_firewall_done()) {
        HANDLE t = CreateThread(NULL, 0, firewall_check_main, NULL, 0, NULL);
        if (t) CloseHandle(t);
    }

    MSG msg;
    while (GetMessageW(&msg, NULL, 0, 0) > 0) {
        if (transfer_dialog_message(&msg)) continue; /* Tab/Enter/Esc in the transfer window */
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    link_stop();
    return 0;
}
