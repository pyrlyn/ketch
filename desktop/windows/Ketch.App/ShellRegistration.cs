// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// Startup and the ketch protocol, for an unpackaged app. A packaged app would
// declare both in its manifest; ActivationRegistrationManager is the path that
// works without one, which is what `WindowsPackageType=None` ships.

using Microsoft.Windows.AppLifecycle;

namespace KetchApp;

internal static class ShellRegistration
{
    public const string Scheme = "ketch";
    public const string StartupTask = "Ketch";

    /// <summary>Registers or removes the login entry. Null when it stuck.</summary>
    public static string? SetStartup(bool enabled)
    {
        try
        {
            if (enabled)
            {
                TryUnregisterStartup();
                ActivationRegistrationManager.RegisterForStartupActivation(StartupTask, "");
            }
            else
            {
                ActivationRegistrationManager.UnregisterForStartupActivation(StartupTask);
            }

            return null;
        }
        catch (Exception error)
        {
            return error.Message;
        }
    }

    /// <summary>Registers or removes <c>ketch://</c>. Null when it stuck.</summary>
    public static string? SetProtocol(bool enabled)
    {
        try
        {
            if (enabled)
            {
                TryUnregisterProtocol();
                ActivationRegistrationManager.RegisterForProtocolActivation(Scheme, Logo(), "Ketch", "");
            }
            else
            {
                ActivationRegistrationManager.UnregisterForProtocolActivation(Scheme, "");
            }

            return null;
        }
        catch (Exception error)
        {
            return error.Message;
        }
    }

    // An empty executable path means this process. The logo has to name a file:
    // unpackaged registration wants a binary plus a resource index, and the
    // executable's first icon is the one Windows can always find.
    private static string Logo()
    {
        var exe = Environment.ProcessPath;
        return string.IsNullOrEmpty(exe) ? "" : exe + ",0";
    }

    private static void TryUnregisterStartup()
    {
        try
        {
            ActivationRegistrationManager.UnregisterForStartupActivation(StartupTask);
        }
        catch (Exception)
        {
            // Not registered yet, which is what the following register replaces.
        }
    }

    private static void TryUnregisterProtocol()
    {
        try
        {
            ActivationRegistrationManager.UnregisterForProtocolActivation(Scheme, "");
        }
        catch (Exception)
        {
            // Not registered yet, which is what the following register replaces.
        }
    }
}
