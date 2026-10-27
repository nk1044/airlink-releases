#pragma once
#include "common.h"

/* UI thread only. */
#define CLIP_TIMER    7   /* SetTimer id: read the clipboard a moment after it changes */
#define CLIP_DELAY_MS 150

void clip_init(void);
void clip_changed(HWND hwnd);       /* CLIP_TIMER fired: read and send the new clipboard */
void clip_on_connected(HWND hwnd);  /* sends the latest copy if it was made while the phone was away */
void clip_set_text(HWND hwnd, const char *utf8, size_t len);
void clip_set_image(HWND hwnd, RecvImage *img);
void clip_set_files(HWND hwnd, const FileList *files);
BOOL clip_get_files(HWND hwnd, FileList *out);
