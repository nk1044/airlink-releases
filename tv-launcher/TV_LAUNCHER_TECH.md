ui details:-
https://claude.ai/code/artifact/198b62cd-ba67-4a7c-99de-fa8142a52080

Read the design's files from that link. Match how it looks, using this codebase's own components and conventions.


# TV launcher: technical brief

Reference for building a fast, rich home-screen launcher for the living-room TV. Everything under
"The device" was measured on the TV over adb on 2026-10-31. Everything else follows from those
measurements, or was learned while optimising the Nitflix TV app (`app-tv-android/`) on the same TV.

---

## 1. The device

| | Measured | What it means |
|---|---|---|
| Board | MediaTek/MStar **mt5862** (`mt9255l`), CVTE MSD338 board, 512 MB variant | A budget TV chip |
| CPU | 4 × **Cortex-A53** (`CPU part 0xd03`), **32-bit userland** (`zygote32`, ABIs `armeabi-v7a, armeabi`) | Ship `armeabi-v7a` only; no 64-bit runtime |
| Android | **9** (API 28), `user` build | `minSdk` ≤ 28; no API 29+ features |
| JIT | **`dalvik.vm.usejit=false`**, `usejitprofiles=false` | **Code runs in the interpreter unless it's compiled ahead of time** (§3) |
| RAM | 430 MB total, **~110 MB available**, zram swap in use, `ro.config.low_ram=true` | The low-memory killer is aggressive, so plan for being killed |
| Java heap | `heapgrowthlimit` **48 MB** (`largeHeap` 128 MB), `heapstartsize` 5 MB | A hard ceiling for Java objects |
| Screen | **1280×720 @ 60 Hz only**, density **160 (mdpi)** | **1 dp = 1 px**. Frame budget is 16.6 ms. No 1080p UI is needed |
| GPU | **Mali-G31**, OpenGL ES 3.2, HWUI renderer `opengl` (not Vulkan) | Low fill rate: overdraw and blur are expensive |
| HDR | Panel reports HDR10, but it is a 720p panel | Irrelevant to a launcher |
| System animations | `window_animation_scale=0`, `animator_duration_scale=1` | Window transitions are off, view animators still run |
| Features | `leanback`, `leanback_only`, `live_tv`, `app_widgets`, `webview` | A true Android TV device: TV Input Framework is present |
| Current launcher | `com.vstv.mtk.samsung.tv/.activity.SaActivity` (vendor), fallback `com.vstv.fbhome/.FallbackHome` | Restore with the command in §8 |
| TV inputs | `com.mediatek.tvinput/.hdmi.HDMIInputService/HW5`, `/HW6`; composite `HW2`, `HW3`; a Zee5 TvInputService | HDMI and AV switching are possible (§5.4) |
| Media decoders (for previews) | H.264, HEVC (incl. 10-bit), VP9 up to 1920×1088 in hardware (`OMX.MS.*`). **No AC-3 decoder exposed to apps** | Use muted or AAC trailers for background video, if any |
| Baseline (Nitflix UI, release, AOT) | 2337 frames: **17.5% janky**, p50 8 ms, p90 22 ms, p99 77 ms | The number to beat |

Other apps already resident (PSS): `system` 40 MB, GMS 15 MB, vendor channel services ~18 MB. The
launcher competes with these for the ~110 MB that's left. Target **< 45 MB PSS** while idle on
Home.

---

## 2. Stack choice

| Use | Avoid | Why |
|---|---|---|
| Kotlin + **Android Views** + `RecyclerView` (as Nitflix TV does) | **Jetpack Compose** | Compose does far more work per frame in managed code. With no JIT and a 32-bit A53, recomposition and layout cost is high, and Compose's startup is heavy |
| Plain `RecyclerView` rows, custom focus handling | Leanback library (`BrowseSupportFragment`) | Leanback is heavy, dated, and hard to make fast. Nitflix TV skipped it and works fine |
| `kotlinx.serialization` (code-generated) or hand-written JSON | Gson, Moshi-reflect, Jackson | Reflection in the interpreter is very slow (seen as `ArtField::GetTypeDescriptor` and `FindFieldFast` hot spots in simpleperf) |
| Coroutines on `Dispatchers.Default`/`IO` for package and disk work | RxJava | Smaller and simpler |
| `BitmapFactory`, or Coil/Glide configured for `RGB_565` and exact sizes | Loading full-size images | 48 MB heap, and bitmaps count toward PSS |
| Static or pre-blurred images | `RenderEffect` blur (API 31+, unavailable), RenderScript blur, WebView, video wallpapers | Fill rate, memory, CPU |

---

## 3. AOT compilation (the most important item)

