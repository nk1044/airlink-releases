/*
 * Connection handling.
 *
 *  net thread     select() over the TCP listener, UDP discovery socket and a loopback wake socket;
 *                 accepts Wi-Fi connections, answers discovery, drives the heartbeat, owns the BT thread.
 *  bt thread      blocking accept() on the RFCOMM listener (kept out of select: different provider).
 *  reader thread  one per connection; blocking reads of frames.
 *  sender thread  the only writer; text/images always go ahead of file chunks.
 *
 * The most recent authenticated connection becomes the active one and the previous one is closed.
 */
#include "link.h"
#include "proto.h"
#include "crypto.h"
#include "config.h"
#include "image.h"
#include "files.h"
#include <objbase.h>
#include <ws2bth.h>
#include <mstcpip.h>

#ifndef SIO_UDP_CONNRESET
#define SIO_UDP_CONNRESET _WSAIOW(IOC_VENDOR, 12)
#endif

#define HEARTBEAT_MS   240000ULL
#define DEAD_MS        600000ULL
#define BT_RETRY_MS    300000ULL
#define TEXT_MAX       (1u << 20)
#define IMAGE_MAX_WIFI (10u << 20)
#define IMAGE_MAX_BT   (2u << 20)

/* c1b5d7a2-3f4e-4b8a-9d6c-2e7f1a0b5c93 */
static const GUID BT_SERVICE_UUID = { 0xc1b5d7a2, 0x3f4e, 0x4b8a, { 0x9d, 0x6c, 0x2e, 0x7f, 0x1a, 0x0b, 0x5c, 0x93 } };

enum { IT_FRAME, IT_DIB, IT_FILES };

typedef struct Item {
    struct Item *next;
    int kind;
    BYTE type;
    BYTE *data;
    size_t len;
    FileList files;
} Item;

static CRITICAL_SECTION g_cs;
static Conn *g_active;
static Item *g_q, *g_q_tail;   /* control, text, images */
static Item *g_fq, *g_fq_tail; /* file batches */
static HANDLE g_send_event, g_net_thread, g_send_thread;
static SOCKET g_wake = INVALID_SOCKET;
static struct sockaddr_in g_wake_addr;
static volatile LONG g_quit, g_restart, g_cancel;

static ULONGLONG g_fail_window, g_block_until;
static int g_fail_count;

/* ---- queue ---- */

static void push(Item *it)
{
    EnterCriticalSection(&g_cs);
    if (it->kind == IT_FILES) {
        if (g_fq_tail) g_fq_tail->next = it; else g_fq = it;
        g_fq_tail = it;
    } else {
        if (g_q_tail) g_q_tail->next = it; else g_q = it;
        g_q_tail = it;
    }
    LeaveCriticalSection(&g_cs);
    SetEvent(g_send_event);
}

static Item *pop(BOOL include_files)
{
    EnterCriticalSection(&g_cs);
    Item *it = g_q;
    if (it) {
        g_q = it->next;
        if (!g_q) g_q_tail = NULL;
    } else if (include_files && (it = g_fq)) {
        g_fq = it->next;
        if (!g_fq) g_fq_tail = NULL;
    }
    LeaveCriticalSection(&g_cs);
    return it;
}

static void item_free(Item *it)
{
    free(it->data);
    file_list_free(&it->files);
    free(it);
}

static void enqueue(int kind, BYTE type, BYTE *data, size_t len)
{
    Item *it = calloc(1, sizeof *it);
    if (!it) { free(data); return; }
    it->kind = kind;
    it->type = type;
    it->data = data;
    it->len = len;
    push(it);
}

void link_send_frame(BYTE type, BYTE *data, size_t len) { enqueue(IT_FRAME, type, data, len); }
void link_send_dib(BYTE *dib, size_t len) { enqueue(IT_DIB, T_IMAGE, dib, len); }
void link_cancel_files(void) { g_cancel = 1; }

