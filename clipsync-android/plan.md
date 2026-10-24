# ClipSync Android: Plan

The phone side of ClipSync (see `../clipsync-desktop/plan.md`).
It has no launcher screen and no notifications. You use it through a **Control Center tile** (the quick settings panel with Wi-Fi and Bluetooth) and the **Share** menu.

Target device: **POCO X7, Android 16, HyperOS**.

## Goals

- **Tiny:** APK of about 150 KB or less, with no AndroidX, Jetpack or other libraries.
- **Invisible:**
  - Nothing appears in the notification area. The only UI is the tile, the Share target, brief toasts and a settings screen opened by long-pressing the tile.
  - The app never asks for notification permission. On Android 13+ that keeps the required background-service notification out of the notification shade.
- **Low battery use:** one idle connection, woken only by incoming data or the PC's heartbeat every 4 minutes. A wake lock is held only during transfers.

## Tech stack

| Part | Choice |
|---|---|
| Language | Kotlin, Android framework APIs only (no AndroidX) |
| SDK levels | `minSdk 33` (Android 13), `targetSdk 36` (Android 16) |
| Build | Gradle (Kotlin DSL) + Android Gradle Plugin, R8 minify + resource shrinking on release |
| Networking | `java.net.Socket` / `DatagramSocket` (Wi-Fi), `BluetoothSocket` RFCOMM (fallback) |
| Crypto | `javax.crypto`: PBKDF2WithHmacSHA256, HmacSHA256, AES/GCM/NoPadding; Android Keystore to protect the stored key |
| Images | `ImageDecoder` / `Bitmap.compress` (re-encode HEIC/WebP to PNG, or to JPEG for Bluetooth) |
| Files | Storage Access Framework input from Share, `MediaStore.Downloads` output (no storage permission needed) |

**Build requirements:** JDK 17+ and the Android SDK. The machine currently has Java 8 and no SDK, so **install Android Studio**, which includes both. `adb` is already installed for sideloading.

## User experience

### Control Center tile: "ClipSync"
- **Tap:** sends the phone's current clipboard (text or image) to the PC. A short toast confirms it: `Sent to PC` / `PC not connected`.
- **Tile look:**
  - **Active** (lit): connected. The subtitle says `Wi-Fi` or `Bluetooth`.
  - **Inactive** (dim): not connected. The subtitle says `Not connected`.
  - **Inactive with subtitle `Paused`:** sync is paused from settings.
- **Long-press:** opens the settings screen.
- The tile works from the lock screen by asking you to unlock first, because Android hides the clipboard while the phone is locked.

### Share menu: "Send to PC"
- **One image** → sent as an `IMAGE` and lands on the PC clipboard.
- **Anything else, or multiple items** → sent as files and saved to `Downloads\ClipSync` on the PC.
- Toasts: `Sending 3 files…` → `Sent 3 files`. Over Bluetooth, files are refused with the toast `Files need Wi-Fi`.

