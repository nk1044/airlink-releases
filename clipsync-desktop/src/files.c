#include "files.h"
#include <shlobj.h>
#include <stdio.h>
#include <wctype.h>

BOOL files_download_dir(WCHAR *out, size_t n)
{
    WCHAR *dl;
    if (FAILED(SHGetKnownFolderPath(&FOLDERID_Downloads, KF_FLAG_CREATE, NULL, &dl))) return FALSE;
    swprintf(out, n, L"%s\\ClipSync", dl);
    CoTaskMemFree(dl);
    CreateDirectoryW(out, NULL);
    return TRUE;
}

/* Turns a name from the phone into a safe base name: no paths, no reserved characters or device names. */
static void sanitize(WCHAR *name)
{
    static const WCHAR *reserved[] = { L"CON", L"PRN", L"AUX", L"NUL", L"COM", L"LPT" };
    for (WCHAR *p = name; *p; p++)
        if (*p < 32 || wcschr(L"\\/:*?\"<>|", *p)) *p = L'_';
    size_t n = wcslen(name);
    while (n && (name[n - 1] == L'.' || name[n - 1] == L' ')) name[--n] = 0;
    for (size_t i = 0; i < ARRAYSIZE(reserved); i++) {
        size_t rl = wcslen(reserved[i]);
        if (!_wcsnicmp(name, reserved[i], rl) &&
            (name[rl] == 0 || name[rl] == L'.' || (rl == 3 && iswdigit(name[rl]))))
            name[0] = L'_';
    }
    if (!name[0]) wcscpy(name, L"file");
}

static BOOL exists(const WCHAR *path) { return GetFileAttributesW(path) != INVALID_FILE_ATTRIBUTES; }

void files_rx_begin(RxState *rx, const BYTE *p, size_t n)
{
    WCHAR dir[MAX_PATH], base[256], stem[256];
    files_rx_cancel(rx);
    if (n < 10 || 10 + get_be16(p + 8) > n || !files_download_dir(dir, MAX_PATH)) return;
    rx->size = get_be64(p);
    WCHAR *name = wide_from_utf8((const char *)p + 10, (int)get_be16(p + 8));
    if (!name) return;
    wcsncpy(base, name, 200);
    base[200] = 0;
    free(name);
    sanitize(base);

    /* name.ext, name (1).ext, name (2).ext, ... */
    WCHAR *dot = wcsrchr(base, L'.');
    const WCHAR *ext = dot && dot != base ? dot : L"";
    wcsncpy(stem, base, (size_t)(ext[0] ? dot - base : 255));
    stem[ext[0] ? dot - base : 255] = 0;
    swprintf(rx->final, ARRAYSIZE(rx->final), L"%s\\%s", dir, base);
    for (int i = 1; exists(rx->final) && i < 10000; i++)
        swprintf(rx->final, ARRAYSIZE(rx->final), L"%s\\%s (%d)%s", dir, stem, i, ext);
    swprintf(rx->part, ARRAYSIZE(rx->part), L"%s.part", rx->final);

    rx->got = 0;
    rx->h = CreateFileW(rx->part, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (rx->h == INVALID_HANDLE_VALUE) rx->h = NULL;
}

void files_rx_data(RxState *rx, const BYTE *p, size_t n)
{
    DWORD w;
    if (!rx->h) return;
    if (rx->got + n > rx->size || !WriteFile(rx->h, p, (DWORD)n, &w, NULL) || w != n) {
        files_rx_cancel(rx);
        return;
    }
    rx->got += n;
}

void files_rx_end(RxState *rx)
{
    if (!rx->h) return;
    CloseHandle(rx->h);
    rx->h = NULL;
    if (rx->got == rx->size && MoveFileExW(rx->part, rx->final, 0))
        file_list_add(&rx->done, _wcsdup(rx->final));
    else
        DeleteFileW(rx->part);
}

void files_rx_cancel(RxState *rx)
{
    if (!rx->h) return;
    CloseHandle(rx->h);
    rx->h = NULL;
    DeleteFileW(rx->part);
}

void files_rx_done(RxState *rx)
{
    if (!rx->done.n) return;
    FileList *l = malloc(sizeof *l);
    if (l) {
        *l = rx->done;
        memset(&rx->done, 0, sizeof rx->done);
        if (PostMessageW(g_hwnd, WM_APP_RECV_FILES, 0, (LPARAM)l)) return;
        rx->done = *l;
        free(l);
    }
    file_list_free(&rx->done);
}

void files_rx_abort(RxState *rx)
{
    files_rx_cancel(rx);
    files_rx_done(rx); /* keep whatever finished before the link dropped */
}

/* Adds "Phone (ClipSync)" to Explorer's Send to menu; it runs "clipsync.exe --send <paths>". */
void files_install_sendto(void)
{
    WCHAR *dir, lnk[MAX_PATH], exe[MAX_PATH];
    if (FAILED(SHGetKnownFolderPath(&FOLDERID_SendTo, 0, NULL, &dir))) return;
    swprintf(lnk, MAX_PATH, L"%s\\Phone (ClipSync).lnk", dir);
    CoTaskMemFree(dir);
    if (exists(lnk)) return;

    GetModuleFileNameW(NULL, exe, MAX_PATH);
    HRESULT init = CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);
    IShellLinkW *sl;
    if (SUCCEEDED(CoCreateInstance(&CLSID_ShellLink, NULL, CLSCTX_INPROC_SERVER, &IID_IShellLinkW, (void **)&sl))) {
        IPersistFile *pf;
        sl->lpVtbl->SetPath(sl, exe);
        sl->lpVtbl->SetArguments(sl, L"--send");
        sl->lpVtbl->SetDescription(sl, L"Send files to your phone with ClipSync");
        if (SUCCEEDED(sl->lpVtbl->QueryInterface(sl, &IID_IPersistFile, (void **)&pf))) {
            pf->lpVtbl->Save(pf, lnk, TRUE);
            pf->lpVtbl->Release(pf);
        }
        sl->lpVtbl->Release(sl);
    }
    if (SUCCEEDED(init)) CoUninitialize();
}
