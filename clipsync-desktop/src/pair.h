#pragma once
#include "common.h"

/*
 * Linking a phone, once: ECDH P-256 plus a 6-digit code the user compares on both screens.
 * The result is a random 256-bit key that both sides keep for good. Protocol in plan.md.
 */
void pair_show(HWND owner);           /* modal "Link a phone" window */
BOOL pair_mode(void);                 /* TRUE while that window is open: the PC answers link requests */
void pair_start(SOCKET s, int kind);  /* "CSYP" arrived on s (magic already read); takes ownership of s */
