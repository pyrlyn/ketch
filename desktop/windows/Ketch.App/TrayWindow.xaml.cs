// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// The tray panel: pending upgrades, the running operation, and the commands
// the menu-bar extra has on macOS. It hides when it loses focus, which is how
// a panel opened from the notification area is dismissed.

using Ketch.AppCore;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Graphics;

namespace KetchApp;

public sealed partial class TrayWindow : Window
{
    private const int MaxRows = 5;
    private bool armed;

    public TrayWindow()
    {
        InitializeComponent();
        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.IsShownInSwitchers = false;
            presenter.IsResizable = false;
            presenter.IsMinimizable = false;
            presenter.IsMaximizable = false;
            presenter.SetBorderAndTitleBar(false, false);
        }

        AppWindow.Resize(new SizeInt32(340, 460));
        Activated += OnActivated;
        App.Store.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName is nameof(KetchStore.Updates)
                or nameof(KetchStore.Activity)
                or nameof(KetchStore.IsRunning)
                or nameof(KetchStore.PendingUpgradeCount)
                or nameof(KetchStore.Installed))
            {
                Render();
            }
        };
        Render();
    }

    /// <summary>Shows the panel just above the pointer, or below it when that would leave the screen.</summary>
    public void Present()
    {
        armed = false;
        Render();
        var cursor = Cursor.Position();
        var size = AppWindow.Size;
        var x = Math.Max(0, cursor.X - (size.Width / 2));
        var y = cursor.Y > size.Height + 12 ? cursor.Y - size.Height - 8 : cursor.Y + 8;
        AppWindow.Move(new PointInt32(x, y));
        AppWindow.Show();
        Activate();
    }

    private void OnActivated(object sender, WindowActivatedEventArgs e)
    {
        if (e.WindowActivationState == WindowActivationState.Deactivated)
        {
            // The click that opened the panel deactivates it once before the
            // activation lands. Hide only after it has actually been in front.
            if (armed)
            {
                AppWindow.Hide();
            }

            return;
        }

        armed = true;
    }

    private void Render()
    {
        var store = App.Store;
        var count = store.PendingUpgradeCount;
        var updates = count == 1 ? "1 update" : $"{count} updates";
        Summary.Text = $"{updates} · {store.Installed.Count} installed";

        Rows.Children.Clear();
        foreach (var update in store.Updates.Take(MaxRows))
        {
            var name = update.Name;
            var row = new Grid { ColumnSpacing = 8 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var label = new TextBlock
            {
                Text = $"{name}  {Fmt.Change(update.From, update.To)}",
                VerticalAlignment = VerticalAlignment.Center,
                TextTrimming = TextTrimming.CharacterEllipsis,
            };
            var button = new Button { Content = "Update", IsEnabled = !store.IsRunning };
            button.Click += async (_, _) =>
            {
                AppWindow.Hide();
                App.ShowMain();
                await store.UpgradeAsync([name]);
            };
            Grid.SetColumn(button, 1);
            row.Children.Add(label);
            row.Children.Add(button);
            Rows.Children.Add(row);
        }

        var extra = store.Updates.Count - MaxRows;
        if (extra > 0)
        {
            Rows.Children.Add(new TextBlock { Text = $"and {extra} more", Opacity = 0.7 });
        }

        var activity = store.Activity;
        var running = activity is not null;
        ActivityText.Visibility = Fmt.Show(running);
        ActivityText.Text = activity?.Title ?? "";
        Progress.Visibility = Fmt.Show(running);
        Progress.Value = activity?.Fraction ?? 0;
        Cancel.Visibility = Fmt.Show(running);
        Cancel.IsEnabled = activity is { IsCancelling: false };
        UpdateAll.IsEnabled = count > 0 && !running;
    }

    private void OnCancel(object sender, RoutedEventArgs e) => App.Store.Cancel();

    private async void OnUpdateAll(object sender, RoutedEventArgs e)
    {
        var count = App.Store.PendingUpgradeCount;
        if (!await Dialogs.ConfirmAsync(
                Root,
                $"Update {Fmt.Plural(count, "package")}?",
                "Each package is downloaded, verified and linked in turn. Held packages stay where they are.",
                "Update all"))
        {
            return;
        }

        AppWindow.Hide();
        App.ShowMain();
        await App.Store.UpgradeAsync();
    }

    private void OnOpen(object sender, RoutedEventArgs e)
    {
        AppWindow.Hide();
        App.ShowMain();
    }

    private async void OnCheck(object sender, RoutedEventArgs e) => await App.Store.RefreshAsync();

    private void OnSettings(object sender, RoutedEventArgs e)
    {
        AppWindow.Hide();
        App.Main?.ShowSection("settings");
    }

    private void OnQuit(object sender, RoutedEventArgs e) => App.Quit();
}
