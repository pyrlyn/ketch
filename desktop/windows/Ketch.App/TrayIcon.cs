// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// The notification-area icon. WinUI has no tray control, so this is
// Shell_NotifyIcon on a message-only window: subclassing the WinUI hwnd
// fights the window's own procedure. The panel itself is TrayWindow.

using System.Runtime.InteropServices;

namespace KetchApp;

/// <summary>A notification-area icon. <see cref="Clicked"/> is raised on the UI thread.</summary>
public sealed class TrayIcon
{
    private const uint NimAdd = 0x00000000;
    private const uint NimModify = 0x00000001;
    private const uint NimDelete = 0x00000002;
    private const uint NifMessage = 0x00000001;
    private const uint NifIcon = 0x00000002;
    private const uint NifTip = 0x00000004;
    private const uint Callback = 0x8001;
    private const uint WmLButtonUp = 0x0202;
    private const uint WmLButtonDblClk = 0x0203;
    private const uint WmRButtonUp = 0x0205;
    private const uint WmContextMenu = 0x007B;
    private const uint NinSelect = 0x0400;
    private const int ErrorClassAlreadyExists = 1410;
    private const string ClassName = "KetchTrayMessage";

    private static readonly IntPtr HwndMessage = new(-3);

    private readonly WndProc wndProc;
    private IntPtr hwnd;
    private IntPtr icon;
    private string tip = "Ketch";

    public TrayIcon() => wndProc = OnMessage;

    /// <summary>A click, left or right: the panel is the menu.</summary>
    public event Action? Clicked;

    public bool Added => hwnd != IntPtr.Zero;

    /// <summary>Adds the icon. False when Windows refuses; the caller keeps the window as the only UI.</summary>
    public bool Add()
    {
        if (hwnd != IntPtr.Zero)
        {
            return true;
        }

        var instance = GetModuleHandle(null);
        var cls = new WndClassEx
        {
            cbSize = (uint)Marshal.SizeOf<WndClassEx>(),
            lpfnWndProc = Marshal.GetFunctionPointerForDelegate(wndProc),
            hInstance = instance,
            lpszClassName = ClassName,
        };
        if (RegisterClassEx(ref cls) == 0 && Marshal.GetLastWin32Error() != ErrorClassAlreadyExists)
        {
            return false;
        }

        hwnd = CreateWindowEx(0, ClassName, "", 0, 0, 0, 0, 0, HwndMessage, IntPtr.Zero, instance, IntPtr.Zero);
        if (hwnd == IntPtr.Zero)
        {
            return false;
        }

        // A shared stock icon: LoadIcon's handle must not be destroyed.
        icon = LoadIcon(IntPtr.Zero, (IntPtr)32512);
        if (!Notify(NimAdd))
        {
            DestroyWindow(hwnd);
            hwnd = IntPtr.Zero;
            return false;
        }

        return true;
    }

    public void SetTip(string text)
    {
        tip = text.Length <= 127 ? text : text[..127];
        if (hwnd != IntPtr.Zero)
        {
            Notify(NimModify);
        }
    }

    public void Remove()
    {
        if (hwnd == IntPtr.Zero)
        {
            return;
        }

        Notify(NimDelete);
        DestroyWindow(hwnd);
        hwnd = IntPtr.Zero;
    }

    private bool Notify(uint message)
    {
        var data = new NotifyIconData
        {
            cbSize = Marshal.SizeOf<NotifyIconData>(),
            hWnd = hwnd,
            uID = 1,
            uFlags = unchecked((int)(NifMessage | NifIcon | NifTip)),
            uCallbackMessage = unchecked((int)Callback),
            hIcon = icon,
            szTip = tip,
        };
        return Shell_NotifyIcon(message, ref data);
    }

    private IntPtr OnMessage(IntPtr window, uint message, IntPtr wParam, IntPtr lParam)
    {
        if (message == Callback)
        {
            var mouse = unchecked((uint)lParam.ToInt64());
            if (mouse is WmLButtonUp or WmLButtonDblClk or WmRButtonUp or WmContextMenu or NinSelect)
            {
                Clicked?.Invoke();
            }

            return IntPtr.Zero;
        }

        return DefWindowProc(window, message, wParam, lParam);
    }

    private delegate IntPtr WndProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WndClassEx
    {
        public uint cbSize;
        public uint style;
        public IntPtr lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public IntPtr hInstance;
        public IntPtr hIcon;
        public IntPtr hCursor;
        public IntPtr hbrBackground;
        public string? lpszMenuName;
        public string lpszClassName;
        public IntPtr hIconSm;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NotifyIconData
    {
        public int cbSize;
        public IntPtr hWnd;
        public int uID;
        public int uFlags;
        public int uCallbackMessage;
        public IntPtr hIcon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string szTip;
        public int dwState;
        public int dwStateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)]
        public string szInfo;
        public int uTimeoutOrVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)]
        public string szInfoTitle;
        public int dwInfoFlags;
        public Guid guidItem;
        public IntPtr hBalloonIcon;
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern ushort RegisterClassEx(ref WndClassEx lpwcx);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateWindowEx(
        uint dwExStyle, string lpClassName, string lpWindowName, uint dwStyle,
        int x, int y, int nWidth, int nHeight, IntPtr hWndParent, IntPtr hMenu,
        IntPtr hInstance, IntPtr lpParam);

    [DllImport("user32.dll")]
    private static extern IntPtr DefWindowProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool DestroyWindow(IntPtr hWnd);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr GetModuleHandle(string? lpModuleName);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr LoadIcon(IntPtr hInstance, IntPtr lpIconName);

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern bool Shell_NotifyIcon(uint dwMessage, ref NotifyIconData lpData);
}
