// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// Which upgrades are worth a notification, and what it says. The rule is pure
// so a test can pin it; the store decides when to ask and the WinUI shell owns
// AppNotificationManager. Same rule as the macOS app's UpdateNotices.

using System.Globalization;

namespace Ketch.AppCore;

/// <summary>Posts one notice. The Windows shell sends a toast; tests record the text.</summary>
public interface IUpdateNotifier
{
    void Post(string title, string body);
}

/// <summary>Which upgrades are new, and the text of the one notice that covers them.</summary>
public static class UpdateNotices
{
    /// <summary>
    /// An upgrade is identified by its package and target version: a package
    /// that moves on to a newer release is news again, one that stays put is not.
    /// </summary>
    public static string Key(Upgrade upgrade) => $"{upgrade.Name}@{upgrade.To}";

    /// <summary>
    /// The upgrades <paramref name="notified"/> has not announced yet. Held
    /// packages never count: <c>upgrade</c> would leave them where they are.
    /// </summary>
    public static List<Upgrade> Fresh(IEnumerable<Upgrade> upgrades, IReadOnlySet<string> notified) =>
        [.. upgrades.Where(upgrade => upgrade.HeldBy is null && !notified.Contains(Key(upgrade)))];

    /// <summary>
    /// The notification text. Versions come from release tags, which a
    /// project's author wrote, so control and format characters are dropped
    /// before the text reaches the system.
    /// </summary>
    public static (string Title, string Body) Message(IReadOnlyList<Upgrade> upgrades, int listed = 3)
    {
        var title = upgrades.Count == 1 ? "1 update available" : $"{upgrades.Count} updates available";
        var lines = upgrades.Take(listed).Select(upgrade => Printable($"{upgrade.Name} {upgrade.From} → {upgrade.To}")).ToList();
        if (upgrades.Count > listed)
        {
            lines.Add($"and {upgrades.Count - listed} more");
        }

        return (title, string.Join('\n', lines));
    }

    /// <summary>Drops control characters and format characters such as the bidi overrides.</summary>
    public static string Printable(string text) =>
        new([.. text.Where(static c => !char.IsControl(c) && char.GetUnicodeCategory(c) != UnicodeCategory.Format)]);
}
