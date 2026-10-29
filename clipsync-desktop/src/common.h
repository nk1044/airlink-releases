#pragma once

#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#define WIN32_LEAN_AND_MEAN
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define CS_TCP_PORT_DEFAULT 47817
#define CS_UDP_PORT         47818
#define CS_WND_CLASS        L"ClipSyncTrayWindow"
#define CS_COPYDATA_SEND    0x43535346 /* 'CSSF': list of paths from "--send" */

/* Messages posted to the tray window by worker threads. */
enum {
    WM_APP_TRAY = WM_APP + 1,
    WM_APP_STATE,      /* connection changed */
    WM_APP_RECV_TEXT,  /* wParam: length, lParam: malloc'd UTF-8 (NUL-terminated) */
    WM_APP_RECV_IMAGE, /* lParam: RecvImage* */
    WM_APP_RECV_FILES, /* lParam: FileList* */
    WM_APP_NOTIFY,     /* lParam: malloc'd WCHAR* */
    WM_APP_TX,         /* wParam: TX_SEND/TX_RECV, lParam: started/ended (transfer.c) */
    WM_APP_FIREWALL,   /* wParam: TRUE if the firewall lets the phone in */
};

typedef struct {
    WCHAR **paths;
    int n, cap;
} FileList;

typedef struct {
    HGLOBAL dib;   /* CF_DIB for the clipboard */
    BYTE *png;     /* original bytes when the image arrived as PNG, else NULL */
    size_t png_len;
    UINT64 hash;
} RecvImage;

extern HWND g_hwnd;

static inline void put_be16(BYTE *p, unsigned v) { p[0] = (BYTE)(v >> 8); p[1] = (BYTE)v; }
static inline void put_be32(BYTE *p, UINT32 v) { for (int i = 0; i < 4; i++) p[i] = (BYTE)(v >> (24 - 8 * i)); }
static inline void put_be64(BYTE *p, UINT64 v) { for (int i = 0; i < 8; i++) p[i] = (BYTE)(v >> (56 - 8 * i)); }
static inline unsigned get_be16(const BYTE *p) { return (unsigned)p[0] << 8 | p[1]; }
static inline UINT32 get_be32(const BYTE *p) { return (UINT32)p[0] << 24 | (UINT32)p[1] << 16 | (UINT32)p[2] << 8 | p[3]; }
static inline UINT64 get_be64(const BYTE *p) { return (UINT64)get_be32(p) << 32 | get_be32(p + 4); }

/* Longest prefix of s (at most max bytes) that doesn't cut a UTF-8 sequence. */
static inline int utf8_clip(const char *s, int len, int max)
{
    if (len <= max) return len;
    while (max > 0 && ((BYTE)s[max] & 0xC0) == 0x80) max--;
    return max;
}

/* util.c */
char *utf8_from_wide(const WCHAR *w, int wlen, int *out_len);
WCHAR *wide_from_utf8(const char *s, int len);
UINT64 fnv64(const void *data, size_t len);
void post_notify(const WCHAR *fmt, ...);
/* Diagnostics: one line per connection event in %APPDATA%\ClipSync\clipsync.log (kept under ~1 MB). Any thread. */
void log_init(const WCHAR *dir);
void log_event(const char *fmt, ...);
void file_list_add(FileList *l, WCHAR *owned_path);
void file_list_free(FileList *l);

/* This PC's IPv4 addresses on Wi-Fi/Ethernet networks that have a gateway (skips VPN and virtual adapters). */
typedef struct {
    struct in_addr ip, gateway;
    ULONG prefix;
} LanAddr;
int lan_addresses(LanAddr *out, int max);
