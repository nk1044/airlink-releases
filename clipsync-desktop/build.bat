@echo off
rem Builds build\clipsync.exe and build\testclient.exe with MinGW-w64 gcc (MSYS2 UCRT64).
setlocal
cd /d "%~dp0"
if not exist build mkdir build

set CFLAGS=-std=c17 -Os -Wall -Wextra -Wno-unused-parameter -Wno-cast-function-type -ffunction-sections -fdata-sections
set LDFLAGS=-s -static-libgcc -Wl,--gc-sections

windres -I src src\resource.rc -O coff -o build\resource.o || exit /b 1

gcc %CFLAGS% -mwindows -municode src\main.c src\util.c src\crypto.c src\proto.c src\config.c src\link.c ^
    src\clip.c src\image.c src\files.c src\icons.c src\settings.c build\resource.o ^
    -o build\clipsync.exe %LDFLAGS% ^
    -lws2_32 -lbcrypt -lcrypt32 -lwindowscodecs -lole32 -loleaut32 -lshell32 -luser32 -lgdi32 -ladvapi32 -luuid -liphlpapi ^
    || exit /b 1

gcc %CFLAGS% -municode tools\testclient.c src\crypto.c src\proto.c -o build\testclient.exe %LDFLAGS% -lws2_32 -lbcrypt || exit /b 1

echo Built build\clipsync.exe
