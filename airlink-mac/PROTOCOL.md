# realme Buds Air 6 Pro control protocol

This is the protocol AirLink uses: the OPO headset protocol shared by realme, OPPO and OnePlus buds (the one HeyMelody and realme Link speak). The facts come from reading open-source projects, then checking them on real buds with `airlink-probe`. AirLink's code is an independent implementation; no code was copied.

**Status: confirmed on hardware.** Battery, wear status, reading and setting all three noise modes, refusals, and pushes for long-press mode changes all work on a realme Buds Air6 Pro: product id `063012`, firmware `1.1.0.75`, macOS 27.0.1, probed 2026-10-23. The captured frames are unit-test fixtures in `Tests/BudsProtocolTests/CapturedFramesTests.swift`. Where the probe and the references disagreed, the probe wins.

## Confidence labels

| Label | Meaning |
|---|---|
| **Probe** | Seen on the owner's buds with `airlink-probe` (2026-10-23, firmware 1.1.0.75). |
| **GB-A6P** | Gadgetbridge has Air 6 Pro–specific code (PR #6305, hardware-tested by its contributor). Not contradicted by the probe. |
| **Family** | Documented for other realme/OPPO models only. |

## Sources

| Id | Project | Licence | What it gave us |
|---|---|---|---|
| GB | [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge), `devices/realme/RealmeBudsAir6ProCoordinator`, `service/devices/oppo/*` | AGPL-3.0 (**read only**) | Air 6 Pro ANC values and levels, wear-gating rule, battery parsing |
| OB | [himanshudev28/OneBuds](https://github.com/himanshudev28/OneBuds), `Documentation/ProtocolSources.md`, `Sources/OneBudsProtocol`, `DeviceRegistry.swift` | see repo | Frame layout, LEB128 length, resync, realme noise table, product ids, IOBluetooth lessons |
| RM | [kalki-kgp/realme-buds-mac](https://github.com/kalki-kgp/realme-buds-mac), `BudsClient.swift`, `cli/buds.swift` | see repo | realme Air 7 over IOBluetooth: `oppointeraction` service, ANC read layout, SDP quirk |

## 1. Transport and SDP

Classic Bluetooth RFCOMM. Look the channel up from SDP every time; never hard-code it.

SDP records on the Air 6 Pro (**Probe**):

| Record | RFCOMM ch | Service class UUIDs | Use |
|---|---|---|---|
| `oppointeraction` | 15 | `0000079A-D102-11E1-9B23-00025B00A5A5` (vendor) **and** `1101` (SPP) | **Control channel.** Every command below was run here. |
| `TOTA` | 12 | `1101` | Firmware update. **Never opened**: AirLink refuses any record named `*OTA*`. |
| `RFCOMM COM` | 17 | `1101` | Unknown. Not used. |
| three unnamed records | 1 | — | Audio profiles. Not used. |

Gadgetbridge asks Android for the SPP UUID (`1101`) on this model, which most likely lands on `oppointeraction` too, since that record also lists `1101`. But SPP alone is ambiguous here, so the profile matches the vendor UUID first (unique to channel 15) and only falls back to SPP. The record name `oppointeraction` is also accepted as a match.

AirLink does not use the BLE GATT bearer and never scans.

### IOBluetooth quirks

- **macOS 27: IOBluetooth is a shim over CoreBluetooth** (the log shows "Loading Shim Coordinator"). If `openRFCOMMChannelAsync` is called within milliseconds of the process's first IOBluetooth call, it is silently dropped. The process log shows `API MISUSE: … can only accept this command while in the powered on state`, bluetoothd never sees the request, and the open never completes. `performSDPQuery` returns at once from cache. **Fix:** once per process, wait about 1 s after the first IOBluetooth use before opening, and retry a stalled open once. With that, the channel opens in about 80 ms. (**Probe**, found in the unified log.)
- **Don't open the channel the instant the buds connect.** macOS is still bringing up the audio links then, including hands-free, which has its own RFCOMM channel (A2DP defers endpoint discovery by 2 s). An open sent 12 ms after the connect notification stalled for good. Worse, closing that pending open made bluetoothd answer **"Already connected to device"** (result `0x76e`) to every later open from the same process, until the process exited. **Fix:** wait about 4 s after a connect notification, make a single open with a 15 s timeout, never close it early, and if it still stalls, recover by starting a new process. (Seen in the AirLink and bluetoothd logs on 2026-10-23.)
- Run `performSDPQuery` in the same process before opening, and only on a device that is already connected, so it never pages the buds. Never use the `uuids:` variant. (OB, RM)
- The open-complete callback is unreliable. Treat the channel as open on a successful callback **or** when `isOpen()` is true. (OB)
- `rfcommChannelData`'s buffer is only valid during the callback. Copy it.
- One client per channel. AirLink and the probe share a `flock` on `$TMPDIR/io.github.nk1044.airlink.rfcomm.lock`.
- `register(forConnectNotifications:)` misses devices that were connected before launch, so also check `pairedDevices()` once. Disconnect notifications fire once; re-register on every connect. (OB)

**Debugging tip:** `/usr/bin/log show --predicate 'process == "airlink-probe"'` shows IOBluetooth errors on the client side, and `process == "bluetoothd"` shows the daemon side.

## 2. Frame format

```
AA | len (LEB128) | ctl | 00 | cmd lo | cmd hi | seq | plen lo | plen hi | payload…
```

| Field | Meaning | Confidence |
|---|---|---|
| `AA` | Start of frame | **Probe** |
| `len` | Bytes after this field: `7 + payload`. Unsigned LEB128; every frame seen was one byte, the longest being the firmware reply (`64`). | **Probe** (LEB128 itself: Family) |
| `ctl` | `00` both ways. Other realme models send `04` or `08` (GB); receivers ignore it. | **Probe** |
| reserved | `00` | **Probe** |
| `cmd` | u16 little-endian. A reply sets bit 15 (`0x0106` → `0x8106`). | **Probe** |
| `seq` | Replies echo the request's value. Pushes carry the buds' own incrementing counter (`14`, `15`, … `24`), **not** `FF`. | **Probe** |
| `plen` | Payload length, u16 LE. Normally `len − 7`. **Quirk:** the hello reply has `len` = 14 but `plen` = 5. The decoder trusts `len` for framing, uses the first `plen` bytes as the payload, and rejects only `plen > len − 7`. | **Probe** |

- No checksum, CRC or trailer. (**Probe**)
- A reply's payload starts with a status byte: `00` means OK. (**Probe**)
- The buds send two pushes immediately when a channel opens: wear status, then battery. (**Probe**)

## 3. Session start

| Step | Bytes | Result |
|---|---|---|
| Hello `0x0100` | `AA 07 00 00 00 01 01 00 00` | Answered in about 35 ms: `AA 0E 00 00 00 81 01 05 00 00 BF 2F 5A 64 45 00`, i.e. status `00` plus capability bitmap `BF 2F 5A 64`, with 2 bytes beyond `plen` (see §2). Skipping the hello changes nothing that AirLink uses. AirLink sends it anyway, because every noise-set test ran with it. (**Probe**) |
| Subscribe `0x0205` | `03 01 02 03` (count-first) | **Never answered**, with or without the hello. It isn't needed: battery, wear and noise pushes all arrive without it, including a noise push that came before the subscribe was sent. **The Air 6 Pro profile does not subscribe.** (GB's `09 01 02 03 05` form was not tried.) (**Probe**) |
| Time request `0x0500` from the buds | — | Never seen. AirLink still answers one if it arrives. |

With the hello reply decoded correctly, the connect sequence is: open the channel (about 80 ms) → hello (about 35 ms) → battery and noise read (about 25 ms each).

## 4. Commands

| Feature | Request | Reply / push | Confidence |
|---|---|---|---|
| Product id | `0x0103` | `00 12 30 06` → `063012` | **Probe** |
| Firmware | `0x0105` | `00 09` + ASCII `component,kind,value` triples. Buds 1 and 2: `111`, `1.1.0.75`, `1.1.0.75`. Case (3): `111`, `026`, `026`. | **Probe** |
| Battery | `0x0106` | `00 <n> [<idx> <raw>]…` | **Probe** |
| Battery push | — | `0x0204` payload `01 <n> [<idx> <raw>]…` | **Probe** |
| Wear status | `0x0109` | `00 <n> [<idx> <state>]…` | **Probe** |
| Wear push | — | `0x0204` payload `02 <n> [<idx> <state>]…` (often sent twice in a row) | **Probe** |
| Noise read | `0x010C 01 01` | `00 01 01 <value>` | **Probe** |
| Noise push | — | `0x0204` payload `03 01 01 <value>` | **Probe** |
| Noise set | `0x0404 01 01 <value>` | `0x8404 <status>`, then a noise push. The reply comes in about 25 ms, and the push can arrive just before or just after it. | **Probe** |

### Battery

- `idx`: `01` left, `02` right, `03` case. Level = `raw & 0x7F`; charging = `raw & 0x80`. (**Probe** for the level; the charging bit was not exercised.)
- **Case**: `00` (not reported) while the lid is closed; the real level (e.g. `28` = 40 %) while it is open. (**Probe**)
- **Each push is a full snapshot of what the buds can report.** With the right bud in the closed case, the push was `01 02 01 64 03 00`: left and case only, with right **absent**. So a component missing from a push means "not reported now": show "—", never the stale value. (**Probe**)
- Level `00` means "not reported" → "—".

### Wear states (`idx` 1 = left, 2 = right)

`00` in case, `01` out of ear, `03` in ear (**Probe**). Wear messages also carry an `idx 03` (case) entry with value `04` whether the lid is open or closed; AirLink ignores it.

### Noise-control values

The set value equals the read value. (**Probe** for 01/02/08)

| Mode | Value | Confidence |
|---|---|---|
| Off | `01` | **Probe** |
| Transparency | `02` | **Probe** |
| ANC (high / max) | `08` | **Probe**: what long-press and "ANC" both produce on this unit |
| ANC low / normal / dynamic | `04` / `10` / `20` | GB-A6P. Read back as "ANC". AirLink never sends them. |

A long-press on a bud cycles Transparency → ANC → Off, and each step produces a noise push. (**Probe**)

### Refusals

The buds enforce Gadgetbridge's rule themselves and answer `0x8404` with **status `0E`**. (**Probe**)

| Buds in ear | ANC | Transparency | Off |
|---|---|---|---|
| both | ✅ | ✅ | ✅ |
| one | ❌ `0E` (Probe) | ✅ (GB) | ✅ (GB) |
| none | ❌ (GB) | ❌ `0E` (Probe) | ❌ (GB) |

On a refusal the mode doesn't change and no push follows. AirLink checks the rule before sending, so it can show "Put a bud in your ear" without a round trip, and still handles `0E` and timeouts.

## 5. Push events (`0x0204`)

| Id | Meaning | Confidence |
|---|---|---|
| `01` | Battery (full snapshot) | **Probe** |
| `02` | Wear status | **Probe** |
| `03` | Noise mode | **Probe** |
| `05`, `06` | Game mode, multipoint list | GB |
