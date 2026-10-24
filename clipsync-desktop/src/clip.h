#pragma once
#include "common.h"

/* UI thread only. */
void clip_init(void);
void clip_changed(HWND hwnd);  /* WM_CLIPBOARDUPDATE */
void clip_set_text(HWND hwnd, const char *utf8, size_t len);
void clip_set_image(HWND hwnd, RecvImage *img);
void clip_set_files(HWND hwnd, const FileList *files);
BOOL clip_get_files(HWND hwnd, FileList *out);
