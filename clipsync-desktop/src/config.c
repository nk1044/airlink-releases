#include "config.h"
#include "crypto.h"
#include <shlobj.h>
#include <wincrypt.h>
#include <stdio.h>
#include <wchar.h>

#define SECTION L"ClipSync"
#define RUN_KEY L"Software\\Microsoft\\Windows\\CurrentVersion\\Run"

Config g_cfg;
static CRITICAL_SECTION g_cs;
static WCHAR g_ini[MAX_PATH];

void config_lock(void) { EnterCriticalSection(&g_cs); }
void config_unlock(void) { LeaveCriticalSection(&g_cs); }

void config_snapshot(Config *out)
{
    config_lock();
    *out = g_cfg;
    config_unlock();
}

/* The key is stored DPAPI-encrypted (current Windows user only), hex-encoded. */
static void load_key(void)
{
    WCHAR hex[2048];
    BYTE blob[1024];
    DWORD n = GetPrivateProfileStringW(SECTION, L"Key", L"", hex, ARRAYSIZE(hex), g_ini);
    if (n < 2 || n / 2 > sizeof blob) return;
    for (DWORD i = 0; i < n / 2; i++) {
        unsigned v;
        if (swscanf(hex + 2 * i, L"%2x", &v) != 1) return;
        blob[i] = (BYTE)v;
    }
    DATA_BLOB in = { n / 2, blob }, out = { 0 };
    if (!CryptUnprotectData(&in, NULL, NULL, NULL, NULL, CRYPTPROTECT_UI_FORBIDDEN, &out)) return;
    if (out.cbData == 32) {
        memcpy(g_cfg.key, out.pbData, 32);
        g_cfg.has_key = TRUE;
    }
    SecureZeroMemory(out.pbData, out.cbData);
    LocalFree(out.pbData);
}

static void save_key(void)
{
    DATA_BLOB in = { 32, g_cfg.key }, out = { 0 };
    if (!g_cfg.has_key || !CryptProtectData(&in, L"ClipSync", NULL, NULL, NULL, CRYPTPROTECT_UI_FORBIDDEN, &out))
        return;
    WCHAR *hex = malloc(((size_t)out.cbData * 2 + 1) * sizeof(WCHAR));
    if (hex) {
        for (DWORD i = 0; i < out.cbData; i++) swprintf(hex + 2 * i, 3, L"%02x", out.pbData[i]);
        hex[out.cbData * 2] = 0;
        WritePrivateProfileStringW(SECTION, L"Key", hex, g_ini);
        free(hex);
    }
    LocalFree(out.pbData);
}

void config_load(void)
{
    InitializeCriticalSection(&g_cs);
    WCHAR *appdata;
    if (SUCCEEDED(SHGetKnownFolderPath(&FOLDERID_RoamingAppData, 0, NULL, &appdata))) {
        swprintf(g_ini, MAX_PATH, L"%s\\ClipSync", appdata);
        CreateDirectoryW(g_ini, NULL);
        wcscat(g_ini, L"\\clipsync.ini");
        CoTaskMemFree(appdata);
    }
    g_cfg.port = (int)GetPrivateProfileIntW(SECTION, L"Port", CS_TCP_PORT_DEFAULT, g_ini);
    g_cfg.bluetooth = GetPrivateProfileIntW(SECTION, L"Bluetooth", 1, g_ini) != 0;
    g_cfg.paused = GetPrivateProfileIntW(SECTION, L"Paused", 0, g_ini) != 0;
    GetPrivateProfileStringW(SECTION, L"PhoneName", L"", g_cfg.phone, ARRAYSIZE(g_cfg.phone), g_ini);
    GetPrivateProfileStringW(SECTION, L"Name", L"", g_cfg.name, ARRAYSIZE(g_cfg.name), g_ini);
    if (!g_cfg.name[0]) {
        DWORD n = ARRAYSIZE(g_cfg.name);
        if (!GetComputerNameW(g_cfg.name, &n)) wcscpy(g_cfg.name, L"PC");
    }
    load_key();
}

void config_save(void)
{
    WCHAR num[16];
    swprintf(num, 16, L"%d", g_cfg.port);
    WritePrivateProfileStringW(SECTION, L"Port", num, g_ini);
    WritePrivateProfileStringW(SECTION, L"Bluetooth", g_cfg.bluetooth ? L"1" : L"0", g_ini);
    WritePrivateProfileStringW(SECTION, L"Paused", g_cfg.paused ? L"1" : L"0", g_ini);
    WritePrivateProfileStringW(SECTION, L"Name", g_cfg.name, g_ini);
    save_key();
}