With `usejit=false`, an installed APK runs **interpreted** unless ART compiled it. On this TV that
made ExoPlayer's thread run at 97% of a core, and the same code dropped to ~60% once compiled.
UI code suffers the same way: list scrolling, focus animations, layout and JSON parsing.

Rules:

1. **Test performance only on a release build** (`isDebuggable = false`). ART ignores compiled code
   for debuggable apps; this was verified on this TV (a compiled debug build still stuttered).
2. **After every adb install, compile:**
   ```sh
   adb install -r app-armeabi-v7a-release.apk
   adb shell cmd package compile -m speed -f <your.package>     # ~1 min on this TV
   adb shell dumpsys package dexopt | grep -A2 "\[<your.package>\]"   # expect status=speed
   ```
   Put this in the install script, and only run it when `getprop dalvik.vm.usejit` is `false`.
   See `app-tv-android/scripts/run-app.sh` for a working version.
3. **For installs without adb** (a USB stick, a file manager), add **Baseline Profiles**
   (`androidx.profileinstaller` plus a `baseline-prof.txt` that covers startup, focus moves and row
   scrolling). The install reason is `speed-profile`. ProfileInstaller writes the profile, and the
   background dexopt job (`pm.dexopt.bg-dexopt=speed-profile`) compiles those methods during
   idle maintenance. That happens later, not at install, so adb + `-m speed` is still the reliable
   path.
4. Keep the dex small (R8 full mode, `isMinifyEnabled`, `isShrinkResources`). Less code means
   faster compiles and less to page in.

---

## 4. Memory budget (48 MB heap, ~110 MB device headroom)

- **Bitmaps:** pixels live in the native heap (API 26+), so they don't hit the 48 MB Java limit,
  but they count toward PSS and the low-memory killer. Budget **≤ 16 MB** of decoded bitmaps:
  - App banners are **320×180 px** (mdpi). As ARGB_8888 that's 225 KB each, so 40 banners ≈ 9 MB.
    Decode banners at their display size, never larger.
  - Full-screen background: 1280×720. **RGB_565 ≈ 1.8 MB**, versus 3.6 MB for ARGB_8888. Use
    RGB_565 for anything opaque (backdrops, photos). Keep **one** backdrop in memory, plus the
    next one prefetched.
  - Use an `LruCache<String, Bitmap>` sized in bytes (`bitmap.allocationByteCount`).
- **`onTrimMemory(level)`:** at `TRIM_MEMORY_UI_HIDDEN`, drop the backdrop and off-screen
  banners. At `RUNNING_LOW`/`RUNNING_CRITICAL`, clear every cache. A launcher is backgrounded
  while apps run (Nitflix needs every MB for playback). Being small in the background is what keeps
  it from being killed, and it must cold-start fast when it is killed anyway.
- Don't use `android:largeHeap`. On a low-RAM device it only gets you killed sooner.
- **Single process**, no long-running services, no `WorkManager` for UI data.
- **Avoid WebView** (+30–60 MB), Google Play Services APIs, and Firebase.
- Fonts: one bundled family with **at most 2–3 weights**, loaded once (`Typeface` cached in an
  object).

---

## 5. Becoming the launcher

### 5.1 Manifest

```xml
<uses-feature android:name="android.software.leanback" android:required="true" />
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />
<!-- Android 11+ only (ignored on this API 28 TV); keeps the app list working on newer TVs -->
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />

<activity
    android:name=".HomeActivity"
    android:exported="true"
    android:launchMode="singleTask"
    android:stateNotNeeded="true"
    android:clearTaskOnLaunch="true"
    android:excludeFromRecents="true"
    android:resumeWhilePausing="true"
    android:screenOrientation="landscape"
    android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenLayout|screenSize|smallestScreenSize|uiMode|locale|layoutDirection"
    android:theme="@style/Theme.Launcher">       <!-- windowBackground = the static wallpaper, or a solid colour -->
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.HOME" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
    </intent-filter>
</activity>
```

- **HOME pressed while Home is already showing** arrives in `onNewIntent()`. Scroll back to the
  first row and close any open panel. Don't recreate the activity.
- Swallow **Back** on the root screen (a launcher has nothing behind it).
- Set the theme's `android:windowBackground` to the wallpaper drawable, so the first frame is
  never black.

### 5.2 Listing apps

```kotlin
val pm = context.packageManager
val tv = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
val phone = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
// TV entries first; phone-only apps (sideloaded) as a fallback, de-duplicated by package
```

- Banner: `activityInfo.loadBanner(pm)` → `applicationInfo.loadBanner(pm)` → fall back to
  `loadIcon(pm)` drawn on a coloured 320×180 card. Load banners **on `Dispatchers.IO`**.
  `loadBanner` decodes from another APK and is slow.
- Launch: `pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)`, with
  `FLAG_ACTIVITY_NEW_TASK`.
