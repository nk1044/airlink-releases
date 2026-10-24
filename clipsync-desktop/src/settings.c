#include "settings.h"
#include "config.h"
#include "resource.h"
#include <iphlpapi.h>
#include <commctrl.h>
#include <stdio.h>

/* LAN IPv4 addresses of Wi-Fi/Ethernet adapters that have a gateway (skips VPN and virtual adapters). */
static void lan_addresses(WCHAR *out, size_t n)
{
    ULONG size = 16384;
    IP_ADAPTER_ADDRESSES *list = malloc(size);
    ULONG flags = GAA_FLAG_INCLUDE_GATEWAYS | GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
    out[0] = 0;
    if (list && GetAdaptersAddresses(AF_INET, flags, NULL, list, &size) == ERROR_BUFFER_OVERFLOW) {
        free(list);
        list = malloc(size);
    }
    if (!list || GetAdaptersAddresses(AF_INET, flags, NULL, list, &size) != NO_ERROR) {
        free(list);
        return;
    }
    for (IP_ADAPTER_ADDRESSES *a = list; a; a = a->Next) {
        if (a->OperStatus != IfOperStatusUp || !a->FirstGatewayAddress ||
            (a->IfType != IF_TYPE_IEEE80211 && a->IfType != IF_TYPE_ETHERNET_CSMACD))
            continue;
        for (IP_ADAPTER_UNICAST_ADDRESS *u = a->FirstUnicastAddress; u; u = u->Next) {
            WCHAR ip[INET_ADDRSTRLEN];
            struct sockaddr_in *sin = (struct sockaddr_in *)u->Address.lpSockaddr;
            if (!InetNtopW(AF_INET, &sin->sin_addr, ip, ARRAYSIZE(ip))) continue;
            size_t len = wcslen(out);
            swprintf(out + len, n - len, L"%s%s", len ? L", " : L"", ip);
        }
    }
    free(list);
}

static INT_PTR CALLBACK dlg_proc(HWND dlg, UINT msg, WPARAM wp, LPARAM lp)
{
    (void)lp;
    switch (msg) {
    case WM_INITDIALOG: {
        WCHAR addr[256], text[320];
        SetDlgItemTextW(dlg, IDC_NAME, g_cfg.name);
        SetDlgItemInt(dlg, IDC_PORT, (UINT)g_cfg.port, FALSE);
        CheckDlgButton(dlg, IDC_BT, g_cfg.bluetooth ? BST_CHECKED : BST_UNCHECKED);
        SendDlgItemMessageW(dlg, IDC_PASSWORD, EM_LIMITTEXT, 128, 0);
        SendDlgItemMessageW(dlg, IDC_NAME, EM_LIMITTEXT, ARRAYSIZE(g_cfg.name) - 1, 0);
        SendDlgItemMessageW(dlg, IDC_PASSWORD, EM_SETCUEBANNER, TRUE,
                            (LPARAM)(g_cfg.has_key ? L"(unchanged)" : L"12 or more characters"));
        lan_addresses(addr, ARRAYSIZE(addr));
        swprintf(text, ARRAYSIZE(text), L"This PC's address: %s\nThe phone finds it automatically; enter it there only if it doesn't.",
                 addr[0] ? addr : L"(not on a network)");
        SetDlgItemTextW(dlg, IDC_ADDR, text);
        SetForegroundWindow(dlg);
        return TRUE;
    }
    case WM_COMMAND:
        if (LOWORD(wp) == IDCANCEL) {
            EndDialog(dlg, FALSE);
            return TRUE;
        }
        if (LOWORD(wp) == IDOK) {
            WCHAR pw[130], name[ARRAYSIZE(g_cfg.name)];
            BOOL port_ok;
            UINT port = GetDlgItemInt(dlg, IDC_PORT, &port_ok, FALSE);
            GetDlgItemTextW(dlg, IDC_PASSWORD, pw, ARRAYSIZE(pw));
            GetDlgItemTextW(dlg, IDC_NAME, name, ARRAYSIZE(name));
            if (!pw[0] && !g_cfg.has_key) {
                MessageBoxW(dlg, L"Enter a password. Use the same one on the phone.", L"ClipSync", MB_ICONINFORMATION);
                return TRUE;
            }
            if (!port_ok || port < 1024 || port > 65535) {
                MessageBoxW(dlg, L"The port must be between 1024 and 65535.", L"ClipSync", MB_ICONINFORMATION);
                return TRUE;
            }
            if (pw[0] && wcslen(pw) < 12 &&
                MessageBoxW(dlg, L"Short passwords can be guessed by someone on your Wi-Fi. Use it anyway?",
                            L"ClipSync", MB_ICONWARNING | MB_YESNO | MB_DEFBUTTON2) != IDYES)
                return TRUE;

            BOOL first_setup = !g_cfg.has_key;
            if (pw[0]) {
                HCURSOR old = SetCursor(LoadCursor(NULL, IDC_WAIT));
                config_set_password(pw);
                SetCursor(old);
                SecureZeroMemory(pw, sizeof pw);
            }
            config_lock();
            if (name[0]) wcscpy(g_cfg.name, name);
            g_cfg.port = (int)port;
            g_cfg.bluetooth = IsDlgButtonChecked(dlg, IDC_BT) == BST_CHECKED;
            config_unlock();
            config_save();
            if (first_setup) autostart_set(TRUE);
            EndDialog(dlg, TRUE);
            return TRUE;
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
