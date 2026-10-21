// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// Where the pointer is, so the tray panel can open beside the icon. WinUI's
// pointer events do not fire for a click that landed on the notification area.

using System.Runtime.InteropServices;

namespace KetchApp;

internal static class Cursor
{
    public readonly record struct Point(int X, int Y);

    public static Point Position()
    {
        GetCursorPos(out var point);
        return new Point(point.X, point.Y);
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Win32Point
    {
        public int X;
        public int Y;
    }

    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out Win32Point point);
}
