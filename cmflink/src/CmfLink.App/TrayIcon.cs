using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using static CmfLink.App.Native;

namespace CmfLink.App;

/// <summary>
/// The notification-area icon, on a hidden top-level window (a message-only window would miss
/// "TaskbarCreated", which Explorer broadcasts when it restarts). Also owns the global hotkey.
/// </summary>
internal sealed class TrayIcon : IDisposable
{
    private const int IconId = 1;
    private const int CallbackMessage = WM_APP + 1;
    private const int HotkeyId = 1;

    private readonly HwndSource _window;
    private readonly int _taskbarCreated;
    private IntPtr _icon;
    private string _tip = "";
    private bool _visible;
    private bool _hotkey;

    /// <summary>Left click or keyboard select.</summary>
    public event Action? Invoked;
    /// <summary>Right click, with the screen point (physical pixels) to show the menu at.</summary>
    public event Action<int, int>? MenuRequested;
    public event Action? HotkeyPressed;
    /// <summary>Light/dark or DPI changed: the icon needs redrawing.</summary>
    public event Action? AppearanceChanged;

    public TrayIcon()
    {
        _window = new HwndSource(new HwndSourceParameters("CmfLinkTray")
        {
            Width = 0,
            Height = 0,
            WindowStyle = unchecked((int)0x80000000), // WS_POPUP, never shown
        });
        _window.AddHook(WndProc);
        _taskbarCreated = RegisterWindowMessageW("TaskbarCreated");
    }

    public IntPtr Handle => _window.Handle;

    /// <summary>DPI of the window, which Windows keeps at the primary monitor's: the taskbar's.</summary>
    public uint Dpi => Math.Max(96u, GetDpiForWindow(_window.Handle));

    /// <summary>Shows the icon, or updates it. Takes ownership of <paramref name="icon"/>.</summary>
    public void Show(IntPtr icon, string tip)
    {
        var old = _icon;
        _icon = icon;
        _tip = tip.Length > 127 ? tip[..127] : tip;
        var data = Data(NIF_MESSAGE | NIF_ICON | NIF_TIP | NIF_SHOWTIP);
        if (_visible)
        {
            Shell_NotifyIconW(NIM_MODIFY, ref data);
        }
        else
        {
            _visible = Shell_NotifyIconW(NIM_ADD, ref data);
            data.uVersion = NOTIFYICON_VERSION_4;
            Shell_NotifyIconW(NIM_SETVERSION, ref data);
        }
        if (old != IntPtr.Zero && old != icon) DestroyIcon(old);
    }

    public void Hide()
    {
        if (!_visible) return;
        var data = Data(0);
        Shell_NotifyIconW(NIM_DELETE, ref data);
        _visible = false;
    }

    public bool IsVisible => _visible;

    /// <summary>A Windows notification attributed to the tray icon. Quiet and respects Focus Assist.</summary>
    public void Notify(string title, string text)
    {
        if (!_visible) return;
        var data = Data(NIF_INFO);
        data.szInfoTitle = title;
        data.szInfo = text;
        data.dwInfoFlags = NIIF_INFO | NIIF_NOSOUND | NIIF_RESPECT_QUIET_TIME;
        Shell_NotifyIconW(NIM_MODIFY, ref data);
    }

    /// <summary>The icon's rectangle in physical pixels, or null when Windows can't say (e.g. in the overflow).</summary>
    public RECT? IconRect()
    {
        var id = new NOTIFYICONIDENTIFIER { cbSize = Marshal.SizeOf<NOTIFYICONIDENTIFIER>(), hWnd = Handle, uID = IconId };
        return Shell_NotifyIconGetRect(ref id, out var rect) == 0 && rect.Width > 0 ? rect : null;
    }

    /// <summary>Ctrl+Alt+N. Returns false if another app already has it.</summary>
    public bool SetHotkey(bool enabled)
    {
        if (enabled == _hotkey) return true;
        if (enabled) _hotkey = RegisterHotKey(Handle, HotkeyId, MOD_CONTROL | MOD_ALT | MOD_NOREPEAT, 'N');
        else
        {
            UnregisterHotKey(Handle, HotkeyId);
            _hotkey = false;
        }
        return _hotkey == enabled;
    }

    /// <summary>A native popup menu. Returns the chosen id, or 0.</summary>
    public int ShowMenu(int x, int y, IEnumerable<(int Id, string? Text, bool Checked, bool Enabled)> items)
    {
        var menu = CreatePopupMenu();
        try
        {
            foreach (var (id, text, isChecked, enabled) in items)
            {
                if (text == null)
                {
                    AppendMenuW(menu, MF_SEPARATOR, UIntPtr.Zero, null);
                    continue;
                }
                uint flags = MF_STRING | (isChecked ? MF_CHECKED | MFT_RADIOCHECK : 0) | (enabled ? 0 : MF_GRAYED);
                AppendMenuW(menu, flags, (UIntPtr)id, text);
            }
            // Without this the menu doesn't close when the user clicks elsewhere.
            SetForegroundWindow(Handle);
            int chosen = TrackPopupMenuEx(menu, TPM_RIGHTBUTTON | TPM_RETURNCMD | TPM_BOTTOMALIGN | TPM_RIGHTALIGN, x, y, Handle, IntPtr.Zero);
            PostMessageW(Handle, WM_NULL, IntPtr.Zero, IntPtr.Zero);
            return chosen;
        }
        finally
        {
            DestroyMenu(menu);
        }
    }

    private NOTIFYICONDATAW Data(int flags) => new()
    {
        cbSize = Marshal.SizeOf<NOTIFYICONDATAW>(),
        hWnd = Handle,
        uID = IconId,
        uFlags = flags,
        uCallbackMessage = CallbackMessage,
        hIcon = _icon,
        szTip = _tip,
        szInfo = "",
        szInfoTitle = "",
    };

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == CallbackMessage)
        {
            // Version 4: the event is in the low word of lParam, the anchor point in wParam.
            int ev = (int)(lParam.ToInt64() & 0xFFFF);
            switch (ev)
            {
                case NIN_SELECT:
                case NIN_KEYSELECT:
                    Invoked?.Invoke();
                    break;
                case WM_CONTEXTMENU:
                    long w = wParam.ToInt64();
                    MenuRequested?.Invoke((short)(w & 0xFFFF), (short)((w >> 16) & 0xFFFF));
                    break;
            }
            handled = true;
        }
        else if (msg == _taskbarCreated && _visible)
        {
            // Explorer restarted and forgot the icon.
            _visible = false;
            if (_icon != IntPtr.Zero) Show(_icon, _tip);
        }
        else if (msg == WM_HOTKEY && wParam.ToInt32() == HotkeyId)
        {
            HotkeyPressed?.Invoke();
            handled = true;
        }
        else if (msg is WM_SETTINGCHANGE or WM_DPICHANGED)
        {
            AppearanceChanged?.Invoke();
        }
        return IntPtr.Zero;
    }

    public void Dispose()
    {
        SetHotkey(false);
        Hide();
        if (_icon != IntPtr.Zero) DestroyIcon(_icon);
        _icon = IntPtr.Zero;
        _window.Dispose();
    }
}
