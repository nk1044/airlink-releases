#pragma once
#include "common.h"

typedef struct {
    BYTE  key[32];   /* shared key from linking (or PBKDF2 of a password) */
    BOOL  has_key;
    WCHAR phone[65]; /* name of the linked phone */
    WCHAR name[33];
    int   port;
    BOOL  bluetooth;
    BOOL  paused;
} Config;

/* Owned by the UI thread; other threads read it through config_snapshot(). */
extern Config g_cfg;

void config_load(void);
void config_save(void);
void config_lock(void);
void config_unlock(void);
void config_snapshot(Config *out);
BOOL config_set_password(const WCHAR *password);
/* Stores the key from linking a phone, and saves it right away. Any thread. */
void config_set_key(const BYTE key[32], const WCHAR *phone);
/* Uninstall: deletes the settings file and folder. */
void config_delete_all(void);

BOOL autostart_get(void);
void autostart_set(BOOL on);

/* Where the phone was last seen, so the PC can tell it "I'm back" after a restart or sleep. Any thread. */
void config_set_phone_ip(const struct in_addr *ip);
BOOL config_get_phone_ip(struct in_addr *ip);
void config_set_phone_bt(UINT64 addr);
UINT64 config_get_phone_bt(void);

BOOL config_firewall_done(void);
void config_set_firewall_done(void);