void link_send_files(FileList *files)
{
    Item *it = calloc(1, sizeof *it);
    if (!it) { file_list_free(files); return; }
    it->kind = IT_FILES;
    it->files = *files;
    memset(files, 0, sizeof *files);
    push(it);
}

/* ---- active connection ---- */

static Conn *acquire(void)
{
    EnterCriticalSection(&g_cs);
    Conn *c = g_active;
    if (c) InterlockedIncrement(&c->refs);
    LeaveCriticalSection(&g_cs);
    return c;
}

static void release(Conn *c)
{
    if (c && InterlockedDecrement(&c->refs) == 0) proto_free(c);
}

int link_state(WCHAR *peer, int peer_len)
{
    EnterCriticalSection(&g_cs);
    int kind = g_active ? g_active->kind : LINK_NONE;
    if (peer && peer_len) {
        peer[0] = 0;
        if (g_active) MultiByteToWideChar(CP_UTF8, 0, g_active->peer, -1, peer, peer_len);
    }
    LeaveCriticalSection(&g_cs);
    return kind;
}

static void wake_net(void)
{
    if (g_wake != INVALID_SOCKET)
        sendto(g_wake, "w", 1, 0, (struct sockaddr *)&g_wake_addr, sizeof g_wake_addr);
}

static void handle_frame(Conn *c, RxState *rx, Frame *f)
{
    switch (f->type) {
    case T_PING:
        enqueue(IT_FRAME, T_PONG, NULL, 0);
        break;
    case T_TEXT:
        if (!g_cfg.paused && f->len && f->len <= TEXT_MAX) {
            char *s = malloc(f->len + 1);
            if (!s) break;
            memcpy(s, f->data, f->len);
            s[f->len] = 0;
            if (!PostMessageW(g_hwnd, WM_APP_RECV_TEXT, f->len, (LPARAM)s)) free(s);
        }
        break;
    case T_IMAGE:
        if (!g_cfg.paused) {
            RecvImage *ri = calloc(1, sizeof *ri);
            if (!ri) break;
            if (img_to_dib(f->data, f->len, &ri->dib)) {
                if (img_is_png(f->data, f->len) && (ri->png = malloc(f->len))) {
                    memcpy(ri->png, f->data, f->len);
                    ri->png_len = f->len;
                }
                ri->hash = fnv64(f->data, f->len);
                if (PostMessageW(g_hwnd, WM_APP_RECV_IMAGE, 0, (LPARAM)ri)) break;
                GlobalFree(ri->dib);
                free(ri->png);
            }
            free(ri);
        }
        break;
    default:
        if (c->kind != LINK_WIFI) break; /* files are Wi-Fi only */
        switch (f->type) {
        case T_FILE_BEGIN:  files_rx_begin(rx, f->data, f->len); break;
        case T_FILE_DATA:   files_rx_data(rx, f->data, f->len); break;
        case T_FILE_END:    files_rx_end(rx); break;
        case T_FILE_CANCEL: files_rx_cancel(rx); break;
        case T_FILES_DONE:  files_rx_done(rx); break;
        }
    }
}

static DWORD WINAPI reader_main(void *arg)
{
    Conn *c = arg;
    RxState rx = { 0 };
    Frame f;
    CoInitializeEx(NULL, COINIT_MULTITHREADED);
    while (proto_recv(c, &f) == RX_OK) {
        c->last_recv = GetTickCount64();
        handle_frame(c, &rx, &f);
        free(f.buf);
    }
    files_rx_abort(&rx);

    EnterCriticalSection(&g_cs);
    BOOL was_active = g_active == c;
    if (was_active) g_active = NULL;
    LeaveCriticalSection(&g_cs);
    shutdown(c->s, SD_BOTH);
    if (was_active) {
        release(c);
        PostMessageW(g_hwnd, WM_APP_STATE, 0, 0);
        wake_net();
    }
    release(c);
    CoUninitialize();
    return 0;
}

