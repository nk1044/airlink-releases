/*
 * Phone simulator for testing the desktop app without a phone. Speaks the same protocol as the Android app.
 *
 *   testclient discover <password> [target-ip]
 *   testclient send     <host> <password> <text>
 *   testclient image    <host> <password> <image-file>
 *   testclient files    <host> <password> <file>...
 *   testclient listen   <host> <password>        print what the PC sends; Ctrl+C to stop
 */
#include "../src/common.h"
#include "../src/crypto.h"
#include "../src/proto.h"
#include <stdio.h>

HWND g_hwnd; /* unused; satisfies common.h */

static BYTE g_master[32];

static void die(const char *msg)
{
    fprintf(stderr, "error: %s\n", msg);
    exit(1);
}

static BYTE *read_file(const char *path, size_t *len)
{
    FILE *f = fopen(path, "rb");
    if (!f) die("can't open file");
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    BYTE *b = malloc((size_t)n + 1);
    if (!b || fread(b, 1, (size_t)n, f) != (size_t)n) die("can't read file");
    fclose(f);
    *len = (size_t)n;
    return b;
}

static Conn *open_conn(const char *host)
{
    struct addrinfo hints = { 0 }, *res;
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    if (getaddrinfo(host, "47817", &hints, &res)) die("bad host");
    SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (connect(s, res->ai_addr, (int)res->ai_addrlen)) die("connect failed");
    freeaddrinfo(res);
    int why;
    Conn *c = proto_handshake(s, LINK_WIFI, g_master, FALSE, "testclient", &why);
    if (!c) die(why == HS_AUTH ? "wrong password" : "handshake failed");
    printf("connected to \"%s\"\n", c->peer);
    return c;
}

static int discover(const char *target)
{
    static const char ask[] = "ClipSync discover", answer[] = "ClipSync answer";
    BYTE pkt[36], reply[128], mac[32];
    SOCKET u = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    BOOL on = TRUE;
    DWORD timeout = 2000;
    setsockopt(u, SOL_SOCKET, SO_BROADCAST, (char *)&on, sizeof on);
    setsockopt(u, SOL_SOCKET, SO_RCVTIMEO, (char *)&timeout, sizeof timeout);
    memcpy(pkt, "CSYD", 4);
    crypto_random(pkt + 4, 16);
    Buf q[2] = { { ask, sizeof ask - 1 }, { pkt + 4, 16 } };
    crypto_hmac(g_master, q, 2, mac);
    memcpy(pkt + 20, mac, 16);

    struct sockaddr_in to = { 0 };
    to.sin_family = AF_INET;
    to.sin_port = htons(CS_UDP_PORT);
    inet_pton(AF_INET, target, &to.sin_addr);
    sendto(u, (char *)pkt, sizeof pkt, 0, (struct sockaddr *)&to, sizeof to);

    struct sockaddr_in from;
    int fl = sizeof from;
    int n = recvfrom(u, (char *)reply, sizeof reply - 1, 0, (struct sockaddr *)&from, &fl);
    if (n < 38 || memcmp(reply, "CSYA", 4) || memcmp(reply + 4, pkt + 4, 16)) die("no valid answer");
    Buf a[3] = { { answer, sizeof answer - 1 }, { pkt + 4, 16 }, { reply + 20, 2 } };
    crypto_hmac(g_master, a, 3, mac);
    if (memcmp(mac, reply + 22, 16)) die("answer has a bad HMAC");
    reply[n] = 0;
    char ip[INET_ADDRSTRLEN];
    inet_ntop(AF_INET, &from.sin_addr, ip, sizeof ip);
    printf("found \"%s\" at %s:%u\n", reply + 38, ip, get_be16(reply + 20));
    return 0;
}

static void send_file(Conn *c, const char *path)
{
    size_t len;
    BYTE *data = read_file(path, &len);
    const char *base = strrchr(path, '\\') ? strrchr(path, '\\') + 1 : strrchr(path, '/') ? strrchr(path, '/') + 1 : path;
    BYTE hdr[10];
    put_be64(hdr, len);
    put_be16(hdr + 8, (unsigned)strlen(base));
    if (!proto_send(c, T_FILE_BEGIN, hdr, 10, base, strlen(base))) die("send failed");
    for (size_t off = 0; off < len; off += CHUNK_SIZE) {
        size_t n = len - off < CHUNK_SIZE ? len - off : CHUNK_SIZE;
        if (!proto_send(c, T_FILE_DATA, data + off, n, NULL, 0)) die("send failed");
    }
    if (!proto_send(c, T_FILE_END, NULL, 0, NULL, 0)) die("send failed");
    printf("sent %s (%zu bytes)\n", base, len);
    free(data);
}