- **Package changes:** use `LauncherApps.registerCallback(...)` (`onPackageAdded`, `Removed`,
  `Changed`). It's more reliable than manifest broadcasts, which are restricted since API 26.
- **Cache the app list to disk** (package, activity, label, last-used time) and render it from
  the cache on cold start, then refresh in the background. Don't block the first frame on
  PackageManager.
- **Order by usage:** record launches yourself. `UsageStatsManager` needs a special permission
  the user must grant in Settings, which is often unreachable on vendor TVs.

### 5.3 Rows a third-party launcher *can* and *can't* fill

| Feature | Possible? | How |
|---|---|---|
| Apps / Favourites / recently launched by you | Yes | §5.2 plus your own launch log |
| HDMI / AV inputs | Yes | §5.4 |
| Wi-Fi, Bluetooth, display settings | Yes | `Settings.ACTION_SETTINGS`, `ACTION_WIFI_SETTINGS`, `ACTION_BLUETOOTH_SETTINGS`, … (check `resolveActivity` first, because vendors strip some) |
| Clock / date / weather | Yes | Clock: `TextClock`, or a coroutine on the minute boundary, never a per-second timer. Weather: one HTTP call every 30–60 min, cached |
| Other apps' "channels" / Play Next / Watch Next | **No** | Reading other apps' rows in `TvContract` needs `READ_EPG_DATA`/`ACCESS_ALL_EPG_DATA`, which are signature/privileged permissions (both present on this TV, neither grantable to you) |
| Recents / running apps | **No** | `getRecentTasks` is limited to your own tasks since API 21 |
| Notifications | Only with notification-listener access | `NotificationListenerService` needs the user to enable it in Settings, often hidden on vendor builds |
| Nitflix "Continue watching" | Yes, with a small change to Nitflix | §7 |

### 5.4 Switching to HDMI / AV

```kotlin
val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
val inputs = tim.tvInputList.filter { it.isPassthroughInput }   // HDMI / composite
// Label: info.loadLabel(context); hide disconnected ports: tim.getInputState(info.id) != INPUT_STATE_DISCONNECTED
val uri = TvContract.buildChannelUriForPassthroughInput(info.id)
startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
```

This should open the vendor's live-TV app on that input (not yet tested on this TV). Input states
are `0` connected, `1` connected-standby, `2` disconnected. `dumpsys tv_input` showed HDMI HW5/HW6
at `1` and composite HW3 at `0`, so show `0` and `1`. If no activity resolves the `ACTION_VIEW`,
hide the row.

---

## 6. Rendering and focus at 60 fps on a Mali-G31

Frame budget: **16.6 ms**, shared between the UI thread and RenderThread, on a 1.x GHz A53.

**Layout and lists**
- Home is a vertical `RecyclerView` of horizontal `RecyclerView` rows, all sharing **one
  `RecycledViewPool`**. Use `setHasFixedSize(true)`, stable ids, and fixed card sizes, so a focus
  move never triggers `requestLayout()`.
- Nested rows: `(layoutManager as LinearLayoutManager).initialPrefetchItemCount = visibleCards + 1`.
- Run `DiffUtil` off the main thread (`AsyncListDiffer` / `ListAdapter`).
- Use flat hierarchies: one `FrameLayout` per card (image + text), no nested `ConstraintLayout`
  inside cards. Build views in code or with simple XML; avoid inflating complex layouts per bind.
- Use fixed-width `TextView`s with `maxLines`/`ellipsize` set once. Never `wrap_content` text in
  a card that changes on focus.

**Focus animation**
- Animate only **`scaleX`/`scaleY`/`translationX/Y`/`alpha`** with `View.animate()` (≈150 ms,
  `DecelerateInterpolator`). These are RenderThread properties and need no layout or redraw of
  children.
- Draw the focus ring as a **separate overlay view** that moves and scales over the focused card,
  or as a 9-patch on the card that only changes on state. Never resize views on focus.
- Leave **elevation and shadows** out of cards. Shadows cost extra passes on a Mali-G31; a
  border or a light ring reads well at 720p.
- When scrolling a row to keep focus at a fixed position, use `smoothScrollBy` with a short
  duration, or a custom `LinearSmoothScroller` with a lower `calculateSpeedPerPixel`. Throttle key
  repeats: a held D-pad fires about 20 events/s, so move focus at most once per frame and drop the
  rest.

**Overdraw and fill rate**
- The window background *is* the wallpaper. Don't draw a second full-screen background. After the
  first frame, `window.setBackgroundDrawable(null)` if a full-screen view covers it.
- Hero / backdrop cross-fade: two `ImageView`s, alpha-animate the top one. **Pre-blur and pre-darken
  assets at build time or on download**; no runtime blur.
