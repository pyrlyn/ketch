// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// The app's own preferences, in a JSON file. Not `ApplicationData`: the app
// also ships unpackaged (D14), and an unpackaged app has none. Anything ketch
// itself is configured by stays in the CLI's config.toml, and no token is
// ever stored here.

using System.Text.Json;

namespace Ketch.AppCore;

public sealed class AppSettings
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    /// <summary>`Default`, `Light` or `Dark`, as WinUI's `ElementTheme` names them.</summary>
    public string Theme { get; set; } = "Default";

    /// <summary>
    /// `%LOCALAPPDATA%\ketch\app-settings.json`. Beside the ketch root, not in
    /// it, so `ketch self uninstall` never meets a file it did not create. An
    /// MSIX's writes under `%LOCALAPPDATA%` are redirected into its package
    /// data by Windows and read back from there, so the same path serves both
    /// forms of the app.
    /// </summary>
    public static string DefaultPath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ketch", "app-settings.json");

    /// <summary>The settings in <paramref name="path"/>; the defaults when it is missing or unreadable.</summary>
    public static AppSettings Load(string path)
    {
        try
        {
            return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(path), Json) ?? new AppSettings();
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException)
        {
            return new AppSettings();
        }
    }

    /// <summary>
    /// Writes the settings to <paramref name="path"/>, whole or not at all.
    /// False when it could not: a preference is not worth an error dialog,
    /// and the choice still holds until the app quits.
    /// </summary>
    public bool Save(string path)
    {
        var temporary = path + ".tmp";
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
            File.WriteAllText(temporary, JsonSerializer.Serialize(this, Json));
            File.Move(temporary, path, overwrite: true);
            return true;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }
}
