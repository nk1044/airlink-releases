#include "firewall.h"
#include <shellapi.h>
#include <stdio.h>

/* Runs a console program hidden and returns its output (OEM code page), or NULL. */
static char *run_capture(WCHAR *cmdline)
{
    SECURITY_ATTRIBUTES sa = { sizeof sa, NULL, TRUE };
    HANDLE rd, wr;
    if (!CreatePipe(&rd, &wr, &sa, 0)) return NULL;
    SetHandleInformation(rd, HANDLE_FLAG_INHERIT, 0);
    STARTUPINFOW si = { 0 };
    si.cb = sizeof si;
    si.dwFlags = STARTF_USESTDHANDLES;
    si.hStdOutput = wr;
    si.hStdError = wr;
    PROCESS_INFORMATION pi;
    BOOL started = CreateProcessW(NULL, cmdline, NULL, NULL, TRUE, CREATE_NO_WINDOW, NULL, NULL, &si, &pi);
    CloseHandle(wr);
    if (!started) {
        CloseHandle(rd);
        return NULL;
    }
    size_t cap = 65536, n = 0;
    char *out = malloc(cap);
    DWORD got;
    while (out && ReadFile(rd, out + n, (DWORD)(cap - n - 1), &got, NULL) && got) {
        n += got;
        if (cap - n < 4096) {
            char *p = realloc(out, cap *= 2);
            if (!p) {
                free(out);
                out = NULL;
            }
            out = p;
        }
    }
    if (out) out[n] = 0;
    CloseHandle(rd);
    WaitForSingleObject(pi.hProcess, 5000);
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    return out;
}

static const char *find_nocase(const char *hay, const char *needle)
{
    size_t n = strlen(needle);
    for (; *hay; hay++)
        if (!_strnicmp(hay, needle, n)) return hay;
    return NULL;
}

/* netsh output for the rules named "ClipSync" (Windows names rules from its own prompt after the file description). */
static char *show_rules(char *exe_oem, size_t n)
{
    WCHAR exe[MAX_PATH], sys[MAX_PATH], cmd[MAX_PATH * 2];
    GetModuleFileNameW(NULL, exe, MAX_PATH);
    GetSystemDirectoryW(sys, MAX_PATH);
    WideCharToMultiByte(CP_OEMCP, 0, exe, -1, exe_oem, (int)n, NULL, NULL);
    swprintf(cmd, ARRAYSIZE(cmd), L"\"%s\\netsh.exe\" advfirewall firewall show rule name=ClipSync dir=in verbose", sys);
    return run_capture(cmd);
}

BOOL firewall_ok(void)
{
    char exe_oem[MAX_PATH * 2];
    char *out = show_rules(exe_oem, sizeof exe_oem);
    if (!out) return TRUE; /* can't tell: don't nag */
    BOOL ok = find_nocase(out, exe_oem) && strstr(out, "Public") && !strstr(out, "Block");
    free(out);
    return ok;
}

BOOL firewall_has_rules(void)
{
    char exe_oem[MAX_PATH * 2];
    char *out = show_rules(exe_oem, sizeof exe_oem);
    BOOL found = out && find_nocase(out, exe_oem);
    free(out);
    return found;
}

/* Runs netsh commands (joined by &) as administrator; returns TRUE if the last one succeeded. */
static BOOL run_elevated(HWND owner, const WCHAR *commands)
{
    WCHAR sys[MAX_PATH], cmd[MAX_PATH], args[MAX_PATH * 4];
    GetSystemDirectoryW(sys, MAX_PATH);
    swprintf(cmd, ARRAYSIZE(cmd), L"%s\\cmd.exe", sys);
    swprintf(args, ARRAYSIZE(args), L"/s /c \"%s\"", commands);
    SHELLEXECUTEINFOW sei = { 0 };
    sei.cbSize = sizeof sei;
    sei.fMask = SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC;
    sei.hwnd = owner;
    sei.lpVerb = L"runas";
    sei.lpFile = cmd;
    sei.lpParameters = args;
    sei.nShow = SW_HIDE;
    if (!ShellExecuteExW(&sei) || !sei.hProcess) return FALSE; /* the user said No */
    WaitForSingleObject(sei.hProcess, 20000);
    DWORD code = 1;
    GetExitCodeProcess(sei.hProcess, &code);
    CloseHandle(sei.hProcess);
    return code == 0;
}

BOOL firewall_fix(HWND owner)
{
    WCHAR exe[MAX_PATH], commands[MAX_PATH * 3];
    GetModuleFileNameW(NULL, exe, MAX_PATH);
    /* Drop every old rule for this exe (including "block" ones from a dismissed Windows prompt), then allow it. */
    swprintf(commands, ARRAYSIZE(commands),
             L"netsh advfirewall firewall delete rule name=all program=\"%s\" >nul & "
             L"netsh advfirewall firewall add rule name=ClipSync dir=in action=allow program=\"%s\" enable=yes profile=any",
             exe, exe);
    return run_elevated(owner, commands);
}

BOOL firewall_remove(HWND owner)
{
    WCHAR exe[MAX_PATH], commands[MAX_PATH * 2];
    GetModuleFileNameW(NULL, exe, MAX_PATH);
    swprintf(commands, ARRAYSIZE(commands), L"netsh advfirewall firewall delete rule name=all program=\"%s\"", exe);
    return run_elevated(owner, commands);
}
