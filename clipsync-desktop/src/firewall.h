#pragma once
#include "common.h"

/* TRUE if Windows Firewall lets the phone reach clipsync.exe on every network type. Runs netsh; call off the UI thread. */
BOOL firewall_ok(void);
/* Replaces any rules for clipsync.exe with one "allow" rule. Shows the Windows admin prompt; returns FALSE if refused. */
BOOL firewall_fix(HWND owner);
/* Uninstall: are there rules for clipsync.exe, and remove them all (admin prompt). */
BOOL firewall_has_rules(void);
BOOL firewall_remove(HWND owner);
