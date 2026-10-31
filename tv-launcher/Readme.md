# Air Launch — TV launcher

A home screen for the living-room TV (Android 9, MediaTek mt5862, 1280×720). It follows
[TV_LAUNCHER_TECH.md](TV_LAUNCHER_TECH.md) and the "orbittv" Home design.

Air Launch is only the home screen. It never writes a TV setting: the Settings tiles open the TV's own
screens, and the Home-app choice changes only when you ask it to.

## Build and install

```sh
scripts/install.sh <tv-ip>            # build release, install, AOT-compile (-m speed), open Air Launch
scripts/install.sh <tv-ip> --home     # …and make it the Home app
scripts/restore-vendor-home.sh <tv-ip> # hand Home back to the TV's launcher
scripts/measure.sh <tv-ip>            # dexopt, cold start, PSS, gfxinfo (§9 checklist)
```

This needs JDK 17. The script uses Homebrew's `openjdk@17` when `JAVA_HOME` isn't set. The APK
contains no native code, so a single universal APK covers every ABI.

## Making it the default launcher

From the TV: open **Settings › Air Launch › Default launcher** and choose **Choose Home app now**. The TV
then asks which Home app to use. Pick Air Launch, then **Always**. Some vendor builds hide this chooser.
In that case, run `scripts/install.sh <ip> --home` once.

**Open at TV startup** is a separate switch. It enables a boot receiver that brings Air Launch up after
the TV starts, which covers vendor builds that launch their own home regardless of your choice.
The switch is off by default, and while it's off the receiver is disabled and costs nothing.

## Remote

| Key | Action |
|---|---|
| D-pad | Move focus. Holding it scrolls rows; at the end of a row the card bumps |
| OK | Open |
| Hold OK, or Menu | Options: open, pin to the front, app info, uninstall |
| Back | Close the sheet, then go to the Home tab, then return to the top. On the top of Home it does nothing |
| Home (on Home) | Return to the top |

## How it stays fast

- Android Views only, with no Compose, Leanback, reflection or WebView. Dependencies are RecyclerView and ProfileInstaller.
- Every tile is one custom-drawn `View`. Focus animates `scaleX/Y` and `translationZ` (draw order,
  no shadows) plus one alpha value, so it never triggers a layout. Glows are ALPHA_8 masks that
  are blurred once in software.
- Motion uses damped springs baked into lookup tables (`ui/Motion.kt`), so each frame costs one
  array read. Pages slide on a strip, the options sheet springs in while the page behind it
  recedes, and launching zooms into the tile and zooms back out when you return.
- The app list loads from a small disk cache before the first frame, then refreshes in the
  background. `LauncherApps` callbacks catch installs and removals.
- Card art is rendered once per app version at its exact display size, with the corners baked in.
  It's cached in an LRU bounded to 14 MB and as PNGs on disk. When Home is hidden
  (`TRIM_MEMORY_UI_HIDDEN`), every bitmap is released and reloaded from disk when you come back.
- A held D-pad is throttled, and stale events are dropped, so focus never runs ahead of scrolling.
- `baseline-prof.txt` covers our code and RecyclerView, for installs made without adb.

## Code map

```
app/src/main/java/dev/orbit/launcher/
  HomeActivity.kt     root focus rules, page strip, launch/return motion, keys, memory trim
  OrbitApp.kt         one-time init (scale, fonts, caches)
  BootReceiver.kt     "Open at TV startup"
  data/               AppRepository (list, launch log, pins), Art (card art), Inputs (HDMI/AV), HomeRole
  ui/                 Motion, FocusCard + Cards, TvList (rows/grid), TopBar, Sheet, Hud
  pages/              Home, Apps, Inputs, Settings
```
