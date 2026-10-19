// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// Application start: one instance, the tray, update notices, and the startup
// and protocol registrations. The window closes into the tray while the icon
// is on; Quit is what actually leaves.

using System.ComponentModel;
using Ketch.AppCore;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.Windows.AppLifecycle;

namespace KetchApp;

public partial class App : Application
{
    private TrayIcon? tray;
    private TrayWindow? panel;

    public App()
    {
        InitializeComponent();
    }

    /// <summary>The fake core behind <see cref="Store"/>, for Settings to script.</summary>
    public static FakeKetchCore Core { get; } = FakeCoreSetup.Create();

    /// <summary>What every page binds to; events reach it through the UI thread's queue.</summary>
    public static KetchStore Store { get; private set; } = null!;

    public static MainWindow? Main { get; private set; }

    public static AppSettings Settings { get; private set; } = null!;

    /// <summary>The last startup or protocol registration that failed, for Settings to show.</summary>
    public static string? ShellError { get; private set; }

    public static bool Quitting { get; private set; }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        var ui = DispatcherQueue.GetForCurrentThread();
        if (ui is null)
        {
            throw new InvalidOperationException("Ketch was started off the UI thread.");
        }

        // Register before reading how this process was started: a toast click
        // otherwise arrives with no arguments.
        try
        {
            Toasts.Register(OnToast, ui);
        }
        catch (Exception error)
        {
            ShellError = error.Message;
        }

        if (!InstanceGate.TryBecomePrimary(out var activation))
        {
            InstanceGate.RedirectAndExit(activation);
            return;
        }

        Settings = AppSettings.Load(AppSettings.DefaultDirectory());
        Store = new KetchStore(Core, action => ui.TryEnqueue(() => action()), Settings, Toasts.Notifier);
        Main = new MainWindow();
        Main.AppWindow.Closing += OnMainClosing;
        Main.Activate();

        try
        {
            ApplyRegistrations();
            if (Settings.ShowTray)
            {
                ShellError ??= SetShowTray(true);
            }
        }
        catch (Exception error)
        {
            // A settings file that cannot be written must not stop the window opening.
            ShellError ??= error.Message;
        }

        Store.PropertyChanged += OnStoreChanged;
        InstanceGate.Activated += forwarded => ui.TryEnqueue(() =>
        {
            Toasts.Reregister();
            HandleActivation(forwarded);
        });
        HandleActivation(activation);
    }

    public static void ShowMain()
    {
        if (Main is null)
        {
            return;
        }

        Main.AppWindow.Show();
        Main.Activate();
    }

    public static void Quit()
    {
        Quitting = true;
        if (Current is App app)
        {
            app.tray?.Remove();
        }

        Toasts.Unregister();
        Current.Exit();
    }

    /// <summary>Shows or removes the notification-area icon. Null when it stuck.</summary>
    public static string? SetShowTray(bool on)
    {
        if (Current is not App app)
        {
            return "Ketch is not running.";
        }

        if (!on)
        {
            app.tray?.Remove();
            Settings.ShowTray = false;
            Settings.Save();
            return null;
        }

        app.tray ??= new TrayIcon();
        app.tray.Clicked -= app.OnTrayClick;
        app.tray.Clicked += app.OnTrayClick;
        if (!app.tray.Add())
        {
            return "The notification area icon could not be added.";
        }

        app.tray.SetTip(app.Tip());
        Settings.ShowTray = true;
        Settings.Save();
        return null;
    }

    public static string? SetOpenAtLogin(bool on)
    {
        var error = ShellRegistration.SetStartup(on);
        if (error is null)
        {
            Settings.OpenAtLogin = on;
            Settings.Save();
        }

        ShellError = error;
        return error;
    }

    public static string? SetHandleLinks(bool on)
    {
        var error = ShellRegistration.SetProtocol(on);
        if (error is null)
        {
            Settings.HandleLinks = on;
            Settings.Save();
        }

        ShellError = error;
        return error;
    }

    private static void ApplyRegistrations()
    {
        if (Settings.OpenAtLogin)
        {
            ShellError ??= ShellRegistration.SetStartup(true);
        }

        if (Settings.HandleLinks)
        {
            ShellError ??= ShellRegistration.SetProtocol(true);
        }
    }

    private void OnMainClosing(AppWindow sender, AppWindowClosingEventArgs args)
    {
        // The icon is the app while the window is closed. Quit is the way out.
        if (!Quitting && Settings.ShowTray && tray is { Added: true })
        {
            args.Cancel = true;
            sender.Hide();
        }
    }

    private void OnTrayClick()
    {
        panel ??= new TrayWindow();
        panel.Present();
    }

    private void OnStoreChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(KetchStore.PendingUpgradeCount) or nameof(KetchStore.IsRunning))
        {
            tray?.SetTip(Tip());
        }
    }

    private void OnToast(string argument)
    {
        if (!InstanceGate.WantsUpdates(argument))
        {
            return;
        }

        ShowMain();
        Store.OpenUpdates();
    }

    private static void HandleActivation(AppActivationArguments? activation)
    {
        if (activation is null)
        {
            return;
        }

        if (InstanceGate.Protocol(activation) is { } link)
        {
            ShowMain();
            _ = Store.OpenLinkAsync(link);
            return;
        }

        if (InstanceGate.IsUpdateNotification(activation))
        {
            ShowMain();
            Store.OpenUpdates();
        }
    }

    private string Tip()
    {
        var count = Store.PendingUpgradeCount;
        var updates = count switch
        {
            0 => "",
            1 => ", 1 update",
            _ => $", {count} updates",
        };
        var working = Store.IsRunning ? ", working" : "";
        var tip = "Ketch" + updates + working;
        return tip.Length <= 127 ? tip : tip[..127];
    }
}
