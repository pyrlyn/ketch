// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// The Windows app's own preferences. An unpackaged app has no ApplicationData,
// and an MSIX virtualises it, so both forms read one file under
// %LOCALAPPDATA%\ketch. Tokens and ketch's own config stay in the CLI's
// config.toml; this file never holds them.

using System.Text.Json;
using System.Text.Json.Serialization;

namespace Ketch.AppCore;

/// <summary>What the user can switch off: the tray, notices, login and ketch links.</summary>
public sealed class AppSettings
{
    private static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    private readonly string? path;
    private readonly HashSet<string> notified = new(StringComparer.Ordinal);

    private AppSettings(string? path) => this.path = path;

    /// <summary>The notification-area icon. On until the user hides it.</summary>
    public bool ShowTray { get; set; } = true;

    /// <summary>Whether a new upgrade posts a system notification. Off until the user turns it on.</summary>
    public bool NotifiesOfUpdates { get; set; }

    /// <summary>Whether Windows starts the app at login.</summary>
    public bool OpenAtLogin { get; set; }

    /// <summary>Whether <c>ketch://</c> links open this app. On until the user turns it off.</summary>
    public bool HandleLinks { get; set; } = true;

    /// <summary><see cref="UpdateNotices.Key"/>s already announced.</summary>
    public IReadOnlySet<string> NotifiedUpgrades => notified;

    /// <summary><c>%LOCALAPPDATA%\ketch</c>, the directory both the MSIX and the unpackaged app can write.</summary>
    public static string DefaultDirectory() =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ketch");

    /// <summary>
    /// Reads <c>settings.json</c> from <paramref name="directory"/>, or the defaults
    /// when the file is missing or not this app's JSON. A broken file must not
    /// stop the app from opening.
    /// </summary>
    public static AppSettings Load(string directory)
    {
        var settings = new AppSettings(Path.Combine(directory, "settings.json"));
        if (!File.Exists(settings.path))
        {
            return settings;
        }

        try
        {
            var stored = JsonSerializer.Deserialize<Stored>(File.ReadAllText(settings.path), Json);
            if (stored is null)
            {
                return settings;
            }

            settings.ShowTray = stored.ShowTray;
            settings.NotifiesOfUpdates = stored.NotifiesOfUpdates;
            settings.OpenAtLogin = stored.OpenAtLogin;
            settings.HandleLinks = stored.HandleLinks;
            foreach (var key in stored.NotifiedUpgrades ?? [])
            {
                if (!string.IsNullOrEmpty(key))
                {
                    settings.notified.Add(key);
                }
            }
        }
        catch (JsonException)
        {
            // The next Save replaces the file. Until then the defaults apply.
        }
        catch (IOException)
        {
            // Same as a missing file: the app still runs, and the next Save retries.
        }

        return settings;
    }

    /// <summary>Remembers <paramref name="upgrades"/> so the next check stays quiet about them.</summary>
    public void Remember(IEnumerable<Upgrade> upgrades)
    {
        var added = false;
        foreach (var upgrade in upgrades)
        {
            added |= notified.Add(UpdateNotices.Key(upgrade));
        }

        if (added)
        {
            Save();
        }
    }

    /// <summary>Writes the file, replacing it. A failure leaves the in-memory values as they are.</summary>
    public void Save()
    {
        if (path is null)
        {
            return;
        }

        var directory = Path.GetDirectoryName(path);
        if (string.IsNullOrEmpty(directory))
        {
            return;
        }

        Directory.CreateDirectory(directory);
        var stored = new Stored
        {
            ShowTray = ShowTray,
            NotifiesOfUpdates = NotifiesOfUpdates,
            OpenAtLogin = OpenAtLogin,
            HandleLinks = HandleLinks,
            NotifiedUpgrades = [.. notified.Order(StringComparer.Ordinal)],
        };
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(stored, Json));
        File.Move(tmp, path, overwrite: true);
    }

    private sealed class Stored
    {
        public bool ShowTray { get; set; } = true;

        public bool NotifiesOfUpdates { get; set; }

        public bool OpenAtLogin { get; set; }

        public bool HandleLinks { get; set; } = true;

        public List<string>? NotifiedUpgrades { get; set; }
    }
}
