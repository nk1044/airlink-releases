# CMF Buds 2a control protocol

This is the protocol CMF Link uses. It is the control protocol that Nothing and CMF buds share, the one the Nothing X app speaks. The facts come from reading open-source projects, then checking them on real buds with `cmflink-probe`. CMF Link's code is an independent implementation; no code was copied.

**Status: confirmed on hardware.** Everything in §4 marked **Probe** was confirmed on a CMF Buds 2a on 2026-10-24:
- **Reads:** firmware, battery, noise mode, EQ preset, Ultra Bass, low latency, gestures, custom EQ, dual connection.
- **Writes:** all three noise modes, EQ preset, Ultra Bass, low latency.

The buds ran firmware `1.0.1.60`, connected to a Windows 11 PC. The captured frames are unit-test fixtures in `tests/CmfLink.Tests/CapturedFramesTests.cs`. Where the probe and the references disagreed, the probe wins.

## Confidence labels

| Label | Meaning |
|---|---|
| **Probe** | Seen on the owner's buds with `cmflink-probe` (2026-10-24, firmware 1.0.1.60). |
| **GB** | Gadgetbridge's `CmfBuds2aCoordinator` / `Ear1Support`. Not contradicted by the probe. |
| **EW** | ear-web's code or its `B185` config entry. Not tested here. |

## Sources

