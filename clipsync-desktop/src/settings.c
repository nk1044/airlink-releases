#include "settings.h"
#include "config.h"
#include "pair.h"
#include "resource.h"
#include <commctrl.h>
#include <shellapi.h>
#include <stdio.h>

static void address_text(WCHAR *out, size_t n)
{
    LanAddr lan[8];
    int count = lan_addresses(lan, 8);
    out[0] = 0;
    for (int i = 0; i < count; i++) {
        WCHAR ip[INET_ADDRSTRLEN];
        if (!InetNtopW(AF_INET, &lan[i].ip, ip, ARRAYSIZE(ip))) continue;
        size_t len = wcslen(out);
        swprintf(out + len, n - len, L"%s%s", len ? L", " : L"", ip);
    }
}

static void show_linked(HWND dlg)
{
    WCHAR text[120];
    if (!g_cfg.has_key)
        wcscpy(text, L"No phone linked yet.");
    else
        swprintf(text, ARRAYSIZE(text), L"Linked with: %s", g_cfg.phone[0] ? g_cfg.phone : L"your phone");
    SetDlgItemTextW(dlg, IDC_LINKED, text);
    SetDlgItemTextW(dlg, IDC_LINK, g_cfg.has_key ? L"Link another phone\x2026" : L"Link a phone\x2026");
}

static INT_PTR CALLBACK dlg_proc(HWND dlg, UINT msg, WPARAM wp, LPARAM lp)
{
    (void)lp;
    switch (msg) {
    case WM_INITDIALOG: {
        WCHAR addr[256], text[320];
        show_linked(dlg);
        SetDlgItemTextW(dlg, IDC_NAME, g_cfg.name);
        SetDlgItemInt(dlg, IDC_PORT, (UINT)g_cfg.port, FALSE);
        CheckDlgButton(dlg, IDC_BT, g_cfg.bluetooth ? BST_CHECKED : BST_UNCHECKED);
        SendDlgItemMessageW(dlg, IDC_NAME, EM_LIMITTEXT, ARRAYSIZE(g_cfg.name) - 1, 0);
        address_text(addr, ARRAYSIZE(addr));
        swprintf(text, ARRAYSIZE(text),
                 L"This PC's address: %s\nThe phone finds this PC by itself. Type the address in the phone app only if it "
                 L"says it can't find the PC.",
                 addr[0] ? addr : L"(not on a network)");
        SetDlgItemTextW(dlg, IDC_ADDR, text);
        SetForegroundWindow(dlg);
        return TRUE;
    }
    case WM_COMMAND:
        switch (LOWORD(wp)) {
        case IDC_LINK:
            pair_show(dlg);
            show_linked(dlg);
            return TRUE;
        case IDC_BTOPEN:
            ShellExecuteW(dlg, L"open", L"ms-settings:bluetooth", NULL, NULL, SW_SHOWNORMAL);
            return TRUE;
        case IDCANCEL:
            EndDialog(dlg, FALSE);
            return TRUE;
        case IDOK: {
            WCHAR name[ARRAYSIZE(g_cfg.name)];
            BOOL port_ok;
            UINT port = GetDlgItemInt(dlg, IDC_PORT, &port_ok, FALSE);
            GetDlgItemTextW(dlg, IDC_NAME, name, ARRAYSIZE(name));
            if (!port_ok || port < 1024 || port > 65535) {
                MessageBoxW(dlg, L"The port must be between 1024 and 65535.", L"ClipSync", MB_ICONINFORMATION);
                return TRUE;
            }
            config_lock();
            if (name[0]) wcscpy(g_cfg.name, name);
            g_cfg.port = (int)port;
            g_cfg.bluetooth = IsDlgButtonChecked(dlg, IDC_BT) == BST_CHECKED;
            config_unlock();
            config_save();
            EndDialog(dlg, TRUE);
            return TRUE;
        }
        }
        break;
    }
    return FALSE;
}

BOOL settings_show(HWND owner)
{
    static BOOL open;
    if (open) return FALSE;
    open = TRUE;
    BOOL saved = DialogBoxParamW(GetModuleHandleW(NULL), MAKEINTRESOURCEW(IDD_SETTINGS), owner, dlg_proc, 0) == TRUE;
    open = FALSE;
    return saved;
}