static void adopt(Conn *c)
{
    c->refs = 2; /* g_active + reader thread */
    c->last_send = c->last_recv = GetTickCount64();
    EnterCriticalSection(&g_cs);
    Conn *old = g_active;
    g_active = c;
    LeaveCriticalSection(&g_cs);
    if (old) {
        shutdown(old->s, SD_BOTH); /* its reader thread exits and drops its own ref */
        release(old);
    }
    HANDLE t = CreateThread(NULL, 0, reader_main, c, 0, NULL);
    if (t) {
        CloseHandle(t);
    } else {
        EnterCriticalSection(&g_cs);
        if (g_active == c) g_active = NULL;
        LeaveCriticalSection(&g_cs);
        release(c);
        release(c);
    }
    PostMessageW(g_hwnd, WM_APP_STATE, 0, 0);
    wake_net();
}

static void auth_failed(const WCHAR *who)
{
    ULONGLONG now = GetTickCount64();
    EnterCriticalSection(&g_cs);
    if (now - g_fail_window > 60000) {
        g_fail_window = now;
        g_fail_count = 0;
    }
    int n = ++g_fail_count;
    if (n >= 5) g_block_until = now + 60000;
    LeaveCriticalSection(&g_cs);
    if (n == 1) post_notify(L"Wrong password from %s. Check the password on the phone.", who);
}

/* Handshake on a freshly accepted socket; becomes the active connection on success. */
static void serve(SOCKET s, int kind, const struct sockaddr *from)
{
    WCHAR who[64] = L"a Bluetooth device";
    DWORD hs_timeout = 10000, io_timeout = 30000;
    Config cfg;

    EnterCriticalSection(&g_cs);
    BOOL blocked = GetTickCount64() < g_block_until;
    LeaveCriticalSection(&g_cs);
    config_snapshot(&cfg);
    if (blocked || !cfg.has_key) {
        closesocket(s);
        goto out;
    }
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, (char *)&hs_timeout, sizeof hs_timeout);
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, (char *)&io_timeout, sizeof io_timeout);
    if (kind == LINK_WIFI) {
        BOOL on = TRUE;
        setsockopt(s, IPPROTO_TCP, TCP_NODELAY, (char *)&on, sizeof on);
        InetNtopW(AF_INET, &((const struct sockaddr_in *)from)->sin_addr, who, ARRAYSIZE(who));
    }

    int why;
    char *name = utf8_from_wide(cfg.name, (int)wcslen(cfg.name), NULL);
    Conn *c = proto_handshake(s, kind, cfg.key, TRUE, name ? name : "PC", &why);
    free(name);
    if (!c) {
        closesocket(s);
        if (why == HS_AUTH) auth_failed(who);
        goto out;
    }
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, (char *)&io_timeout, sizeof io_timeout);
    adopt(c);
out:
    SecureZeroMemory(cfg.key, sizeof cfg.key);
}

/* ---- discovery ---- */

static void handle_discovery(SOCKET u)
{
    static const char ask[] = "ClipSync discover", answer[] = "ClipSync answer";
    BYTE pkt[64], mac[32], reply[38 + 64];
    struct sockaddr_in from;
    int fl = sizeof from;
    int n = recvfrom(u, (char *)pkt, sizeof pkt, 0, (struct sockaddr *)&from, &fl);
    if (n != 36 || memcmp(pkt, "CSYD", 4)) return;

    Config cfg;
    config_snapshot(&cfg);
    if (!cfg.has_key) return;
    Buf q[2] = { { ask, sizeof ask - 1 }, { pkt + 4, 16 } };
    crypto_hmac(cfg.key, q, 2, mac);
    if (memcmp(mac, pkt + 20, 16) == 0) {
        memcpy(reply, "CSYA", 4);
        memcpy(reply + 4, pkt + 4, 16);
        put_be16(reply + 20, (unsigned)cfg.port);
        Buf a[3] = { { answer, sizeof answer - 1 }, { pkt + 4, 16 }, { reply + 20, 2 } };
        crypto_hmac(cfg.key, a, 3, mac);
        memcpy(reply + 22, mac, 16);
        int nl = 0;
        char *name = utf8_from_wide(cfg.name, (int)wcslen(cfg.name), &nl);
        nl = name ? utf8_clip(name, nl, 64) : 0;
        if (nl) memcpy(reply + 38, name, (size_t)nl);
        free(name);
        sendto(u, (char *)reply, 38 + nl, 0, (struct sockaddr *)&from, fl);
    }
    SecureZeroMemory(cfg.key, sizeof cfg.key);
}

