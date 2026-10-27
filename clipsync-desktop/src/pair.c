#include "pair.h"
#include "config.h"
#include "crypto.h"
#include "link.h"
#include "proto.h"
#include "resource.h"
#include <stdio.h>

#define WM_PAIR_REQUEST (WM_APP + 50) /* lParam: PairReq* (lives on the link thread's stack) */
#define WM_PAIR_DONE    (WM_APP + 51) /* wParam: linked?, lParam: malloc'd phone name or NULL */
#define DECIDE_TIMER    1
#define DECIDE_MS       120000

typedef struct {
    HANDLE decided;
    volatile LONG accept;
    WCHAR phone[65];
    WCHAR code[8];
} PairReq;

static HWND volatile g_dlg; /* the open "Link a phone" window */
static volatile LONG g_busy; /* one link exchange at a time */
static PairReq *g_req;       /* request on screen (UI thread only) */
static BOOL g_shown;         /* a code was shown in this window */
static HFONT g_code_font;

BOOL pair_mode(void) { return g_dlg != NULL; }

/* ---- link thread ---- */

static DWORD WINAPI pair_main(void *arg)
{
    static const char l_commit[] = "ClipSync commit", l_pair[] = "ClipSync pair", l_key[] = "ClipSync key",
                      l_ok[] = "ClipSync paired", l_done[] = "ClipSync done";
    SOCKET s = (SOCKET)(UINT_PTR)arg;
    BYTE commit[32], pk_pc[64], in[81 + 64], check[32], z[32], t[32], key[32], mac[32];
    BCRYPT_KEY_HANDLE priv = NULL;
    PairReq req = { 0 };
    BOOL linked = FALSE, had_key = g_cfg.has_key;
    HWND dlg = g_dlg;

    /* 1. phone: "CSYP" (already read) + commitment to its key; 2. PC: its key; 3. phone: its key, nonce, name */
    if (!proto_recv_all(s, commit, 32) || !(priv = crypto_ecdh_new(pk_pc)) || !proto_send_all(s, pk_pc, 64) ||
        !proto_recv_all(s, in, 81) || in[80] > 64 || (in[80] && !proto_recv_all(s, in + 81, in[80])))
        goto out;
    const BYTE *pk_phone = in, *nonce = in + 64;
    Buf c[3] = { { l_commit, sizeof l_commit - 1 }, { pk_phone, 64 }, { nonce, 16 } };
    crypto_sha256(c, 3, check);
    if (memcmp(check, commit, 32) || !crypto_ecdh_shared(priv, pk_phone, z)) goto out;

    Buf tp[4] = { { l_pair, sizeof l_pair - 1 }, { pk_phone, 64 }, { pk_pc, 64 }, { nonce, 16 } };
    crypto_sha256(tp, 4, t);
    Buf kp[2] = { { l_key, sizeof l_key - 1 }, { t, 32 } };
    crypto_hmac(z, kp, 2, key);

    /* 4. Show the code; the user compares it with the phone and clicks Link. */
    UINT32 sas = get_be32(t) % 1000000;
    swprintf(req.code, ARRAYSIZE(req.code), L"%03u %03u", sas / 1000, sas % 1000);
    WCHAR *name = wide_from_utf8((const char *)in + 81, in[80]);
    wcsncpy(req.phone, name && name[0] ? name : L"A phone", ARRAYSIZE(req.phone) - 1);
    free(name);
    req.decided = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (!req.decided || !dlg || !PostMessageW(dlg, WM_PAIR_REQUEST, 0, (LPARAM)&req)) goto out;
    /* The window decides (Link, Cancel, close, or its own 2-minute timer). If it's gone, that's a no. */
    while (WaitForSingleObject(req.decided, 1000) == WAIT_TIMEOUT) {
        if (g_dlg != dlg) goto out;
    }
    if (!req.accept) {
        BYTE no = 0;
        proto_send_all(s, &no, 1);
        goto out;
    }

    /* 5. PC: yes + proof of the key + its name; 6. phone: "OK" + proof. Store the key only after that. */
    BYTE reply[18 + 64], ack[18];
    Buf okp[1] = { { l_ok, sizeof l_ok - 1 } };
    crypto_hmac(key, okp, 1, mac);
    reply[0] = 1;
    memcpy(reply + 1, mac, 16);
    int nl = 0;
    char *pc = utf8_from_wide(g_cfg.name, (int)wcslen(g_cfg.name), &nl);
    nl = pc ? utf8_clip(pc, nl, 64) : 0;
    reply[17] = (BYTE)nl;
    if (nl) memcpy(reply + 18, pc, (size_t)nl);
    free(pc);
    DWORD timeout = 30000;
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, (char *)&timeout, sizeof timeout);
    if (!proto_send_all(s, reply, 18 + (size_t)nl) || !proto_recv_all(s, ack, sizeof ack) || memcmp(ack, "OK", 2)) goto out;
    Buf dp[1] = { { l_done, sizeof l_done - 1 } };
    crypto_hmac(key, dp, 1, mac);
    if (memcmp(ack + 2, mac, 16)) goto out;

    config_set_key(key, req.phone);
    if (!had_key) autostart_set(TRUE);
    linked = TRUE;
out:
    if (priv) BCryptDestroyKey(priv);
    if (req.decided) CloseHandle(req.decided);
    SecureZeroMemory(z, sizeof z);
    SecureZeroMemory(key, sizeof key);
    closesocket(s);
    if (g_dlg == dlg && dlg) PostMessageW(dlg, WM_PAIR_DONE, linked, linked ? (LPARAM)_wcsdup(req.phone) : 0);
    if (linked) link_restart(); /* drop any session made with the old key */
    InterlockedExchange(&g_busy, 0);
    return 0;
}

