// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// Application start: builds the store on the core CoreFactory picks, applies
// the saved settings and opens the window.

using Ketch.AppCore;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;

namespace KetchApp;

public partial class App : Application
{
    private Window? window;

    public App()
    {
        InitializeComponent();
    }

    /// <summary>The core behind <see cref="Store"/>; Settings scripts it when it is the fake.</summary>
    public static IKetchCore Core { get; } = CoreFactory.Create();

    /// <summary>The app's own preferences, read once at start and saved as they change.</summary>
    public static AppSettings Settings { get; } = AppSettings.Load(AppSettings.DefaultPath);

    /// <summary>The saved theme; anything the file says that is not one is the system's.</summary>
    public static ElementTheme Theme =>
        Enum.TryParse<ElementTheme>(Settings.Theme, out var theme) && Enum.IsDefined(theme) ? theme : ElementTheme.Default;

    /// <summary>What every page binds to; events reach it through the UI thread's queue.</summary>
    public static KetchStore Store { get; private set; } = null!;

    public static MainWindow? Main { get; private set; }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        var queue = DispatcherQueue.GetForCurrentThread();
        Store = new KetchStore(Core, action => queue.TryEnqueue(() => action()));
        Main = new MainWindow();
        Main.SetTheme(Theme);
        window = Main;
        window.Activate();
    }
}
