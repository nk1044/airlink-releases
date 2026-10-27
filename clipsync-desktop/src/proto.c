#include "proto.h"
#include "crypto.h"

BOOL proto_send_all(SOCKET s, const void *buf, size_t n)
{
    const char *p = buf;
    while (n) {
        int r = send(s, p, n > (1u << 20) ? 1 << 20 : (int)n, 0);
        if (r <= 0) return FALSE;
        p += r;
        n -= (size_t)r;
    }
    return TRUE;
}

BOOL proto_recv_all(SOCKET s, void *buf, size_t n)
{
    char *p = buf;
    while (n) {
        int r = recv(s, p, n > (1u << 20) ? 1 << 20 : (int)n, 0);
        if (r <= 0) return FALSE;
        p += r;
        n -= (size_t)r;
    }
    return TRUE;
}

static void make_nonce(BYTE out[12], BYTE dir, UINT64 counter)
{
    memset(out, 0, 12);
    out[0] = dir;
    put_be64(out + 4, counter);
}

void proto_free(Conn *c)
{
    if (!c) return;
    if (c->s != INVALID_SOCKET) closesocket(c->s);
    if (c->ks) BCryptDestroyKey(c->ks);
    if (c->kr) BCryptDestroyKey(c->kr);
    SecureZeroMemory(c, sizeof *c);
    free(c);
}

BOOL proto_send(Conn *c, BYTE type, const void *a, size_t alen, const void *b, size_t blen)
{
    size_t pt = 1 + alen + blen;
    if (pt > PAYLOAD_MAX + 1) return FALSE;
    BYTE *buf = malloc(4 + pt + 16);
    if (!buf) return FALSE;
    put_be32(buf, (UINT32)(pt + 16));
    buf[4] = type;
    if (alen) memcpy(buf + 5, a, alen);
    if (blen) memcpy(buf + 5 + alen, b, blen);
    BYTE nonce[12];
    make_nonce(nonce, c->ds, c->ns++);
    BOOL ok = crypto_seal(c->ks, nonce, buf + 4, (ULONG)pt, buf + 4 + pt) && proto_send_all(c->s, buf, 4 + pt + 16);
    free(buf);
    return ok;
}

int proto_recv(Conn *c, Frame *f)
{
    BYTE hdr[4], nonce[12];
    if (!proto_recv_all(c->s, hdr, 4)) return RX_IO;
    UINT32 len = get_be32(hdr);
    if (len < 17 || len > FRAME_MAX) return RX_IO;
    BYTE *buf = malloc(len);
    if (!buf) return RX_IO;
    if (!proto_recv_all(c->s, buf, len)) {
        free(buf);
        return RX_IO;
    }
    make_nonce(nonce, c->dr, c->nr++);
    if (!crypto_open(c->kr, nonce, buf, len - 16, buf + len - 16)) {
        free(buf);
        return RX_AUTH;
    }
    f->type = buf[0];
    f->data = buf + 1;
    f->len = len - 17;
    f->buf = buf;
    return RX_OK;
}

Conn *proto_handshake(SOCKET s, int kind, const BYTE master[32], BOOL server, const char *name, int *why)
{
    BYTE mine[36], theirs[36], session[32];
    *why = HS_IO;
    memcpy(mine, "CSY1", 4);
    crypto_random(mine + 4, 32);
    if (server) {
        memcpy(theirs, "CSY1", 4); /* already read by the caller */
        if (!proto_recv_all(s, theirs + 4, 32) || !proto_send_all(s, mine, 36)) return NULL;
    } else if (!proto_send_all(s, mine, 36) || !proto_recv_all(s, theirs, 36)) {
        return NULL;
    }
    if (memcmp(theirs, "CSY1", 4)) return NULL;

    static const char label[] = "ClipSync session";
    Buf parts[3] = {
        { label, sizeof label - 1 },
        { server ? theirs + 4 : mine + 4, 32 }, /* client random */
        { server ? mine + 4 : theirs + 4, 32 }, /* server random */
    };
    crypto_hmac(master, parts, 3, session);

    Conn *c = calloc(1, sizeof *c);
    if (!c) return NULL;
    c->s = s;
    c->kind = kind;
    c->ks = crypto_aes_key(session);
    c->kr = crypto_aes_key(session);
    SecureZeroMemory(session, sizeof session);
    c->ds = server ? 1 : 0;
    c->dr = server ? 0 : 1;

    Frame f;
    int r = RX_IO;
    int nl = utf8_clip(name, (int)strlen(name), 64);
    if (c->ks && c->kr && proto_send(c, T_AUTH, name, (size_t)nl, NULL, 0)) {
        r = proto_recv(c, &f);
        if (r == RX_OK) {
            if (f.type == T_AUTH) {
                size_t n = f.len > 64 ? 64 : f.len;
                memcpy(c->peer, f.data, n);
                c->peer[n] = 0;
                free(f.buf);
                return c;
            }
            free(f.buf);
            r = RX_IO;
        }
    }
    *why = r == RX_AUTH ? HS_AUTH : HS_IO;
    c->s = INVALID_SOCKET; /* caller owns the socket on failure */
    proto_free(c);
    return NULL;
}
