#pragma once
#include "common.h"

/* State for one incoming file batch, owned by a connection's reader thread. */
typedef struct {
    HANDLE h;
    WCHAR part[1024], final[1024];
    UINT64 size, got;
    FileList done;
} RxState;

void files_rx_begin(RxState *rx, const BYTE *p, size_t n);
void files_rx_data(RxState *rx, const BYTE *p, size_t n);
void files_rx_end(RxState *rx);
void files_rx_cancel(RxState *rx);
void files_rx_done(RxState *rx);   /* batch finished: hand the list to the UI thread */
void files_rx_abort(RxState *rx);  /* connection lost */

BOOL files_download_dir(WCHAR *out, size_t n);
void files_install_sendto(void);
void files_remove_sendto(void);
void files_delete_self_on_exit(void); /* uninstall: deletes clipsync.exe a moment after the process exits */
