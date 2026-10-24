#pragma once
#include "common.h"

void link_start(void);
void link_stop(void);
void link_restart(void);  /* re-read config: port, Bluetooth, password */

/* Returns LINK_NONE / LINK_WIFI / LINK_BT; copies the phone's name into peer if given. */
int link_state(WCHAR *peer, int peer_len);

/* All of these take ownership of the buffers. */
void link_send_frame(BYTE type, BYTE *data, size_t len);
void link_send_dib(BYTE *dib, size_t len);  /* encoded to PNG on the sender thread */
void link_send_files(FileList *files);      /* *files is moved; leaves it empty */
void link_cancel_files(void);