| Id | Project | Licence | What it gave us |
|---|---|---|---|
| GB | [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge), `devices/nothing/CmfBuds2aCoordinator`, `service/devices/nothing/Ear1Support` | AGPL-3.0 (**read only**) | Frame layout, CRC routine, command ids, noise / EQ / tap codes, the 2a's feature set |
| EW | [ear-web](https://github.com/radiance-project/ear-web), `res/js/bluetooth_socket.js`, `ear_config_file.json` | AGPL-3.0 (**read only**) | Model id `B185`, listening-mode commands, custom EQ, dual connection, per-gesture action lists |
| EA | [EarA-linux issue #4](https://github.com/oneydef/EarA-linux/issues/4) | — | Look the RFCOMM channel up from SDP; CMF Buds 2 uses 16, not 15 |
| CC | [cmf-control](https://github.com/JScoder4005/cmf-control) | MIT | Confirmation of the frame layout on CMF Buds Pro 2 |

## 1. Transport and SDP

Classic Bluetooth RFCOMM to the service `aeac4a03-dff5-498f-843a-34487cf133eb`. Windows resolves the channel from the SDP record, so CMF Link never hard-codes one. (**Probe**)

RFCOMM services on the 2a (**Probe**, from `cmflink-probe list`):

| Service class | Name | Use |
|---|---|---|
| `0000111e-…` | Handsfree | Audio. Not used. |
| `00001101-…` | SPP | Unknown. Not used. |
| `aeac4a03-dff5-498f-843a-34487cf133eb` | SPP | **Control channel.** Everything below ran here. |
| `df21fe2c-2515-4fdb-8886-f12c4d67927c` | SPP | Google Fast Pair. Not used. |

CMF Link never opens a firmware-update channel and never sends an OTA command.

### Windows notes

- Opening the channel takes about 0.4–1.5 s when the SDP record is cached, about 2 s otherwise. (**Probe**)
- `BluetoothDevice.ConnectionStatus` can read *Disconnected* for a few seconds after `FromIdAsync` even though the buds are playing audio. `ConnectionStatusChanged` then fires *Connected*, and CMF Link opens the channel at that point. (**Probe**, seen in the app log)
- Only one client can hold the channel. The app and the probe share an exclusively opened lock file, `%TEMP%\CmfLink.control-channel.lock`.

### One controller at a time, across devices

Dual connection on the 2a covers **audio** only. The control channel accepts one client across all connected devices. (**Probe**, 2026-10-24)
- With Nothing X open on the owner's phone, Windows' `ConnectAsync` fails after about 2 s with `0x80072750` (WSAEHOSTDOWN, "the destination host was down").
- **Nothing X keeps the channel while it's in the background**, and releases it when it's swiped away from recent apps. Seconds later the PC opened the channel straight away.
- The reverse also holds: while CMF Link held the channel, Nothing X couldn't connect.

So CMF Link opens the channel only while it needs it:
- while the panel is open
- for an action from the tray menu or hotkey
- for the 5-minute battery read

It releases the channel after 15 s idle. When the buds refuse, it keeps the last readings and says the phone has the controls. It then retries every 5 s while the panel is open, and every 60 s otherwise.

## 2. Frame format

```
55 | ctl lo | ctl hi | cmd lo | cmd hi | len lo | len hi | seq | payload… | crc lo | crc hi
```

| Field | Meaning | Confidence |
|---|---|---|
| `55` | Start of frame | **Probe** |
| `ctl` | u16 LE. `0x0160` in both directions: device type 1 (TWS) in bits 8–11, `0x40` sequence counter in use, `0x20` CRC present. The low 5 bits of a reply are its status; `0` means OK. | **Probe** (status bits: GB) |
| `cmd` | u16 LE. Requests set bit 15: `0xC0xx` reads, `0xF0xx` writes. **A reply clears bit 15** (`0xC007` → `0x4007`). Pushes are `0xE0xx`. | **Probe** |
| `len` | Payload length, u16 LE | **Probe** |
| `seq` | The app counts 1…250. Replies echo it. | **Probe** |
| `crc` | CRC-16/MODBUS (reflected polynomial `0xA001`, initial value `0xFFFF`), little-endian | **Probe** |

**CRC quirk (new; not documented by any of the sources):** requests are checksummed over **header and payload**, as the official app does, and the buds accept that. The buds' own replies are checksummed over **the payload only**, so an empty ack ends in `FF FF`. Gadgetbridge and ear-web never check incoming CRCs, which is presumably why nobody had noticed. CMF Link's decoder accepts either form and drops frames that match neither. (**Probe**, all captured replies)

## 3. Session start

The buds send nothing when the channel opens. CMF Link sends:

1. **Reads:** firmware, battery, noise, EQ, Ultra Bass, low latency, gestures, custom EQ and dual connection, each answered in about 30–180 ms.
2. **The time:** `0xF00A`, Unix seconds as 4 big-endian bytes. The official app sends it, and it's acked with `0x700A`.

The full sequence takes about 0.6 s. (**Probe**)

**No pushes to the PC.** In three 60-second monitor runs, with and without that init, the owner put a bud in the case, closed and opened the lid, and used gestures. The buds sent nothing. Gadgetbridge and ear-web handle battery pushes (`0xE001`) and noise pushes (`0xE003` / `0x6003`) on other models, and CMF Link handles them too in case a firmware sends them. Possibly the 2a pushes only to the phone running Nothing X; this unit was also connected to one over dual connection. (**Probe**)

So CMF Link re-reads everything when the panel opens and reads the battery every 5 minutes while connected: one 10-byte request.

## 4. Commands

| Feature | Request | Reply payload | Confidence |
|---|---|---|---|
| Firmware | `0xC042` | ASCII, e.g. `1.0.1.60` | **Probe** |
| Battery | `0xC007` | `n, (id, raw)×n` | **Probe** |
| Noise mode read | `0xC01E` | `(kind, value, 00)` triples; see below | **Probe** |
| Noise mode set | `0xF00F 01 <value> 00` | empty ack after about 0.5 s | **Probe** |
| EQ preset read | `0xC050` | `<preset>` | **Probe** |
| EQ preset set | `0xF01D <preset> 00` | empty ack | **Probe** |
| Ultra Bass read | `0xC04E` | `<on> <level×2>` | **Probe** |
| Ultra Bass set | `0xF051 <on> <level×2>` | empty ack | **Probe** (ear-web skips this write on the 2a; it works) |
| Low latency read | `0xC041` | `01` on, `02` off | **Probe** |
| Low latency set | `0xF040 <01 on / 02 off>` | empty ack | **Probe** |
| Gestures read | `0xC018` | `n, (bud, 01, type, action)×n` | **Probe** |
| Gesture set | `0xF003 01 <bud> 01 <type> <action>` | — | GB, EW |
| Custom EQ read | `0xC044` | `n, total gain f32, (filter, gain f32, freq f32, Q f32)×n`, floats LE | **Probe** |
| Custom EQ set | `0xF041`, same layout plus 3 zero bytes per band | — | EW |
| Find my buds | `0xF002 <bud> <01 ring / 00 stop>` | — | EW |
| Dual connection read | `0xC027` | `01` on | **Probe** |
| Dual device list | `0xC028 <start index>` | `?, ?, n, (flags, mac×6, name length, name)×n`. Flags: high nibble = this device, low nibble = connected. | **Probe** |
| Dual connect / disconnect | `0xF01B <01 / 00> <mac×6>` | — | EW |
| Set time | `0xF00A <u32 BE>` | empty ack | **Probe** |

### Battery

- `id`: `02` left, `03` right, `04` case (`06` "stereo" on single-unit models). Level = `raw & 0x7F`, charging = `raw & 0x80`. (**Probe** for the levels; the charging bit comes from GB and EW.)
- **The case is reported only while its lid is open with a bud inside.** A timeline from `cmflink-probe watch`, reading every 2 s (**Probe**):

  | State | Reply payload |
  |---|---|
  | Both buds in ears | `02 02 50 03 4B`: left 80, right 75 |
  | Left bud in the case, lid open | `03 02 50 03 4B 04 64`: left, right **and case 100** |
  | Lid closed | `01 03 4B`: right only. The bud in the closed case and the case go silent. |
  | Lid open again | case 100 again |
  | Bud back in the ear | left and right |

- The bud sitting in the open case still reported `50` (80 %) with the charging bit clear. The case was at 100 %, so whether the bit is ever set on the 2a is unconfirmed. (**Probe**)
- CMF Link reads the battery every 3 s while its panel is open, so opening the lid then shows the case within 3 s. It keeps the case's last level and when it was reported, also across restarts, and shows it faded with "at 2:25 PM". A bud that isn't reported shows "—". (**Probe** for the timeline above)

### Noise control

The 2a's read reply holds **two** triples, for example `02 01 00 | 01 07 00`. The mode is in the triple whose first byte is `01`, here `07`. ear-web reads byte 1 of the payload, which is always `01` on this model. CMF Link scans the triples. (**Probe**)

| Mode | Value | Confidence |
|---|---|---|
| Noise cancellation | `01` | **Probe** |
| Off | `05` | **Probe** |
| Transparency | `07` | **Probe** |
| ANC mid / low / adaptive | `02` / `03` / `04` | GB. Other models only. The 2a has one ANC strength (EW `ancLevel` `0x31`: transparency, no strength control). |

The 2a has no wear detection (GB, EW `earDetection: 0`), so CMF Link can't check in advance whether a mode will be accepted. It sends the mode, reads it back after 250 ms, and says so if the buds didn't switch.

### EQ presets

`01` Rock, `02` Electronic, `03` Pop, `04` Enhance vocals, `05` Classical, `06` Custom, `07` Dirac. (**Probe** for `03` and `07`; the rest are GB and EW.)

### Custom EQ

The buds report three bands: low shelf 140 Hz Q 0.8, peak 980 Hz Q 0.66, and high shelf **3500 Hz** Q 1.0. The official app's config lists 6900 Hz for the high shelf, so CMF Link writes back the frequencies and Qs the buds report and changes only the gains. (**Probe**)

### Gestures

Types: `02` double tap, `03` triple tap, `07` tap & hold, `09` double tap & hold. The actions each one allows (EW, B185 config):

| Gesture | Actions |
|---|---|
| Double tap | play/pause `02`, previous `08`, next `09`, voice assistant `0B`, none `01` |
| Triple tap | previous, next, voice assistant, none |
| Tap & hold | noise control: all `0A`, ANC↔transparency `16`, ANC↔off `14`, transparency↔off `15`; voice assistant; none |
| Double tap & hold | volume up `12`, volume down `13`, voice assistant, none |
