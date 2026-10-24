#pragma once
#include "common.h"

typedef struct {
    BYTE  key[32];   /* PBKDF2 master key derived from the password */
    BOOL  has_key;
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

BOOL autostart_get(void);
void autostart_set(BOOL on);
