# ClipSync Desktop: Plan

A Windows tray app that syncs the clipboard with an Android phone (see `../clipsync-android/plan.md`).
It has no main window, only a tray icon.

## Goals

- **Tiny:** a single `.exe`, about 150 KB or less, with no runtime, no installer and no DLLs beyond what ships with Windows.
- **Idle cost about zero:** clipboard changes arrive as events (no polling), and the only timer is a heartbeat every 4 minutes. Target idle RAM is 3 MB or less (private working set).
- **Local only:** traffic goes over Wi-Fi (TCP) with a Bluetooth fallback. No cloud, no account.
- **Uncluttered:** a tray icon, a right-click menu and a one-time settings dialog. Nothing else.

## Tech stack

| Part | Choice |
|---|---|
| Language | C17 |
| Compiler | MinGW-w64 gcc (MSYS2 UCRT64, already installed), `-Os -s -mwindows`, static libgcc |
| UI | Win32 API: hidden message-only window, `Shell_NotifyIcon`, popup menu, one dialog from `.rc` |
| Clipboard | `AddClipboardFormatListener` (event-driven), `GetClipboardSequenceNumber` |
| Networking | Winsock2: TCP + UDP over Wi-Fi, `AF_BTH` RFCOMM for Bluetooth (same `SOCKET` API) |
| Crypto | BCrypt (CNG): PBKDF2-SHA256, HMAC-SHA256, AES-256-GCM, RNG |
| Secret storage | DPAPI (`CryptProtectData`) encrypts the derived key in the config file |
| Images | WIC (Windows Imaging Component): PNG encode/decode, JPEG re-encode for Bluetooth. Loaded lazily, only when an image is handled |
| Icons | Drawn at runtime with GDI (no `.ico` files), DPI-aware, light/dark taskbar aware |
| Libraries linked | `ws2_32 bcrypt crypt32 windowscodecs ole32 shell32 user32 gdi32 advapi32 uuid`, all part of Windows |

## User experience

**Tray icon** (one of four states, with tooltip text):

| State | Icon | Tooltip |
|---|---|---|
| Connected over Wi-Fi | solid clipboard | `ClipSync: POCO X7 (Wi-Fi)` |
| Connected over Bluetooth | clipboard + small BT mark | `ClipSync: POCO X7 (Bluetooth)` |
| Waiting for phone | outlined/grey clipboard | `ClipSync: waiting for phone` |
| Paused | clipboard + pause bars | `ClipSync: paused` |

While a file transfer runs, the tooltip shows progress: `Sending 2/5 files (43%)`.

**Left-click:** pause or resume sync.

**Right-click menu:**
```
Send copied files to phone
──────────────────────────
Pause sync                ✓
Start with Windows        ✓
Settings…
──────────────────────────
Exit
```

**Settings dialog** opens automatically on first run (no password set) and otherwise only from the menu. It has:
- Password (shared with the phone; 12+ characters recommended)
- Device name (defaults to the computer name)
- TCP port (default `47817`)
- [x] Bluetooth fallback
- Read-only line: `This PC's address: 192.168.1.23`, for entering it manually on the phone

**Notifications:** a tray balloon appears only for things you'd want to know about: files received, a file send that failed or was refused, a wrong password from the phone. Text and image syncs are silent.

**Explorer integration:** a `Phone (ClipSync)` entry in the **Send to** menu (right-click → Show more options → Send to). It runs `clipsync.exe --send <paths…>`, which hands the paths to the running instance through `WM_COPYDATA` and exits.

## What syncs, and when