### Settings screen (long-press tile; also opens once on first launch)
- Password (must match the PC)
- PC address: `Auto (found: DESKTOP-ABC 192.168.1.23)`, or type an IP manually
- Bluetooth fallback: on/off + pick the PC from the list of paired devices
- Save received images to `Pictures/ClipSync`: on/off (default **on**, because some apps can't paste images from the clipboard)
- Pause sync: on/off
- **Battery setup** buttons for HyperOS: "Battery: No restrictions" and "Autostart" (see below)

### What happens when content arrives (no notifications)

| From PC | Result |
|---|---|
| Text | Put on the clipboard, silently. |
| Image | Saved to the app cache (only the latest is kept) and put on the clipboard through the app's own content provider, so apps like WhatsApp, Gmail and Gboard can paste it. It's also saved to `Pictures/ClipSync` if that setting is on. |
| Files | Streamed straight into `Download/ClipSync/`. Toast: `Received 2 files → Downloads/ClipSync`. |

## What syncs, and when

| Content | Phone → PC | PC → Phone | Over Bluetooth |
|---|---|---|---|
| Text | tap the tile | automatic | ✅ |
| Image | tap the tile (copied image) or Share | automatic | ✅ up to 2 MB, re-encoded as JPEG if larger |
| Files | **Share → Send to PC only** | **only when sent from the PC's menu** | ❌ Wi-Fi only |

**Why phone → PC needs a tap:** since Android 10, only the on-screen app or the active keyboard can read the clipboard.

**How the tap works:** the tile opens an invisible, instantly closing activity (`ClipReadActivity`). It reads the clipboard once it has window focus, hands the item to the service and finishes. Nothing visible appears.

## Architecture

### Components

| Component | Role |
|---|---|
| `SyncService` | Foreground service, `foregroundServiceType="connectedDevice"`. It owns the connection, receive loop, sender queue, reconnect logic and network callback. Its notification channel is `IMPORTANCE_MIN` and notification permission is never requested, so nothing shows in the shade. |
| `ClipTileService` | `TileService` for the Control Center tile. Tap → `startActivityAndCollapse(PendingIntent)` to `ClipReadActivity`. It refreshes the tile when the connection state changes (`TileService.requestListeningState`). |
| `ClipReadActivity` | Transparent, no animation, `noHistory`, `excludeFromRecents`. Reads the clipboard and finishes. |
| `ShareActivity` | Transparent target for `ACTION_SEND` / `ACTION_SEND_MULTIPLE`. Hands the URIs to the service and finishes. |
| `SettingsActivity` | Plain framework views, handles Android 16 edge-to-edge insets. Registered for `ACTION_QS_TILE_PREFERENCES`, so long-pressing the tile opens it. |
| `ClipProvider` | Minimal read-only `ContentProvider` (`exported=false`, `grantUriPermissions=true`) that serves the latest received image for clipboard pasting. |
| `BootReceiver` | `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` → start `SyncService`. A `connectedDevice` foreground service is allowed to start from boot. |

### Connection state machine (inside `SyncService`)

```
PAUSED ◄──── setting ────► DISCONNECTED
                               │ Wi-Fi available
                               ▼
                        CONNECTING_WIFI ── try cached IP → discovery → manual IP
                         │ ok        │ fail (and BT fallback on)
                         ▼           ▼
                  CONNECTED_WIFI   CONNECTING_BT ── RFCOMM to paired PC
                                     │ ok
                                     ▼
                               CONNECTED_BT ── retries Wi-Fi every 60 s or on network change;
                                               when Wi-Fi connects, BT is closed
```

- Reconnect backoff: 5 s, then 10 s, 30 s and 60 s (the maximum). It resets on network changes and on tile taps.
- `ConnectivityManager.NetworkCallback` watches Wi-Fi availability, so the app never polls.
- The PC drives the heartbeat (see the protocol). The phone needs no alarms or timers, and its socket read just blocks.
- Items sent while disconnected are kept for up to 10 seconds while a connection is attempted, then dropped with a `PC not connected` toast.

### Threads
- **Receive thread:** blocking reads on the active socket. Files are written straight to MediaStore, and text/image are set on the clipboard from the main thread.
- **Sender thread:** a single writer working from a queue. Text and images go before file chunks, the same as on the PC.
- A partial wake lock is held only while a frame or file transfer is in progress, with a timeout.

### Source layout

```
clipsync-android/
  plan.md
  settings.gradle.kts
  build.gradle.kts
  gradle/ + gradlew(.bat)
  app/
    build.gradle.kts           minify + shrinkResources, no dependencies
    proguard-rules.pro
    src/main/
      AndroidManifest.xml
      res/                     tile icon (vector), settings layout, strings, transparent theme
      java/dev/clipsync/
        Proto.kt               keys, handshake, frame encrypt/decrypt (must match desktop proto.c)
        Discovery.kt           UDP discovery client
        Link.kt                common stream wrapper for TCP Socket and BluetoothSocket
        SyncService.kt         state machine, receive/sender threads, heartbeat replies
        ClipTileService.kt
        ClipReadActivity.kt
        ShareActivity.kt
        SettingsActivity.kt
        ClipProvider.kt
        BootReceiver.kt
        Prefs.kt               SharedPreferences + Keystore-wrapped master key
```

### Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | TCP/UDP, Wi-Fi monitoring |
| `CHANGE_NETWORK_STATE` | required for a `connectedDevice` foreground service (automatically granted) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | the background service |
| `RECEIVE_BOOT_COMPLETED` | start after reboot |
| `WAKE_LOCK` | short wake locks during transfers |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | the "Battery: No restrictions" button |
| `BLUETOOTH_CONNECT` (runtime) | asked for **only** when you turn on the Bluetooth fallback |
| ~~`POST_NOTIFICATIONS`~~ | **deliberately not requested**, which keeps the notification shade clean |

`dataSync` is **not** used as the service type, because Android 15+ limits it to 6 hours a day.

## HyperOS / POCO notes

HyperOS kills background apps aggressively. For the service to survive, you need to do this once:
1. Settings → Apps → ClipSync → **Autostart: on**
2. Settings → Apps → ClipSync → Battery saver → **No restrictions**
3. *(Optional)* Lock the app in Recents.

The settings screen has buttons that open these pages. If the Autostart page can't be opened directly, the button falls back to the app info page.

Other things to expect:
- If HyperOS asks whether ClipSync may access the clipboard, choose **Always allow**.
- Android shows its own small "pasted from clipboard" or "copied" pop-up when ClipSync reads or writes the clipboard. That's a system message ClipSync can't turn off.
- **Adding the tile:** open Control Center → edit (pencil icon) → find **ClipSync** → drag it into the tile area.

## Network notes

- **Cloudflare WARP** (on the phone or PC): local network addresses are excluded from the tunnel by default, so the connection stays on your Wi-Fi. If the 1.1.1.1 app routes local traffic anyway, exclude ClipSync in its split-tunnel settings, or use manual IP.
- **Local network permission:** Android is introducing a local-network access permission that apps will have to request, opt-in on Android 16 and expected to become mandatory later. If it applies to the installed Android version, the settings screen will request it.
- **Bluetooth:** pair the phone with the PC once in Bluetooth settings, then pick the PC under "Bluetooth fallback". Keeping a Bluetooth link open can make Bluetooth earbuds stutter on some phones. If that happens, turn the fallback off.

## Wire protocol (v1)

**This section must stay identical to `clipsync-desktop/plan.md`.**

**Ports:**
- TCP `47817`: connections.
- UDP `47818`: discovery.
- Bluetooth: RFCOMM with service UUID `c1b5d7a2-3f4e-4b8a-9d6c-2e7f1a0b5c93`, service name `ClipSync`.

The PC is the **server** and the phone is the **client**. TCP and RFCOMM carry exactly the same byte stream.

### Keys
```
master  = PBKDF2-HMAC-SHA256(password UTF-8, salt = "ClipSync/v1", iterations = 200000, 32 bytes)
session = HMAC-SHA256(master, "ClipSync session" || client_random || server_random)
```
Each side computes `master` once whenever the password changes and stores it (DPAPI on Windows, Android Keystore-wrapped on the phone).

### Handshake (plaintext)
1. Client → server: `"CSY1"` (4 ASCII bytes) + `client_random` (32 bytes)
2. Server → client: `"CSY1"` + `server_random` (32 bytes)
3. Both sides derive `session` and immediately send an encrypted `AUTH` frame.
   - If the first frame fails to decrypt, the password is wrong: close the connection.
   - The server closes any connection that hasn't authenticated within 10 seconds.

### Frames (encrypted)
```
uint32 BE  length      = length of (ciphertext || tag)
bytes      ciphertext  = AES-256-GCM(session, nonce, plaintext)
bytes[16]  tag
```
- `nonce` (12 bytes, never sent): `[direction][0][0][0][counter: uint64 BE]`.
  - `direction`: `0x00` for client→server, `0x01` for server→client.
  - `counter` starts at 0 for each direction on each connection and increases by 1 per frame. A counter that is out of order makes decryption fail, which closes the connection. This also blocks replays.
- `plaintext` = `type (1 byte)` + `payload`.
- Maximum `length` is 16 MiB + 17. Anything larger closes the connection.

| Type | Name | Payload | Bluetooth |
|---|---|---|---|
| `0x01` | AUTH | device name, UTF-8, ≤ 64 bytes | ✅ |
| `0x02` | PING | none | ✅ |
| `0x03` | PONG | none | ✅ |
| `0x10` | TEXT | UTF-8 text, ≤ 1 MiB | ✅ |
| `0x11` | IMAGE | encoded image bytes (PNG or JPEG; receiver detects format by magic bytes), ≤ 10 MiB on Wi-Fi, ≤ 2 MiB on Bluetooth | ✅ |
| `0x20` | FILE_BEGIN | `uint64 BE size`, `uint16 BE name_len`, file name UTF-8 (base name only) | ❌ |
| `0x21` | FILE_DATA | ≤ 65536 bytes of file content | ❌ |
| `0x22` | FILE_END | none (receiver checks that the byte count equals `size`) | ❌ |
| `0x23` | FILE_CANCEL | none (sender aborted the current file; receiver deletes the partial file) | ❌ |
| `0x24` | FILES_DONE | `uint16 BE count`, sent after the last file of a batch | ❌ |

- Unknown types are ignored, which allows future additions.
- `FILE_*` frames received over Bluetooth are ignored.

### Heartbeat
- The server sends `PING` after 240 seconds without sending anything. The client answers `PONG`.
- Either side closes the link after 600 seconds without receiving any frame. The phone then reconnects.
- The server drives the heartbeat so the phone never needs its own wake-up timer.

### Discovery (UDP, Wi-Fi only)
- Client → broadcast (`255.255.255.255` and the subnet broadcast address) on port `47818`:
  `"CSYD"` + `nonce (16 bytes)` + `HMAC-SHA256(master, "ClipSync discover" || nonce)[0..16]`
- The server checks the HMAC and replies only if it's valid, so other devices on the network can't find the PC. Reply, sent unicast to the sender:
  `"CSYA"` + `nonce (16 bytes)` + `uint16 BE tcp_port` + `HMAC-SHA256(master, "ClipSync answer" || nonce || port)[0..16]` + device name UTF-8
- The phone remembers the last working IP and tries it first. Manual IP entry is always available as a fallback.

## Milestones

These line up with the desktop milestones. Desktop milestone 2 + Android milestone 3 together give the first end-to-end test.

1. **Project skeleton:** Gradle project, manifest, empty service, tile that shows in Control Center, transparent activities, settings screen. The release APK builds and installs with `adb install`.
2. **Protocol core:** `Proto.kt` (keys, handshake, frames), tested against the desktop app.
3. **Text sync:** service + state machine, discovery, tile tap → send text, received text → clipboard, tile state updates, boot start, HyperOS battery buttons.
4. **Images:** read a copied image on tile tap, Share of one image, receive → `ClipProvider` + clipboard (+ `Pictures/ClipSync`), re-encode HEIC/WebP to PNG.
5. **Files:** Share of multiple items/files → `FILE_*` stream; receive → `Download/ClipSync` through MediaStore; toasts.
6. **Bluetooth fallback:** paired-PC picker, RFCOMM connect, Wi-Fi ↔ Bluetooth switching, 2 MB image cap with JPEG re-encode, files refused.
7. **Polish and measurement:** APK size, battery over a full day (Settings → Battery usage), survival after reboot, behaviour in the HyperOS memory cleaner.

## Testing checklist

- The tile appears in Control Center. Tap with text copied → it appears on the PC. Tap with nothing copied → toast `Clipboard empty`.
- Copy on PC → paste on phone within about 1 second, with the screen off for a minute beforehand.
- Screenshot on PC → paste into WhatsApp. Share a gallery photo → `Ctrl+V` in Paint on the PC.
- Share 5 files → they appear in `Downloads\ClipSync` on the PC, and `Ctrl+V` in Explorer pastes them.
- Turn Wi-Fi off with Bluetooth fallback on → text still syncs. Turn Wi-Fi back on → the tile subtitle changes back to `Wi-Fi`.
- Reboot the phone → it reconnects without opening the app.
- Run with WARP on, on both devices.
- No ClipSync entry ever appears in the notification shade.
