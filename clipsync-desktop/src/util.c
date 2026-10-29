#include "common.h"
#include <iphlpapi.h>
#include <stdarg.h>
#include <stdio.h>

char *utf8_from_wide(const WCHAR *w, int wlen, int *out_len)
{
    int n = wlen ? WideCharToMultiByte(CP_UTF8, 0, w, wlen, NULL, 0, NULL, NULL) : 0;
    char *s = malloc((size_t)n + 1);
    if (!s) return NULL;
    if (n) WideCharToMultiByte(CP_UTF8, 0, w, wlen, s, n, NULL, NULL);
    s[n] = 0;
    if (out_len) *out_len = n;
    return s;
}

WCHAR *wide_from_utf8(const char *s, int len)
{
    int n = len ? MultiByteToWideChar(CP_UTF8, 0, s, len, NULL, 0) : 0;
    WCHAR *w = malloc(((size_t)n + 1) * sizeof(WCHAR));
    if (!w) return NULL;
    if (n) MultiByteToWideChar(CP_UTF8, 0, s, len, w, n);
    w[n] = 0;
    return w;
}

UINT64 fnv64(const void *data, size_t len)
{
    const BYTE *p = data;
    UINT64 h = 0xcbf29ce484222325ULL;
    while (len--) h = (h ^ *p++) * 0x100000001b3ULL;
    return h;
}

/* Shows a tray balloon; safe to call from any thread. */
void post_notify(const WCHAR *fmt, ...)
{
    WCHAR *buf = malloc(256 * sizeof(WCHAR));
    if (!buf) return;
    va_list ap;
    va_start(ap, fmt);
    _vsnwprintf(buf, 255, fmt, ap);
    va_end(ap);
    buf[255] = 0;
    if (!g_hwnd || !PostMessageW(g_hwnd, WM_APP_NOTIFY, 0, (LPARAM)buf)) free(buf);
}

static CRITICAL_SECTION g_log_cs;
static WCHAR g_log_path[MAX_PATH], g_log_old[MAX_PATH];

void log_init(const WCHAR *dir)
{
    InitializeCriticalSection(&g_log_cs);
    swprintf(g_log_path, MAX_PATH, L"%s\\clipsync.log", dir);
    swprintf(g_log_old, MAX_PATH, L"%s\\clipsync.old.log", dir);
}

void log_event(const char *fmt, ...)
{
    char line[400];
    SYSTEMTIME t;
    if (!g_log_path[0]) return;
    GetLocalTime(&t);
    int n = snprintf(line, sizeof line, "%04d-%02d-%02d %02d:%02d:%02d.%03d  ", t.wYear, t.wMonth, t.wDay, t.wHour,
                     t.wMinute, t.wSecond, t.wMilliseconds);
    va_list ap;
    va_start(ap, fmt);
    int m = vsnprintf(line + n, sizeof line - (size_t)n - 3, fmt, ap);
    va_end(ap);
    n += m < 0 ? 0 : m >= (int)(sizeof line) - n - 3 ? (int)(sizeof line) - n - 4 : m;
    line[n++] = '\r';
    line[n++] = '\n';

    EnterCriticalSection(&g_log_cs);
    HANDLE h = CreateFileW(g_log_path, FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_DELETE, NULL, OPEN_ALWAYS,
                           FILE_ATTRIBUTE_NORMAL, NULL);
    if (h != INVALID_HANDLE_VALUE) {
        LARGE_INTEGER size;
        DWORD w;
        WriteFile(h, line, (DWORD)n, &w, NULL);
        BOOL big = GetFileSizeEx(h, &size) && size.QuadPart > 512 * 1024;
        CloseHandle(h);
        if (big) MoveFileExW(g_log_path, g_log_old, MOVEFILE_REPLACE_EXISTING); /* keep this one and the previous */
    }
    LeaveCriticalSection(&g_log_cs);
}

void file_list_add(FileList *l, WCHAR *owned_path)
{
    if (l->n == l->cap) {
        int cap = l->cap ? l->cap * 2 : 8;
        WCHAR **p = realloc(l->paths, (size_t)cap * sizeof *p);
        if (!p) { free(owned_path); return; }
        l->paths = p;
        l->cap = cap;
    }
    l->paths[l->n++] = owned_path;
}

void file_list_free(FileList *l)
{
    for (int i = 0; i < l->n; i++) free(l->paths[i]);
    free(l->paths);
    memset(l, 0, sizeof *l);
}

int lan_addresses(LanAddr *out, int max)
{
    ULONG size = 16384, flags = GAA_FLAG_INCLUDE_GATEWAYS | GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST |
                               GAA_FLAG_SKIP_DNS_SERVER;
    IP_ADAPTER_ADDRESSES *list = malloc(size);
    int n = 0;
    if (list && GetAdaptersAddresses(AF_INET, flags, NULL, list, &size) == ERROR_BUFFER_OVERFLOW) {
        free(list);
        list = malloc(size);
    }
    if (!list || GetAdaptersAddresses(AF_INET, flags, NULL, list, &size) != NO_ERROR) {
        free(list);
        return 0;
    }
    for (IP_ADAPTER_ADDRESSES *a = list; a && n < max; a = a->Next) {
        if (a->OperStatus != IfOperStatusUp || !a->FirstGatewayAddress ||
            (a->IfType != IF_TYPE_IEEE80211 && a->IfType != IF_TYPE_ETHERNET_CSMACD))
            continue;
        struct sockaddr_in *gw = (struct sockaddr_in *)a->FirstGatewayAddress->Address.lpSockaddr;
        for (IP_ADAPTER_UNICAST_ADDRESS *u = a->FirstUnicastAddress; u && n < max; u = u->Next) {
            out[n].ip = ((struct sockaddr_in *)u->Address.lpSockaddr)->sin_addr;
            out[n].prefix = u->OnLinkPrefixLength;
            out[n].gateway = gw->sin_addr;
            n++;
        }
    }
    free(list);
    return n;
}