static void listen_loop(Conn *c)
{
    FILE *out = NULL;
    int images = 0;
    Frame f;
    int r;
    while ((r = proto_recv(c, &f)) == RX_OK) {
        switch (f.type) {
        case T_PING:
            printf("PING\n");
            proto_send(c, T_PONG, NULL, 0, NULL, 0);
            break;
        case T_TEXT:
            printf("TEXT (%zu bytes): %.*s\n", f.len, (int)f.len, f.data);
            break;
        case T_IMAGE: {
            char name[64];
            sprintf(name, "rx_image_%d.%s", ++images, f.len > 3 && f.data[0] == 0xFF ? "jpg" : "png");
            FILE *img = fopen(name, "wb");
            if (img) { fwrite(f.data, 1, f.len, img); fclose(img); }
            printf("IMAGE (%zu bytes) -> %s\n", f.len, name);
            break;
        }
        case T_FILE_BEGIN: {
            char name[300] = "rx_";
            unsigned nl = get_be16(f.data + 8);
            memcpy(name + 3, f.data + 10, nl < 290 ? nl : 290);
            name[3 + (nl < 290 ? nl : 290)] = 0;
            out = fopen(name, "wb");
            printf("FILE_BEGIN %s (%llu bytes)\n", name, (unsigned long long)get_be64(f.data));
            break;
        }
        case T_FILE_DATA:
            if (out) fwrite(f.data, 1, f.len, out);
            break;
        case T_FILE_END:
        case T_FILE_CANCEL:
            if (out) fclose(out);
            out = NULL;
            printf(f.type == T_FILE_END ? "FILE_END\n" : "FILE_CANCEL\n");
            break;
        case T_FILES_DONE:
            printf("FILES_DONE count=%u\n", get_be16(f.data));
            break;
        default:
            printf("frame type 0x%02x (%zu bytes)\n", f.type, f.len);
        }
        fflush(stdout);
        free(f.buf);
    }
    printf("disconnected (%s)\n", r == RX_AUTH ? "bad frame" : "closed");
}

static int run(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 3) {
        fprintf(stderr, "usage: testclient discover|send|image|files|listen ...\n");
        return 2;
    }
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
    if (!crypto_init()) die("crypto init");
    const char *cmd = argv[1];
    const char *pw = !strcmp(cmd, "discover") ? argv[2] : argc > 3 ? argv[3] : "";
    if (!crypto_pbkdf2(pw, strlen(pw), g_master)) die("pbkdf2");

    if (!strcmp(cmd, "discover")) return discover(argc > 3 ? argv[3] : "255.255.255.255");
    if (argc < 4) die("missing arguments");
    Conn *c = open_conn(argv[2]);
    if (!strcmp(cmd, "send") && argc > 4) {
        if (!proto_send(c, T_TEXT, argv[4], strlen(argv[4]), NULL, 0)) die("send failed");
        printf("sent text\n");
    } else if (!strcmp(cmd, "image") && argc > 4) {
        size_t len;
        BYTE *data = read_file(argv[4], &len);
        if (!proto_send(c, T_IMAGE, data, len, NULL, 0)) die("send failed");
        printf("sent image (%zu bytes)\n", len);
    } else if (!strcmp(cmd, "files") && argc > 4) {
        for (int i = 4; i < argc; i++) send_file(c, argv[i]);
        BYTE n[2];
        put_be16(n, (unsigned)(argc - 4));
        proto_send(c, T_FILES_DONE, n, 2, NULL, 0);
        printf("sent FILES_DONE\n");
    } else if (!strcmp(cmd, "listen")) {
        listen_loop(c);
    } else {
        die("unknown command");
    }
    Sleep(300); /* let the PC read everything before we close */
    proto_free(c);
    return 0;
}

/* Arguments as UTF-8 (plain main() gets them in the ANSI codepage). */
int wmain(int argc, wchar_t **wargv)
{
    char **argv = calloc((size_t)argc + 1, sizeof *argv);
    for (int i = 0; i < argc; i++) {
        int n = WideCharToMultiByte(CP_UTF8, 0, wargv[i], -1, NULL, 0, NULL, NULL);
        argv[i] = malloc((size_t)n);
        WideCharToMultiByte(CP_UTF8, 0, wargv[i], -1, argv[i], n, NULL, NULL);
    }
    return run(argc, argv);
}
