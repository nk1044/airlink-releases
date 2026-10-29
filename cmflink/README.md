# CMF Link

**Your CMF Buds 2a, right in the Windows tray.** It shows the battery for each bud and the case, and gives you noise control, EQ, Ultra Bass, gestures and more, all from the notification area.

CMF Link is a small tray app for the **CMF Buds 2a**, a Windows counterpart of [AirLink](../airlink-mac) for macOS. It stays out of the way: the icon only appears while your buds are connected to the PC, and it disappears when they leave.

## Features

- **Battery at a glance.** Left, right and case, as rings that are green, orange at 20 % or below, and red at 10 % or below, with a bolt while charging. CMF Link never guesses a value: a bud the buds aren't reporting shows "—". The buds report the case only while its lid is open with a bud inside. So the panel reads every 3 s while it's open: open the lid and the case appears. After that it keeps the last level, faded, with the time it was read, also across restarts.
- **Noise control in one click.** Noise cancellation, Transparency or Off. The selection slides over and settles once the buds confirm the change. If they don't switch, it springs back and tells you why.
- **Equalizer.** Dirac, Pop, Rock, Classical, Electronic, Vocals, and a custom 3-band EQ (bass / mid / treble, ±6 dB).
- **Ultra Bass** with its 5 levels, and **low-latency mode** for games and video.
- **Gestures.** Remap double tap, triple tap, tap & hold and double tap & hold on each bud. Each gesture only offers the actions the buds accept for it.
- **Find my buds.** Ring the left or right bud.
- **Dual connection.** See which devices the buds are connected to (for example your phone and this PC), and connect or disconnect them.
- **Shares with your phone.** The buds let only one device use their controls at a time (audio dual connection is separate). So CMF Link takes the controls only while you use it, and hands them back 15 s later, leaving the Nothing X app on your phone free to connect.
  - **When your phone has them:** CMF Link shows your last readings and says so.
  - **When you swipe Nothing X away:** CMF Link picks the controls up on its own.
  - **Background behaviour:** Nothing X keeps the controls even in the background.
- **Tray extras.** A battery ring on the tray icon for the lower bud, a tooltip with both levels, and a right-click menu for noise control.
- **Optional extras.**
  - A low-battery alert at 15 %.
  - A global **Ctrl + Alt + N** shortcut that cycles noise control.
- **Feels native.** The panel opens from the tray like Windows 11's own flyouts, with an acrylic backdrop. It follows light and dark mode, works with Narrator and the keyboard, and respects "Animation effects".
- **Featherweight.** About 13 MB in Task Manager and 0.0 % CPU while connected and idle. The only background work is one 10-byte battery read every 5 minutes, because the 2a doesn't push changes to the PC. No scanning. It launches at login, which you can turn off.

## Install