BOOL config_set_password(const WCHAR *password)
{
    BYTE key[32];
    int n;
    char *pw = utf8_from_wide(password, (int)wcslen(password), &n);
    if (!pw) return FALSE;
    BOOL ok = crypto_pbkdf2(pw, (size_t)n, key);
    SecureZeroMemory(pw, (size_t)n);
    free(pw);
    if (!ok) return FALSE;
    config_lock();
    memcpy(g_cfg.key, key, 32);
    g_cfg.has_key = TRUE;
    config_unlock();
    SecureZeroMemory(key, sizeof key);
    return TRUE;
}

void config_set_phone_ip(const struct in_addr *ip)
{
    WCHAR now[INET_ADDRSTRLEN], old[INET_ADDRSTRLEN];
    if (!InetNtopW(AF_INET, ip, now, ARRAYSIZE(now))) return;
    GetPrivateProfileStringW(SECTION, L"PhoneIP", L"", old, ARRAYSIZE(old), g_ini);
    if (wcscmp(now, old)) WritePrivateProfileStringW(SECTION, L"PhoneIP", now, g_ini);
}

BOOL config_get_phone_ip(struct in_addr *ip)
{
    WCHAR s[INET_ADDRSTRLEN];
    GetPrivateProfileStringW(SECTION, L"PhoneIP", L"", s, ARRAYSIZE(s), g_ini);
    return s[0] && InetPtonW(AF_INET, s, ip) == 1;
}

void config_set_phone_bt(UINT64 addr)
{
    WCHAR now[20], old[20];
    swprintf(now, ARRAYSIZE(now), L"%012llx", (unsigned long long)addr);
    GetPrivateProfileStringW(SECTION, L"PhoneBT", L"", old, ARRAYSIZE(old), g_ini);
    if (wcscmp(now, old)) WritePrivateProfileStringW(SECTION, L"PhoneBT", now, g_ini);
}

UINT64 config_get_phone_bt(void)
{
    WCHAR s[20];
    GetPrivateProfileStringW(SECTION, L"PhoneBT", L"", s, ARRAYSIZE(s), g_ini);
    return wcstoull(s, NULL, 16);
}

BOOL config_firewall_done(void)
{
    return GetPrivateProfileIntW(SECTION, L"FirewallDone", 0, g_ini) != 0;
}

void config_set_firewall_done(void)
{
    WritePrivateProfileStringW(SECTION, L"FirewallDone", L"1", g_ini);
}

void config_set_key(const BYTE key[32], const WCHAR *phone)
{
    config_lock();
    memcpy(g_cfg.key, key, 32);
    g_cfg.has_key = TRUE;
    wcsncpy(g_cfg.phone, phone, ARRAYSIZE(g_cfg.phone) - 1);
    save_key();
    config_unlock();
    WritePrivateProfileStringW(SECTION, L"PhoneName", phone, g_ini);
}

void config_delete_all(void)
{
    WCHAR dir[MAX_PATH];
    DeleteFileW(g_ini);
    wcscpy(dir, g_ini);
    WCHAR *slash = wcsrchr(dir, L'\\');
    if (slash) {
        *slash = 0;
        RemoveDirectoryW(dir);
    }
}

BOOL autostart_get(void)
{
    return RegGetValueW(HKEY_CURRENT_USER, RUN_KEY, L"ClipSync", RRF_RT_REG_SZ, NULL, NULL, NULL) == ERROR_SUCCESS;
}

void autostart_set(BOOL on)
{
    HKEY k;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, RUN_KEY, 0, KEY_SET_VALUE, &k) != ERROR_SUCCESS) return;
    if (on) {
        WCHAR exe[MAX_PATH], cmd[MAX_PATH + 2];
        GetModuleFileNameW(NULL, exe, MAX_PATH);
        swprintf(cmd, ARRAYSIZE(cmd), L"\"%s\"", exe);
        RegSetValueExW(k, L"ClipSync", 0, REG_SZ, (const BYTE *)cmd, (DWORD)((wcslen(cmd) + 1) * sizeof(WCHAR)));
    } else {
        RegDeleteValueW(k, L"ClipSync");
    }
    RegCloseKey(k);
}
