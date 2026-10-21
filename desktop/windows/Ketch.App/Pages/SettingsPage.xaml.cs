// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// Settings: the theme, the tray, notices, login and ketch links, where ketch
// lives, and, only when the app runs on the fake core, its scenario picker.
// The choices persist in %LOCALAPPDATA%\ketch\settings.json, which an
// unpackaged app and an MSIX can both write.

using Ketch.AppCore;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace KetchApp.Pages;

public sealed partial class SettingsPage : Page
{
    private const string Simulated = "Simulated pipeline";

    private readonly IReadOnlyList<ContractScenario> scenarios =
        [.. FakeCoreSetup.Scenarios().Where(FakeCoreSetup.IsMutation)];

    private bool loading = true;

    public SettingsPage()
    {
        InitializeComponent();
        Root.Text = App.Store.Root;
        Theme.SelectedIndex = App.Settings.Theme switch { "Light" => 1, "Dark" => 2, _ => 0 };

        FakeCore.Visibility = App.Core is FakeKetchCore ? Visibility.Visible : Visibility.Collapsed;
        Scenario.Items.Add(Simulated);
        foreach (var scenario in scenarios)
        {
            Scenario.Items.Add(scenario.Name);
        }

        Scenario.SelectedIndex = 0;
        Tray.IsOn = App.Settings.ShowTray;
        Notices.IsOn = App.Settings.NotifiesOfUpdates;
        Login.IsOn = App.Settings.OpenAtLogin;
        Links.IsOn = App.Settings.HandleLinks;
        ShellStatus.Text = App.ShellError ?? "";
        loading = false;
    }

    private void OnTrayToggled(object sender, RoutedEventArgs e)
    {
        if (!loading)
        {
            Apply(Tray, App.Settings.ShowTray, App.SetShowTray(Tray.IsOn));
        }
    }

    private void OnNoticesToggled(object sender, RoutedEventArgs e)
    {
        if (loading)
        {
            return;
        }

        try
        {
            App.Store.SetNotifies(Notices.IsOn);
            ShellStatus.Text = "";
        }
        catch (Exception error)
        {
            ShellStatus.Text = error.Message;
            loading = true;
            Notices.IsOn = App.Settings.NotifiesOfUpdates;
            loading = false;
        }
    }

    private void OnLoginToggled(object sender, RoutedEventArgs e)
    {
        if (!loading)
        {
            Apply(Login, App.Settings.OpenAtLogin, App.SetOpenAtLogin(Login.IsOn));
        }
    }

    private void OnLinksToggled(object sender, RoutedEventArgs e)
    {
        if (!loading)
        {
            Apply(Links, App.Settings.HandleLinks, App.SetHandleLinks(Links.IsOn));
        }
    }

    /// <summary>A failed registration puts the switch back and says why.</summary>
    private void Apply(ToggleSwitch toggle, bool saved, string? error)
    {
        if (loading)
        {
            return;
        }

        ShellStatus.Text = error ?? "";
        if (error is null)
        {
            return;
        }

        loading = true;
        toggle.IsOn = saved;
        loading = false;
    }

    private void OnThemeChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!loading && Theme.SelectedItem is ComboBoxItem { Tag: string tag } && Enum.TryParse<ElementTheme>(tag, out var theme))
        {
            App.Main?.SetTheme(theme);
            App.Settings.Theme = tag;
            try
            {
                App.Settings.Save();
                ShellStatus.Text = "";
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            {
                // The theme still holds until the app quits; say why it will not stick.
                ShellStatus.Text = error.Message;
            }
        }
    }

    private void OnScenarioChanged(object sender, SelectionChangedEventArgs e)
    {
        if (loading || App.Core is not FakeKetchCore fake)
        {
            return;
        }

        fake.ClearScripted();
        if (Scenario.SelectedIndex > 0)
        {
            var scenario = scenarios[Scenario.SelectedIndex - 1];
            fake.Script(scenario);
            ScenarioText.Text = scenario.Description;
        }
        else
        {
            ScenarioText.Text = "";
        }
    }
}
