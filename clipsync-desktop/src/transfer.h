#pragma once
#include "common.h"

/*
 * File transfer progress: worker threads update plain counters (no messages per chunk), and a small window
 * near the tray polls them a few times a second while it's showing.
 */
enum { TX_SEND = 0, TX_RECV = 1 };
enum { TXR_OK, TXR_CANCELLED, TXR_FAILED, TXR_NO_LINK };

/* Worker threads. */
void tx_start(int dir, int files, UINT64 total);   /* files/total 0 = not known (receiving) */
void tx_connected(int dir, const char *peer_utf8); /* the transfer is under way (ends "waiting for the phone") */
void tx_file(int dir, int index, const WCHAR *name, UINT64 size);
void tx_add(int dir, UINT64 bytes);
void tx_finish(int dir, int result, int count);
BOOL tx_active(int dir);

/* UI thread. */
void transfer_init(void);
void transfer_on_message(WPARAM wp, LPARAM lp);      /* WM_APP_TX */
void transfer_received(const FileList *files);       /* remembers the last file for "Open folder" */
void transfer_show(void);                            /* bring back a hidden window */
BOOL transfer_window_visible(void);
BOOL transfer_dialog_message(MSG *msg);
BOOL transfer_tip(WCHAR *tip, size_t n);             /* tray tooltip while a transfer runs */

/* Provided by main.c. */
void tray_update(void);
void tray_set_tip(const WCHAR *tip);
void tray_balloon(const WCHAR *text, const WCHAR *folder);
