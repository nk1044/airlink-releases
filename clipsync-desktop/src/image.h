#pragma once
#include "common.h"

/* WIC helpers. Call from threads that have done CoInitializeEx. Outputs are malloc'd. */
BOOL img_dib_to_png(const BYTE *dib, size_t len, BYTE **out, size_t *out_len);
BOOL img_to_dib(const BYTE *data, size_t len, HGLOBAL *dib);  /* any WIC format -> 32bpp CF_DIB */
BOOL img_fit_jpeg(const BYTE *data, size_t len, size_t max, BYTE **out, size_t *out_len);
BOOL img_is_png(const BYTE *data, size_t len);
