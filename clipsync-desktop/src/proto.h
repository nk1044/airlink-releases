#pragma once
#include "common.h"
#include <bcrypt.h>

/* Wire protocol v1; see plan.md. Must match the Android Proto.kt. */
enum {
    T_AUTH = 0x01, T_PING = 0x02, T_PONG = 0x03,
    T_TEXT = 0x10, T_IMAGE = 0x11,
    T_FILE_BEGIN = 0x20, T_FILE_DATA = 0x21, T_FILE_END = 0x22, T_FILE_CANCEL = 0x23, T_FILES_DONE = 0x24,
};

enum { LINK_NONE = 0, LINK_WIFI = 1, LINK_BT = 2 };
enum { RX_OK = 1, RX_IO = 0, RX_AUTH = -1 };
enum { HS_IO = 1, HS_AUTH = 2 };

#define PAYLOAD_MAX (16u * 1024 * 1024)
#define FRAME_MAX   (PAYLOAD_MAX + 17) /* largest length field: type + payload + tag */
#define CHUNK_SIZE  65536

typedef struct Conn {
    volatile LONG refs;
    SOCKET s;
    int kind;                       /* LINK_WIFI or LINK_BT */
    BCRYPT_KEY_HANDLE ks, kr;       /* same session key; one handle per direction/thread */
    UINT64 ns, nr;                  /* nonce counters */
    BYTE ds, dr;                    /* nonce direction bytes */
    volatile ULONGLONG last_send, last_recv;
    char peer[65];
} Conn;

typedef struct {
    BYTE type;
    BYTE *data;
    size_t len;
    BYTE *buf; /* free() this */
} Frame;

/* Returns NULL on failure without closing s; *why is HS_IO or HS_AUTH (wrong password). */
Conn *proto_handshake(SOCKET s, int kind, const BYTE master[32], BOOL server, const char *name, int *why);
BOOL proto_send(Conn *c, BYTE type, const void *a, size_t alen, const void *b, size_t blen);
int proto_recv(Conn *c, Frame *f);
void proto_free(Conn *c);
