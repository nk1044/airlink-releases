#pragma once
#include "common.h"

enum { ICON_WIFI, ICON_BT, ICON_WAITING, ICON_PAUSED };

/* Draws the tray icon for a state, matched to the taskbar's light/dark theme. */
HICON icon_make(int state);