/* ---- sockets ---- */

static SOCKET open_tcp(int port)
{
    SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) return s;
    BOOL excl = TRUE;
    setsockopt(s, SOL_SOCKET, SO_EXCLUSIVEADDRUSE, (char *)&excl, sizeof excl);
    struct sockaddr_in a = { 0 };
    a.sin_family = AF_INET;
    a.sin_port = htons((u_short)port);
    a.sin_addr.s_addr = htonl(INADDR_ANY);
    if (bind(s, (struct sockaddr *)&a, sizeof a) || listen(s, 4)) {
        closesocket(s);
        return INVALID_SOCKET;
    }
    return s;
}

static SOCKET open_udp(void)
{
    SOCKET s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (s == INVALID_SOCKET) return s;
    BOOL off = FALSE;
    DWORD ret;
    WSAIoctl(s, SIO_UDP_CONNRESET, &off, sizeof off, NULL, 0, &ret, NULL, NULL);
    struct sockaddr_in a = { 0 };
    a.sin_family = AF_INET;
    a.sin_port = htons(CS_UDP_PORT);
    a.sin_addr.s_addr = htonl(INADDR_ANY);
    if (bind(s, (struct sockaddr *)&a, sizeof a)) {
        closesocket(s);
        return INVALID_SOCKET;
    }
    return s;
}

/* Bluetooth: RFCOMM listener registered in SDP under BT_SERVICE_UUID. */
static SOCKET g_bt = INVALID_SOCKET;
static HANDLE g_bt_thread;
static SOCKADDR_BTH g_bt_addr;
static CSADDR_INFO g_bt_csa;
static WSAQUERYSETW g_bt_qs;

static DWORD WINAPI bt_main(void *arg)
{
    SOCKET l = (SOCKET)arg;
    for (;;) {
        SOCKET s = accept(l, NULL, NULL);
        if (s == INVALID_SOCKET) break; /* listener closed or radio gone */
        serve(s, LINK_BT, NULL);
    }
    return 0;
}

static void bt_start(void)
{
    SOCKET s = socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
    if (s == INVALID_SOCKET) return; /* no Bluetooth radio */
    SOCKADDR_BTH a = { 0 };
    a.addressFamily = AF_BTH;
    a.port = BT_PORT_ANY;
    int len = sizeof g_bt_addr;
    if (bind(s, (struct sockaddr *)&a, sizeof a) || listen(s, 2) ||
        getsockname(s, (struct sockaddr *)&g_bt_addr, &len))
        goto fail;

    memset(&g_bt_csa, 0, sizeof g_bt_csa);
    g_bt_csa.LocalAddr.iSockaddrLength = sizeof(SOCKADDR_BTH);
    g_bt_csa.LocalAddr.lpSockaddr = (LPSOCKADDR)&g_bt_addr;
    g_bt_csa.iSocketType = SOCK_STREAM;
    g_bt_csa.iProtocol = BTHPROTO_RFCOMM;
    memset(&g_bt_qs, 0, sizeof g_bt_qs);
    g_bt_qs.dwSize = sizeof g_bt_qs;
    g_bt_qs.lpszServiceInstanceName = L"ClipSync";
    g_bt_qs.lpServiceClassId = (LPGUID)&BT_SERVICE_UUID;
    g_bt_qs.dwNameSpace = NS_BTH;
    g_bt_qs.dwNumberOfCsAddrs = 1;
    g_bt_qs.lpcsaBuffer = &g_bt_csa;
    if (WSASetServiceW(&g_bt_qs, RNRSERVICE_REGISTER, 0)) goto fail;

    g_bt_thread = CreateThread(NULL, 0, bt_main, (void *)s, 0, NULL);
    if (!g_bt_thread) {
        WSASetServiceW(&g_bt_qs, RNRSERVICE_DELETE, 0);
        goto fail;
    }
    g_bt = s;
    return;
fail:
    closesocket(s);
}