| Content | PC → Phone | Phone → PC (arrives) | Over Bluetooth |
|---|---|---|---|
| Text | automatic on copy | set on PC clipboard | ✅ |
| Image | automatic on copy, up to 10 MB | set on PC clipboard (DIB + PNG) | ✅ up to 2 MB, re-encoded as JPEG if larger |
| Files | **manual only:** tray menu or Explorer Send to | saved to `Downloads\ClipSync\`, then put on the clipboard so `Ctrl+V` in Explorer pastes them | ❌ Wi-Fi only |

**Never synced:** clipboard content that password managers mark as private, meaning the formats `ExcludeClipboardContentFromMonitorProcessing`, `CanIncludeInClipboardHistory = 0` and `CanUploadToCloudClipboard = 0`.

**Echo suppression:**
- After ClipSync writes to the clipboard, it stores `GetClipboardSequenceNumber()` and ignores the `WM_CLIPBOARDUPDATE` for that number.
- It also keeps a 64-bit hash of the last item sent or received and skips duplicates.

## Architecture

### Threads

1. **UI thread:** message-only window, tray, menu, settings dialog, clipboard read/write, `WM_COPYDATA`. It never blocks on the network.
2. **Network thread:** one `select()` loop over the TCP listener, the Bluetooth listener, the UDP discovery socket and the active connection.
   - Performs handshakes and reads frames.
   - Writes incoming files straight to disk.
   - Hands received text and images to the UI thread with `PostMessage(WM_APP_RECV, …)`.
3. **Sender thread:** the only writer to the active connection. It works from a queue and wakes on an event.
   - Control, text and image items always go before file chunks. Between every 64 KB file chunk it drains any queued text or image first, so a big file transfer never delays clipboard sync.

### Connection rules

- Only one active link at a time. **The most recent authenticated connection wins**, and the old socket is closed.
- In practice this makes Wi-Fi preferred: the phone only tries Bluetooth when Wi-Fi fails, and drops Bluetooth when Wi-Fi comes back.
- If the phone fails the password check 5 times within a minute, new connections are ignored for 60 seconds.

### Source layout

```
clipsync-desktop/
  plan.md
  build.bat            gcc command line (also works from an MSYS2 shell)
  src/
    main.c             WinMain, single-instance mutex, message window, tray, menu, WM_COPYDATA
    config.c/.h        %APPDATA%\ClipSync\clipsync.ini, DPAPI-protected key, autostart (HKCU\…\Run)
    crypto.c/.h        BCrypt wrappers: PBKDF2, HMAC, AES-GCM, random
    proto.c/.h         handshake, frame encrypt/decrypt, read/write over a SOCKET
    link.c/.h          TCP + Bluetooth listeners, active connection, heartbeat, sender queue/thread
    discovery.c/.h     UDP discovery responder
    clip.c/.h          clipboard read/write (text, image, file list), privacy formats, echo suppression
    image.c/.h         WIC: DIB↔PNG, any format→DIB, JPEG re-encode for Bluetooth
    files.c/.h         outgoing file streaming, incoming file writing, Send to shortcut
    icons.c/.h         GDI-drawn tray icons
    settings.c/.h      settings dialog
    resource.rc/.h     dialog template, version info, manifest (DPI-aware, common controls v6)
  tools/
    testclient.c       phone simulator built on proto.c, for testing without the phone
```

## Wire protocol (v1)

**This section must stay identical to `clipsync-android/plan.md`.**

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

## Network notes

- **Cloudflare WARP:** WARP excludes private address ranges from the tunnel by default, so phone ↔ PC traffic stays on the local network. Discovery replies go unicast to the phone's local IP, so they aren't affected either. If WARP's split-tunnel settings were changed to include local ranges, use manual IP or the Bluetooth fallback.
- **Windows Firewall:** the Wi-Fi network must be set to **Private**. On first run, Windows asks to allow `clipsync.exe`. Allow it on private networks only.
- **Bluetooth:** the PC registers its RFCOMM service with `WSASetService`, and the phone must already be paired with the PC in Windows Bluetooth settings. Bluetooth is turned off entirely when "Bluetooth fallback" is unchecked.

## Security notes

- Everything after the handshake is encrypted and authenticated. A wrong password can't read or inject anything.
- Someone recording your Wi-Fi traffic could try to guess the password offline (PBKDF2 slows this down but can't stop a weak password). **Use a password of 12 or more random characters.** The settings dialog warns when it's shorter.
- The password itself is never stored. Only the derived key is saved, encrypted with DPAPI so that only your Windows user can read it.

## Milestones

Each milestone ends with a working, testable build.

1. **Protocol core:** `crypto.c`, `proto.c` and `tools/testclient.c`. The test client completes a handshake with the server and exchanges `TEXT` frames. A wrong password is rejected.
2. **Tray + text sync:** tray icon, menu, settings dialog, config/DPAPI, autostart, discovery responder, automatic text sync with echo suppression, privacy formats respected. *(This is the first end-to-end test with the phone, together with Android milestone 3.)*
3. **Images:** WIC encode/decode, PNG format support, DIB fallback, 10 MB cap.
4. **Files:** tray "Send copied files", Explorer Send to, chunked streaming with text/image priority, `.part` files renamed on completion, name collision handling (`name (1).ext`), `CF_HDROP` on the clipboard after receiving, balloon notification.
5. **Bluetooth fallback:** RFCOMM listener + SDP registration, text and images only, JPEG re-encode for images over 2 MB.
6. **Polish and measurement:** check that exe size, idle RAM and idle wake-ups meet the targets; DPI/theme icon checks; failure messages.

## Testing

- `tools/testclient.c` simulates the phone for protocol tests: handshake, wrong password, oversized frames, out-of-order counters, file batches, cancel mid-file.
- Manual checklist:
  - Copy text, a screenshot (Win+Shift+S), an image from a browser and a password from a password manager. The last one must **not** sync.
  - Send 1 file, 5 files and one 2 GB file. While the large file is sending, copy text and confirm it still syncs.
  - Pull the Wi-Fi with Bluetooth on: sync continues on Bluetooth. Restore Wi-Fi: the link switches back.
  - Run with WARP on.
- Resource check with Task Manager or Process Explorer: private bytes when idle, and CPU time after 1 hour idle.
