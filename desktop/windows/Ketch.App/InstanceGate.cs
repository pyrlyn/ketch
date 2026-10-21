// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// One ketch window. A second process, started from a ketch:// link or a
// notification, hands its activation to the first and leaves. The key is
// per user session, which is the scope AppInstance provides.

using Microsoft.Windows.AppLifecycle;
using Microsoft.Windows.AppNotifications;
using Windows.ApplicationModel.Activation;

namespace KetchApp;

internal static class InstanceGate
{
    public const string Key = "Ketch";

    private static AppInstance? primary;

    public static event Action<AppActivationArguments>? Activated;

    /// <summary>
    /// True when this process owns the key. <paramref name="activation"/> is
    /// how this process was started, including a redirect target.
    /// </summary>
    public static bool TryBecomePrimary(out AppActivationArguments? activation)
    {
        var current = AppInstance.GetCurrent();
        var owner = AppInstance.FindOrRegisterForKey(Key);
        primary = owner;
        activation = current.GetActivatedEventArgs();
        if (!owner.IsCurrent)
        {
            return false;
        }

        current.Activated += (_, args) => Activated?.Invoke(args);
        return true;
    }

    public static void RedirectAndExit(AppActivationArguments? activation)
    {
        if (activation is not null)
        {
            primary?.RedirectActivationToAsync(activation).AsTask().Wait();
        }

        Environment.Exit(0);
    }

    public static string? Protocol(AppActivationArguments args)
    {
        if (args.Kind == ExtendedActivationKind.Protocol && args.Data is IProtocolActivatedEventArgs protocol)
        {
            return protocol.Uri?.AbsoluteUri;
        }

        return null;
    }

    public static bool IsUpdateNotification(AppActivationArguments args) =>
        args.Kind == ExtendedActivationKind.AppNotification
        && args.Data is AppNotificationActivatedEventArgs toast
        && WantsUpdates(toast.Argument);

    public static bool WantsUpdates(string? argument) =>
        argument?.Contains("action=updates", StringComparison.Ordinal) == true;
}
