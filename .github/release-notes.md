## {{VERSION}}

| App | Platform | Download |
|---|---|---|
| **AirLink**: realme Buds Air 6 Pro in the menu bar | macOS 14+ (Apple silicon and Intel) | `AirLink.dmg` |
| **CMF Link**: CMF Buds 2a in the tray | Windows 10 2004+ (x64) | `CmfLink-win-x64.zip` |
| **ClipSync**: clipboard and files between PC and phone | Windows 10+ | `ClipSync.exe` |
| **ClipSync**: the phone side | Android 13+ | `ClipSync.apk` |

`SHA256SUMS.txt` holds the SHA-256 checksum of every file.

### AirLink (macOS)

1. Download **AirLink.dmg**, open it, and drag AirLink to Applications.
2. Open AirLink. It's free and not notarized by Apple, so macOS asks first: click **Done**, then go to **System Settings › Privacy & Security** and click **Open Anyway**.
   (Or in Terminal: `xattr -dr com.apple.quarantine /Applications/AirLink.app`)
3. Allow Bluetooth when asked, and connect your buds.

### CMF Link (Windows)

1. Install the [.NET 10 Desktop Runtime](https://dotnet.microsoft.com/download/dotnet/10.0) (x64), if you don't have it.
2. Download **CmfLink-win-x64.zip**, unzip it anywhere, and run `CmfLink.exe`. Windows SmartScreen may ask first: click **More info › Run anyway**.
3. Connect your buds to the PC. The icon appears in the tray, or in its **^** overflow.

### ClipSync (Windows + Android)

1. On the PC, download **ClipSync.exe** and run it. It's a single file with nothing to install. SmartScreen may ask first: click **More info › Run anyway**.
2. On the phone, download **ClipSync.apk** and install it (allow installs from your browser when asked).
3. On the PC, right-click the ClipSync icon near the clock and choose **Link a phone**. In the phone app, tap **Link with PC**, check that both show the same code, and click **Link** on the PC. Both need the same Wi-Fi the first time.
