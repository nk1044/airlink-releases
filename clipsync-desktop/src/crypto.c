#include "crypto.h"

#ifndef BCRYPT_KDF_RAW_SECRET
#define BCRYPT_KDF_RAW_SECRET L"TRUNCATE"
#endif

static BCRYPT_ALG_HANDLE g_hmac, g_sha, g_aes, g_ecdh;

BOOL crypto_init(void)
{
    if (!BCRYPT_SUCCESS(BCryptOpenAlgorithmProvider(&g_hmac, BCRYPT_SHA256_ALGORITHM, NULL, BCRYPT_ALG_HANDLE_HMAC_FLAG)))
        return FALSE;
    if (!BCRYPT_SUCCESS(BCryptOpenAlgorithmProvider(&g_sha, BCRYPT_SHA256_ALGORITHM, NULL, 0)))
        return FALSE;
    if (!BCRYPT_SUCCESS(BCryptOpenAlgorithmProvider(&g_ecdh, BCRYPT_ECDH_P256_ALGORITHM, NULL, 0)))
        return FALSE;
    if (!BCRYPT_SUCCESS(BCryptOpenAlgorithmProvider(&g_aes, BCRYPT_AES_ALGORITHM, NULL, 0)))
        return FALSE;
    return BCRYPT_SUCCESS(BCryptSetProperty(g_aes, BCRYPT_CHAINING_MODE, (PUCHAR)BCRYPT_CHAIN_MODE_GCM,
                                            sizeof(BCRYPT_CHAIN_MODE_GCM), 0));
}

void crypto_random(void *buf, ULONG len)
{
    BCryptGenRandom(NULL, buf, len, BCRYPT_USE_SYSTEM_PREFERRED_RNG);
}

BOOL crypto_pbkdf2(const char *password, size_t len, BYTE out[32])
{
    static const char salt[] = "ClipSync/v1";
    return BCRYPT_SUCCESS(BCryptDeriveKeyPBKDF2(g_hmac, (PUCHAR)password, (ULONG)len, (PUCHAR)salt,
                                                sizeof salt - 1, 200000, out, 32, 0));
}

void crypto_hmac(const BYTE key[32], const Buf *parts, int nparts, BYTE out[32])
{
    BCRYPT_HASH_HANDLE h;
    memset(out, 0, 32);
    if (!BCRYPT_SUCCESS(BCryptCreateHash(g_hmac, &h, NULL, 0, (PUCHAR)key, 32, 0))) return;
    for (int i = 0; i < nparts; i++)
        BCryptHashData(h, (PUCHAR)parts[i].p, (ULONG)parts[i].n, 0);
    BCryptFinishHash(h, out, 32, 0);
    BCryptDestroyHash(h);
}

void crypto_sha256(const Buf *parts, int nparts, BYTE out[32])
{
    BCRYPT_HASH_HANDLE h;
    memset(out, 0, 32);
    if (!BCRYPT_SUCCESS(BCryptCreateHash(g_sha, &h, NULL, 0, NULL, 0, 0))) return;
    for (int i = 0; i < nparts; i++)
        BCryptHashData(h, (PUCHAR)parts[i].p, (ULONG)parts[i].n, 0);
    BCryptFinishHash(h, out, 32, 0);
    BCryptDestroyHash(h);
}

BCRYPT_KEY_HANDLE crypto_ecdh_new(BYTE pub[64])
{
    BCRYPT_KEY_HANDLE k;
    BYTE blob[sizeof(BCRYPT_ECCKEY_BLOB) + 64];
    ULONG cb;
    if (!BCRYPT_SUCCESS(BCryptGenerateKeyPair(g_ecdh, &k, 256, 0))) return NULL;
    if (!BCRYPT_SUCCESS(BCryptFinalizeKeyPair(k, 0)) ||
        !BCRYPT_SUCCESS(BCryptExportKey(k, NULL, BCRYPT_ECCPUBLIC_BLOB, blob, sizeof blob, &cb, 0)) || cb != sizeof blob) {
        BCryptDestroyKey(k);
        return NULL;
    }
    memcpy(pub, blob + sizeof(BCRYPT_ECCKEY_BLOB), 64);
    return k;
}

BOOL crypto_ecdh_shared(BCRYPT_KEY_HANDLE priv, const BYTE peer_pub[64], BYTE out[32])
{
    BYTE blob[sizeof(BCRYPT_ECCKEY_BLOB) + 64], raw[32];
    BCRYPT_ECCKEY_BLOB *hdr = (BCRYPT_ECCKEY_BLOB *)blob;
    BCRYPT_KEY_HANDLE peer;
    BCRYPT_SECRET_HANDLE secret;
    ULONG cb;
    BOOL ok = FALSE;
    hdr->dwMagic = BCRYPT_ECDH_PUBLIC_P256_MAGIC;
    hdr->cbKey = 32;
    memcpy(blob + sizeof *hdr, peer_pub, 64);
    /* Import validates that the point is on the curve. */
    if (!BCRYPT_SUCCESS(BCryptImportKeyPair(g_ecdh, NULL, BCRYPT_ECCPUBLIC_BLOB, &peer, blob, sizeof blob, 0))) return FALSE;
    if (BCRYPT_SUCCESS(BCryptSecretAgreement(priv, peer, &secret, 0))) {
        if (BCRYPT_SUCCESS(BCryptDeriveKey(secret, BCRYPT_KDF_RAW_SECRET, NULL, raw, 32, &cb, 0)) && cb == 32) {
            for (int i = 0; i < 32; i++) out[i] = raw[31 - i]; /* CNG returns the raw secret little-endian */
            ok = TRUE;
        }
        BCryptDestroySecret(secret);
    }
    BCryptDestroyKey(peer);
    SecureZeroMemory(raw, sizeof raw);
    return ok;
}

BCRYPT_KEY_HANDLE crypto_aes_key(const BYTE key[32])
{
    BCRYPT_KEY_HANDLE k = NULL;
    if (!BCRYPT_SUCCESS(BCryptGenerateSymmetricKey(g_aes, &k, NULL, 0, (PUCHAR)key, 32, 0))) return NULL;
    return k;
}

static void auth_info(BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO *ai, const BYTE nonce[12], const BYTE tag[16])
{
    BCRYPT_INIT_AUTH_MODE_INFO(*ai);
    ai->pbNonce = (PUCHAR)nonce;
    ai->cbNonce = 12;
    ai->pbTag = (PUCHAR)tag;
    ai->cbTag = 16;
}

BOOL crypto_seal(BCRYPT_KEY_HANDLE k, const BYTE nonce[12], BYTE *buf, ULONG len, BYTE tag[16])
{
    BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO ai;
    ULONG out;
    auth_info(&ai, nonce, tag);
    return BCRYPT_SUCCESS(BCryptEncrypt(k, buf, len, &ai, NULL, 0, buf, len, &out, 0));
}

BOOL crypto_open(BCRYPT_KEY_HANDLE k, const BYTE nonce[12], BYTE *buf, ULONG len, const BYTE tag[16])
{
    BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO ai;
    ULONG out;
    auth_info(&ai, nonce, tag);
    return BCRYPT_SUCCESS(BCryptDecrypt(k, buf, len, &ai, NULL, 0, buf, len, &out, 0));
}