1. Install the [.NET 10 Desktop Runtime](https://dotnet.microsoft.com/download/dotnet/10.0) (x64), if you don't have it.
2. [Download **CmfLink-win-x64.zip**](https://github.com/nk1044/airlink-releases/releases/latest/download/CmfLink-win-x64.zip), unzip it anywhere you like, and run `CmfLink.exe`. It isn't code-signed, so SmartScreen may ask first: click **More info › Run anyway**. (Or build it yourself, below.)
3. Connect your buds to the PC for audio. The icon appears in the tray.

**The icon isn't on the taskbar?** Windows 11 puts new tray icons in the **^** overflow. To keep it visible, drag it onto the taskbar, or go to **Settings › Personalization › Taskbar › Other system tray icons** and turn on **CMF Link**.

Running `CmfLink.exe` again while it's already running opens the panel.

**Requirements:** Windows 10 2004 or later (Windows 11 for the acrylic look), with CMF Buds 2a paired and connected to the PC.

## Build from source

You need the [.NET 10 SDK](https://dotnet.microsoft.com/download/dotnet/10.0); Visual Studio isn't required. The app has no third-party dependencies.

```powershell
cd cmflink
dotnet test                                             # unit tests (frames captured from real buds)
dotnet publish src/CmfLink.App -c Release -o dist/CmfLink   # ReadyToRun build in dist\CmfLink
.\dist\CmfLink\CmfLink.exe
```

`scripts/make-icon.ps1` regenerates the app icon.

## Repository layout

```
src/
  CmfLink.Protocol/    frame codec, CRC, commands, reply/push parsers (no Windows APIs)
  CmfLink.Core/        device profiles, Transport interface, BudsSession, DeviceStore
  CmfLink.Bluetooth/   Windows RFCOMM transport, paired-device lookup, connection lifecycle, channel lock
  CmfLink.App/         tray icon, flyout window and settings (WPF; no Bluetooth logic)
  cmflink-probe/       command-line protocol probe
tests/CmfLink.Tests/   protocol tests with frames captured from real buds; session and store tests
scripts/               icon generator
PROTOCOL.md            the control protocol, with sources and hardware confirmation
```

## How it works

Nothing and CMF buds share a control protocol over classic Bluetooth RFCOMM, the one the Nothing X app speaks. CMF Link implements it independently. [PROTOCOL.md](PROTOCOL.md) documents every fact it uses, where each one came from, and what has been confirmed on real hardware. That includes two quirks the existing projects miss:
- The buds checksum their replies over the payload only.
- The 2a reports its noise mode in the second of two entries.

**Lifecycle.** CMF Link waits dormant and does nothing until supported buds connect. It is notified of pairings through a `DeviceWatcher` and of connections through `ConnectionStatusChanged`. When the buds connect for audio, CMF Link:
1. Shows its tray icon.
2. Waits 2 seconds for Windows to finish setting up audio.
3. Opens the control channel and reads everything (about 0.6 s).
4. Reads the battery every 5 minutes, and everything again whenever you open the panel.

On disconnect it closes the panel, releases the channel, removes the icon and goes back to dormant. It never connects to the buds on its own.

**Architecture.**
- **Protocol layer:** plain .NET, unit-tested against captured frames.
- **`BudsSession`:** keeps one request in flight, with a 2.5 s timeout.
- **`DeviceStore`:** the observable UI state.
- **UI:**
  - **Tray icon:** a raw `Shell_NotifyIcon`.
  - **Panel:** a WPF window with the Windows 11 Fluent theme. It exists only while open, and its memory is handed back when it closes.

## Adding another model

Each model is one file in [`src/CmfLink.Core/Profiles/`](src/CmfLink.Core/Profiles). Copy `CmfBuds2a.cs`, adjust its name, noise values and feature flags, and add it to `DeviceRegistry.All`. Find the values with the probe rather than guessing. Then add the captured frames as tests next to `CapturedFramesTests.cs`.

## The probe

`cmflink-probe` talks to your buds from a terminal and prints every frame in and out. Quit CMF Link first: only one process can hold the buds' control channel, and the probe tells you if CMF Link has it.

```powershell
dotnet build src/cmflink-probe
$probe = ".\src\cmflink-probe\bin\Debug\net10.0-windows10.0.19041.0\cmflink-probe.exe"
& $probe list                    # paired devices, connection state, RFCOMM services
& $probe info                    # firmware, battery, noise, EQ, bass, latency, gestures, custom EQ, dual
& $probe noise transparency      # anc | transparency | off | 0xNN
& $probe eq pop                  # dirac | pop | rock | classical | electronic | vocals | custom
& $probe bass on 3               # or: bass off
& $probe latency on
& $probe find left on            # take the buds out first: it's loud
& $probe monitor 60 init         # print pushes for 60 s, after the official app's init
& $probe raw c007                # any request, with an optional payload
```

## Performance

Measured on Windows 11 with the Release (ReadyToRun) build, with the buds connected. "Task Manager" means the Memory column, which is the private working set.

| State | Memory (Task Manager) | CPU |
|---|---|---|
| Connected and idle | ≈ 13 MB | 0.0 % (0.016 s of CPU in 40 s) |
| Panel open | ≈ 70–150 MB while the Fluent theme is loaded; handed back on close | brief, while it draws |

## Troubleshooting

| Symptom | Fix |
|---|---|
| No icon after connecting the buds | Check the **^** overflow (see Install). Make sure CMF Link is running. The buds must be connected **to this PC** for audio. |
| "Couldn't reach the buds" | Click **Try Again**. If it keeps failing, turn the buds off and on, or put them in the case for a few seconds. |
| "Your phone's Nothing X app has the buds' controls" | Swipe Nothing X away from your phone's recent apps; pressing Home isn't enough. CMF Link picks the controls up within seconds while the panel is open, or within a minute otherwise. Audio keeps playing on both devices either way. |
| "cmflink-probe is using the buds" | Quit the probe, then click **Try Again**. |
| Noise control springs back | Put at least one bud in your ear. |
| Ctrl + Alt + N does nothing | Another app has the shortcut. |

**Logs.** Start it with `CmfLink.exe --log` to write `%LOCALAPPDATA%\CmfLink\cmflink.log`, with every frame in hex. Settings live in `%LOCALAPPDATA%\CmfLink\settings.json`.

## Credits

Protocol facts were learned from these open-source projects and re-implemented independently. No code was copied.
- [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge), for its CMF Buds 2a support
- [ear (web)](https://github.com/radiance-project/ear-web)
- [EarA-linux](https://github.com/oneydef/EarA-linux)
- [cmf-control](https://github.com/JScoder4005/cmf-control)

The design follows [AirLink](../airlink-mac) by nk1044. Details are in [PROTOCOL.md](PROTOCOL.md).

---

CMF Link is an independent project, not affiliated with or endorsed by Nothing Technology or CMF. CMF and Nothing are trademarks of their owner. Windows is a trademark of Microsoft.
