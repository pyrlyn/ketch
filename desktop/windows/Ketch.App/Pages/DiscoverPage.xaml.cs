// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// Discover: search the registry and install from the results.

using System.ComponentModel;
using Ketch.AppCore;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace KetchApp.Pages;

public sealed partial class DiscoverPage : Page
{
    public DiscoverPage()
    {
        InitializeComponent();
        Loaded += async (_, _) =>
        {
            App.Store.PropertyChanged += OnStoreChanged;
            await App.Store.SearchAsync(Query.Text);
            Render();
        };
        Unloaded += (_, _) => App.Store.PropertyChanged -= OnStoreChanged;
    }

    private void OnStoreChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(KetchStore.SearchResults))
        {
            Render();
        }
    }

    // On submit, not per keystroke: a search can reach GitHub's search API,
    // which allows a handful of unauthenticated requests a minute.
    private async void OnQuerySubmitted(AutoSuggestBox sender, AutoSuggestBoxQuerySubmittedEventArgs args) =>
        await App.Store.SearchAsync(args.QueryText);

    private void Render()
    {
        var results = App.Store.SearchResults;
        Results.ItemsSource = results;
        Results.Visibility = Fmt.ShowWhenAny(results.Count);
        Empty.Visibility = Fmt.ShowWhenEmpty(results.Count);
        var query = Query.Text.Trim();
        EmptyText.Text = query.Length == 0 ? "Search the registry and GitHub" : $"No package matches \"{query}\"";
    }

    private async void OnInstall(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: string name })
        {
            await App.Store.InstallAsync(name);
        }
    }
}