- Gradients for readability: one `GradientDrawable` scrim, not stacked translucent layers.
- Check with Developer options → "Debug GPU overdraw". Keep most of the screen at 1×, cards at
  most 2×.

**Video in the hero (optional)**
- Only after **3 s of focus rest**, muted, ≤ 720p H.264, in a `SurfaceView`, not a `TextureView`
  (TextureView costs an extra GPU copy). Release the player when focus moves or Home is hidden.
  One ExoPlayer instance at most. AC-3 trailers would need FFmpeg, so keep them muted.

**Measure**
```sh
adb shell dumpsys gfxinfo <pkg> reset
# … scroll Home for 20 s with the remote …
adb shell dumpsys gfxinfo <pkg> | grep -E "Total frames|Janky|percentile"
adb shell dumpsys gfxinfo <pkg> framestats      # per-frame breakdown
```
Targets: **janky < 5%, p90 ≤ 16 ms, p99 ≤ 32 ms** (Nitflix's UI today is 17.5% / 22 / 77 ms).

---

## 7. Integrating with Nitflix (`com.nitflix.tv`)

- Launch: `Intent().setClassName("com.nitflix.tv", "com.nitflix.tv.MainActivity")`, which is
  exported.
- **Deep links / Continue Watching are not exposed yet.** `PlayerActivity` is exported only in
  debug builds (`app-tv-android/app/src/debug/AndroidManifest.xml`). Its extras are `id` (TMDB id,
  int), `kind` (`movie`/`tv`), `name`, `year`, `season`, `episode`, `imdb`. Watch progress lives
  in Nitflix's private SharedPreferences (`library.xml`). To surface it on Home, add to Nitflix:
  1. an exported, read-only **`ContentProvider`** (e.g. `content://com.nitflix.tv.library/continue`)
     returning title id, kind, name, backdrop URL, season/episode and progress, guarded by a
     custom **signature permission** both apps hold (sign both with the same key);
  2. an exported activity with an action such as `com.nitflix.tv.action.PLAY`, carrying the
     extras above.
  The launcher then queries the provider when Home resumes (cheap, local), not on a timer.

---

## 8. Workflow on this TV

```sh
# connect (if "No route to host" while the TV pings fine: restart adb's server)
adb kill-server && adb connect 192.168.1.6:5555

# install + compile + make it Home
adb install -r app/build/outputs/apk/release/app-armeabi-v7a-release.apk
adb shell cmd package compile -m speed -f <your.package>
adb shell cmd package set-home-activity <your.package>/.HomeActivity
adb shell input keyevent KEYCODE_HOME

# back to the vendor launcher at any time
adb shell cmd package set-home-activity com.vstv.mtk.samsung.tv/.activity.SaActivity

# memory, frames, logs
adb shell dumpsys meminfo <your.package> | head -30
adb shell dumpsys gfxinfo <your.package>
adb logcat -G 16M                               # the TV's default log buffer is tiny

# CPU profile (works on this user build)
adb shell setprop security.perf_harden 0
adb shell run-as <your.package> simpleperf record -p $(adb shell pidof <your.package>) --duration 10 -o /data/data/<your.package>/perf.data
adb shell run-as <your.package> simpleperf report -i /data/data/<your.package>/perf.data --sort comm,dso,symbol | head -40
# run-as needs a debuggable build: profile a debug build for *where* time goes, judge speed on release.
# Don't use -g (call graphs): the on-device report hangs. A profile full of art::interpreter::*
# means the code isn't compiled (§3).
```

Gradle notes (same as Nitflix): JDK 17. Set `ANDROID_HOME` or `sdk.dir` in `local.properties`.
ABI split `armeabi-v7a` (add `arm64-v8a` only for other TVs).

---

## 9. Checklist before calling it "optimised"

- [ ] Release build, `dexopt status=speed` on the TV
- [ ] Cold start to first drawn frame **< 1.0 s** (`adb shell am start -W -n <pkg>/.HomeActivity`, read `TotalTime`), with the app list from the disk cache
- [ ] Idle PSS on Home **< 45 MB**; after `TRIM_MEMORY_UI_HIDDEN` **< 25 MB**
- [ ] `gfxinfo`: janky < 5%, p90 ≤ 16 ms while holding the D-pad right across a 30-card row
- [ ] No `requestLayout` on focus (check with a layout-inspector or `View.onLayout` counter in debug)
- [ ] No reflection-based JSON, no WebView, no Compose, no Leanback
- [ ] Banners decoded at 320×180, backdrops RGB_565 at 1280×720, bitmap cache bounded in bytes
- [ ] HOME while on Home scrolls to the top; Back on Home does nothing
- [ ] Survives being killed while Nitflix plays (comes back instantly from the cache)
- [ ] Vendor launcher restore command tested