void pair_start(SOCKET s, int kind)
{
    (void)kind;
    HANDLE t = NULL;
    if (pair_mode() && !InterlockedCompareExchange(&g_busy, 1, 0)) {
        t = CreateThread(NULL, 0, pair_main, (void *)(UINT_PTR)s, 0, NULL);
        if (!t) InterlockedExchange(&g_busy, 0);
    }
    if (t) CloseHandle(t);
    else closesocket(s);
}

/* ---- window ---- */

static void show_waiting(HWND dlg)
{
    SetDlgItemTextW(dlg, IDC_PAIR_TEXT,
                    L"On your phone:\n"
                    L"1.  Open the ClipSync app.\n"
                    L"2.  Tap \"Link with PC\".\n\n"
                    L"The phone needs the same Wi-Fi as this PC (or its hotspot), or a Bluetooth pairing with it.");
    SetDlgItemTextW(dlg, IDC_PAIR_CODE, L"\x2022 \x2022 \x2022");
    EnableWindow(GetDlgItem(dlg, IDOK), FALSE);
}

/* Hands the user's answer to the link thread. */
static void decide(HWND dlg, BOOL accept)
{
    KillTimer(dlg, DECIDE_TIMER);
    if (!g_req) return;
    PairReq *r = g_req;
    g_req = NULL;
    r->accept = accept;
    SetEvent(r->decided);
    EnableWindow(GetDlgItem(dlg, IDOK), FALSE);
}

static INT_PTR CALLBACK pair_proc(HWND dlg, UINT msg, WPARAM wp, LPARAM lp)
{
    switch (msg) {
    case WM_INITDIALOG: {
        HDC dc = GetDC(dlg);
        LOGFONTW lf = { 0 };
        lf.lfHeight = -MulDiv(24, GetDeviceCaps(dc, LOGPIXELSY), 72);
        lf.lfWeight = FW_SEMIBOLD;
        lf.lfQuality = CLEARTYPE_QUALITY;
        wcscpy(lf.lfFaceName, L"Segoe UI");
        ReleaseDC(dlg, dc);
        g_code_font = CreateFontIndirectW(&lf);
        SendDlgItemMessageW(dlg, IDC_PAIR_CODE, WM_SETFONT, (WPARAM)g_code_font, TRUE);
        g_shown = FALSE;
        g_dlg = dlg;
        show_waiting(dlg);
        SetForegroundWindow(dlg);
        return TRUE;
    }
    case WM_PAIR_REQUEST: {
        WCHAR text[200];
        g_req = (PairReq *)lp;
        g_shown = TRUE;
        swprintf(text, ARRAYSIZE(text),
                 L"%s wants to link with this PC.\n\nCheck that the phone shows the same code, then click Link.",
                 g_req->phone);
        SetDlgItemTextW(dlg, IDC_PAIR_TEXT, text);
        SetDlgItemTextW(dlg, IDC_PAIR_CODE, g_req->code);
        EnableWindow(GetDlgItem(dlg, IDOK), TRUE);
        SendMessageW(dlg, DM_SETDEFID, IDOK, 0);
        SetFocus(GetDlgItem(dlg, IDOK));
        SetTimer(dlg, DECIDE_TIMER, DECIDE_MS, NULL);
        SetForegroundWindow(dlg);
        FlashWindow(dlg, TRUE);
        return TRUE;
    }
    case WM_TIMER:
        decide(dlg, FALSE);
        show_waiting(dlg);
        return TRUE;
    case WM_PAIR_DONE: {
        WCHAR text[200];
        WCHAR *phone = (WCHAR *)lp;
        if (wp) {
            swprintf(text, ARRAYSIZE(text),
                     L"Linked with %s.\n\nFrom now on they connect by themselves, over Wi-Fi or Bluetooth. "
                     L"You won't need to do this again.",
                     phone ? phone : L"your phone");
            SetDlgItemTextW(dlg, IDC_PAIR_TEXT, text);
            SetDlgItemTextW(dlg, IDC_PAIR_CODE, L"\x2714  Linked");
            SetDlgItemTextW(dlg, IDCANCEL, L"Done");
            ShowWindow(GetDlgItem(dlg, IDOK), SW_HIDE);
        } else if (g_shown) {
            show_waiting(dlg);
            SetDlgItemTextW(dlg, IDC_PAIR_TEXT,
                            L"That didn't finish (cancelled, timed out, or the phone disconnected).\n\n"
                            L"Tap \"Link with PC\" on the phone to try again.");
        }
        free(phone);
        return TRUE;
    }
    case WM_COMMAND:
        if (LOWORD(wp) == IDOK) {
            decide(dlg, TRUE);
            SetDlgItemTextW(dlg, IDC_PAIR_CODE, L"\x2026");
            return TRUE;
        }
        if (LOWORD(wp) == IDCANCEL) {
            EndDialog(dlg, 0);
            return TRUE;
        }
        break;
    case WM_DESTROY:
        decide(dlg, FALSE);
        g_dlg = NULL;
        DeleteObject(g_code_font);
        g_code_font = NULL;
        break;
    }
    return FALSE;
}

void pair_show(HWND owner)
{
    if (g_dlg) {
        SetForegroundWindow(g_dlg);
        return;
    }
    DialogBoxParamW(GetModuleHandleW(NULL), MAKEINTRESOURCEW(IDD_PAIR), owner, pair_proc, 0);
}