static void bt_stop(void)
{
    if (!g_bt_thread) return;
    closesocket(g_bt);
    WaitForSingleObject(g_bt_thread, 3000);
    CloseHandle(g_bt_thread);
    WSASetServiceW(&g_bt_qs, RNRSERVICE_DELETE, 0);
    g_bt_thread = NULL;
    g_bt = INVALID_SOCKET;
}

/* ---- net thread ---- */

static DWORD WINAPI net_main(void *unused)
{
    (void)unused;
    while (!g_quit) {
        Config cfg;
        g_restart = 0;
        config_snapshot(&cfg);
        SecureZeroMemory(cfg.key, sizeof cfg.key);

        SOCKET tcp = INVALID_SOCKET, udp = INVALID_SOCKET;
        if (cfg.has_key) {
            tcp = open_tcp(cfg.port);
            if (tcp == INVALID_SOCKET) post_notify(L"Port %d is already in use. Pick another one in Settings.", cfg.port);
            udp = open_udp();
        }
        BOOL want_bt = cfg.has_key && cfg.bluetooth;
        ULONGLONG bt_retry = 0;

        while (!g_quit && !g_restart) {
            ULONGLONG now = GetTickCount64(), wait = ~0ULL;

            Conn *c = acquire();
            if (c) {
                ULONGLONG since_recv = now - c->last_recv, since_send = now - c->last_send;
                if (since_recv >= DEAD_MS) {
                    shutdown(c->s, SD_BOTH);
                } else {
                    if (since_send >= HEARTBEAT_MS) {
                        enqueue(IT_FRAME, T_PING, NULL, 0);
                        c->last_send = now;
                        since_send = 0;
                    }
                    wait = min(HEARTBEAT_MS - since_send, DEAD_MS - since_recv);
                }
                release(c);
            }

            if (want_bt) {
                if (g_bt_thread && WaitForSingleObject(g_bt_thread, 0) == WAIT_OBJECT_0) {
                    bt_stop();
                    bt_retry = now + BT_RETRY_MS;
                }
                if (!g_bt_thread && now >= bt_retry) {
                    bt_start();
                    if (!g_bt_thread) bt_retry = now + BT_RETRY_MS;
                }
                if (!g_bt_thread) wait = min(wait, bt_retry - now);
            }

            fd_set rd;
            FD_ZERO(&rd);
            FD_SET(g_wake, &rd);
            if (tcp != INVALID_SOCKET) FD_SET(tcp, &rd);
            if (udp != INVALID_SOCKET) FD_SET(udp, &rd);
            struct timeval tv, *ptv = NULL;
            if (wait != ~0ULL) {
                tv.tv_sec = (long)(wait / 1000);
                tv.tv_usec = (long)(wait % 1000) * 1000;
                ptv = &tv;
            }
            int r = select(0, &rd, NULL, NULL, ptv);
            if (r == SOCKET_ERROR) {
                Sleep(500);
                continue;
            }
            if (r == 0) continue;
            if (FD_ISSET(g_wake, &rd)) {
                char b[16];
                recv(g_wake, b, sizeof b, 0);
            }
            if (udp != INVALID_SOCKET && FD_ISSET(udp, &rd)) handle_discovery(udp);
            if (tcp != INVALID_SOCKET && FD_ISSET(tcp, &rd)) {
                struct sockaddr_in from;
                int fl = sizeof from;
                SOCKET s = accept(tcp, (struct sockaddr *)&from, &fl);
                if (s != INVALID_SOCKET) serve(s, LINK_WIFI, (struct sockaddr *)&from);
            }
        }

        bt_stop();
        if (tcp != INVALID_SOCKET) closesocket(tcp);
        if (udp != INVALID_SOCKET) closesocket(udp);
        Conn *c = acquire();
        if (c) {
            shutdown(c->s, SD_BOTH);
            release(c);
        }
    }
    return 0;
}

