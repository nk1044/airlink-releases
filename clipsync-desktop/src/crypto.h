#pragma once
#include "common.h"
#include <bcrypt.h>

typedef struct { const void *p; size_t n; } Buf;

BOOL crypto_init(void);
void crypto_random(void *buf, ULONG len);
/* master = PBKDF2-HMAC-SHA256(password, "ClipSync/v1", 200000) */
BOOL crypto_pbkdf2(const char *password, size_t len, BYTE out[32]);
/* HMAC-SHA256 over the concatenation of parts. */
void crypto_hmac(const BYTE key[32], const Buf *parts, int nparts, BYTE out[32]);

BCRYPT_KEY_HANDLE crypto_aes_key(const BYTE key[32]);
/* AES-256-GCM in place; 12-byte nonce, 16-byte tag. */
BOOL crypto_seal(BCRYPT_KEY_HANDLE k, const BYTE nonce[12], BYTE *buf, ULONG len, BYTE tag[16]);
BOOL crypto_open(BCRYPT_KEY_HANDLE k, const BYTE nonce[12], BYTE *buf, ULONG len, const BYTE tag[16]);
