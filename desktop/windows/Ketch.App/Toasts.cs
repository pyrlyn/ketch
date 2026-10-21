// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// App notifications for an unpackaged app. Register() installs the COM server
// that lets Windows launch this executable when a toast is clicked, so it has
// to run before the activation arguments are read.

using Ketch.AppCore;
using Microsoft.UI.Dispatching;
using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;

namespace KetchApp;

internal static class Toasts
{
    public static IUpdateNotifier Notifier { get; } = new Poster();

    public static void Register(Action<string> onArgument, DispatcherQueue queue)
    {
        AppNotificationManager.Default.NotificationInvoked += (_, args) =>
        {
            var argument = args.Argument ?? "";
            queue.TryEnqueue(() => onArgument(argument));
        };
        AppNotificationManager.Default.Register();
    }

    /// <summary>Takes the COM registration back after a second process registered itself on the way out.</summary>
    public static void Reregister()
    {
        try
        {
            AppNotificationManager.Default.Register();
        }
        catch (Exception)
        {
            // Already the current registration.
        }
    }

    public static void Unregister()
    {
        try
        {
            AppNotificationManager.Default.Unregister();
        }
        catch (Exception)
        {
            // Already gone with the process.
        }
    }

    private sealed class Poster : IUpdateNotifier
    {
        public void Post(string title, string body)
        {
            var notification = new AppNotificationBuilder()
                .AddArgument("action", "updates")
                .AddText(title)
                .AddText(body)
                .BuildNotification();
            AppNotificationManager.Default.Show(notification);
        }
    }
}