/* ---- sender thread ---- */

static BOOL send_frame(Conn *c, BYTE type, const void *a, size_t alen, const void *b, size_t blen)
{
    if (!proto_send(c, type, a, alen, b, blen)) {
        shutdown(c->s, SD_BOTH); /* the reader thread notices and cleans up */
        return FALSE;
    }
    c->last_send = GetTickCount64();
    return TRUE;
}

static void send_item(Item *it)
{
    Conn *c = acquire();
    if (!c) return; /* not connected: clipboard changes are not queued */
    BYTE *data = it->data, *tmp = NULL;
    size_t len = it->len;
    if (it->kind == IT_DIB) {
        if (!img_dib_to_png(data, len, &tmp, &len)) goto out;
        data = tmp;
    }
    if (it->type == T_IMAGE) {
        size_t cap = c->kind == LINK_BT ? IMAGE_MAX_BT : IMAGE_MAX_WIFI;
        if (len > cap) {
            BYTE *jpg;
            size_t jl;
            if (!img_fit_jpeg(data, len, cap, &jpg, &jl)) goto out;
            free(tmp);
            data = tmp = jpg;
            len = jl;
        }
    }
    send_frame(c, it->type, data, len, NULL, 0);
out:
    free(tmp);
    release(c);
}

/* Lets text and images overtake a running file transfer. */
static void flush_priority(void)
{
    Item *it;
    while ((it = pop(FALSE))) {
        send_item(it);
        item_free(it);
    }
}

static void progress(int pct, int file, int count)
{
    PostMessageW(g_hwnd, WM_APP_PROGRESS, (WPARAM)pct, MAKELPARAM(file, count));
}

static void send_files(FileList *fl)
{
    Conn *c = acquire();
    if (!c) {
        post_notify(L"Phone not connected. Files were not sent.");
        return;
    }
    if (c->kind != LINK_WIFI) {
        post_notify(L"Files need Wi-Fi. The phone is connected over Bluetooth.");
        release(c);
        return;
    }

    UINT64 total = 0, done = 0;
    int count = 0, nfiles = 0, folders = 0, index = 0;
    BOOL ok = TRUE;
    for (int i = 0; i < fl->n; i++) {
        WIN32_FILE_ATTRIBUTE_DATA fa;
        if (!GetFileAttributesExW(fl->paths[i], GetFileExInfoStandard, &fa) ||
            (fa.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) {
            folders++;
            free(fl->paths[i]);
            fl->paths[i] = NULL;
            continue;
        }
        total += (UINT64)fa.nFileSizeHigh << 32 | fa.nFileSizeLow;
        nfiles++;
    }
    BYTE *buf = malloc(CHUNK_SIZE);
    if (!buf || !nfiles) {
        if (!nfiles) post_notify(L"Nothing to send. Folders aren't supported yet; select files instead.");
        free(buf);
        release(c);
        return;
    }

    g_cancel = 0;
    ULONGLONG last = 0;
    progress(0, 1, nfiles);
    for (int i = 0; i < fl->n && ok && !g_cancel; i++) {
        if (!fl->paths[i]) continue;
        index++;
        HANDLE h = CreateFileW(fl->paths[i], GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                               NULL, OPEN_EXISTING, FILE_FLAG_SEQUENTIAL_SCAN, NULL);
        if (h == INVALID_HANDLE_VALUE) continue;
        LARGE_INTEGER size;
        GetFileSizeEx(h, &size);

        const WCHAR *slash = wcsrchr(fl->paths[i], L'\\');
        const WCHAR *base = slash ? slash + 1 : fl->paths[i];
        int nl = 0;
        char *name = utf8_from_wide(base, (int)wcslen(base), &nl);
        nl = name ? utf8_clip(name, nl, 1024) : 0;
        BYTE hdr[10];
        put_be64(hdr, (UINT64)size.QuadPart);
        put_be16(hdr + 8, (unsigned)nl);
        ok = send_frame(c, T_FILE_BEGIN, hdr, 10, name, (size_t)nl);
        free(name);

        UINT64 left = (UINT64)size.QuadPart;
        while (ok && left && !g_cancel) {
            DWORD want = left < CHUNK_SIZE ? (DWORD)left : CHUNK_SIZE, got;
            flush_priority();
            if (!ReadFile(h, buf, want, &got, NULL) || got == 0) break;
            ok = send_frame(c, T_FILE_DATA, buf, got, NULL, 0);
            left -= got;
            done += got;
            ULONGLONG now = GetTickCount64();
            if (now - last > 300) {
                last = now;
                progress(total ? (int)(done * 100 / total) : 100, index, nfiles);
            }
        }
        CloseHandle(h);
        if (!ok) break;
        if (left) {
            ok = send_frame(c, T_FILE_CANCEL, NULL, 0, NULL, 0);
            continue;
        }
        ok = send_frame(c, T_FILE_END, NULL, 0, NULL, 0);
        if (ok) count++;
    }
    if (ok) {
        BYTE n[2];
        put_be16(n, (unsigned)count);
        ok = send_frame(c, T_FILES_DONE, n, 2, NULL, 0);
    }
    progress(-1, 0, 0);

    if (!ok)
        post_notify(L"File transfer interrupted: the phone disconnected.");
    else if (g_cancel)
        post_notify(L"Sending cancelled. %d file%s sent.", count, count == 1 ? L"" : L"s");
    else
        post_notify(L"Sent %d file%s to the phone.%s", count, count == 1 ? L"" : L"s",
                    folders ? L" Folders were skipped." : L"");
    free(buf);
    release(c);
}

static DWORD WINAPI send_main(void *unused)
{
    (void)unused;
    CoInitializeEx(NULL, COINIT_MULTITHREADED);
    while (!g_quit) {
        WaitForSingleObject(g_send_event, INFINITE);
        Item *it;
        while (!g_quit && (it = pop(TRUE))) {
            if (it->kind == IT_FILES) send_files(&it->files);
            else send_item(it);
            item_free(it);
        }
    }
    return 0;
}

/* ---- lifecycle ---- */

void link_start(void)
{
    InitializeCriticalSection(&g_cs);
    g_send_event = CreateEventW(NULL, FALSE, FALSE, NULL);

    g_wake = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    g_wake_addr.sin_family = AF_INET;
    g_wake_addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int len = sizeof g_wake_addr;
    bind(g_wake, (struct sockaddr *)&g_wake_addr, sizeof g_wake_addr);
    getsockname(g_wake, (struct sockaddr *)&g_wake_addr, &len);

    g_net_thread = CreateThread(NULL, 0, net_main, NULL, 0, NULL);
    g_send_thread = CreateThread(NULL, 0, send_main, NULL, 0, NULL);
}

void link_restart(void)
{
    g_restart = 1;
    wake_net();
}

void link_stop(void)
{
    g_quit = 1;
    g_cancel = 1;
    wake_net();
    SetEvent(g_send_event);
    HANDLE t[2] = { g_net_thread, g_send_thread };
    WaitForMultipleObjects(2, t, TRUE, 3000);
}
