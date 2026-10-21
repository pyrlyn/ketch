// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// Which upgrades count as new, and that the notice text cannot carry a
// control character from a release tag.

using Ketch.AppCore;

namespace Ketch.AppCore.Tests;

[TestClass]
public sealed class UpdateNoticesTests
{
    [TestMethod]
    public void Fresh_skips_held_packages_and_ones_already_announced()
    {
        var upgrades = new[]
        {
            new Upgrade("ripgrep", "14.1.0", "14.1.1", null),
            new Upgrade("jq", "1.7.1", "1.8.1", @"C:\work\ketch.lock"),
            new Upgrade("bat", "0.24.0", "0.25.0", null),
        };

        var fresh = UpdateNotices.Fresh(upgrades, new HashSet<string> { "bat@0.25.0" });

        CollectionAssert.AreEqual(new[] { "ripgrep" }, fresh.Select(u => u.Name).ToList());
    }

    [TestMethod]
    public void A_newer_version_of_an_announced_package_is_news_again()
    {
        var again = new Upgrade("ripgrep", "14.1.1", "14.2.0", null);

        var fresh = UpdateNotices.Fresh([again], new HashSet<string> { "ripgrep@14.1.1" });

        Assert.AreEqual(1, fresh.Count);
    }

    [TestMethod]
    public void The_message_lists_three_names_and_drops_control_characters()
    {
        var upgrades = new[]
        {
            new Upgrade("ripgrep", "1", "2\u0007", null),
            new Upgrade("fd", "1", "2", null),
            new Upgrade("bat", "1", "2\u202e", null),
            new Upgrade("jq", "1", "2", null),
        };

        var (title, body) = UpdateNotices.Message(upgrades);

        Assert.AreEqual("4 updates available", title);
        Assert.AreEqual("ripgrep 1 → 2\nfd 1 → 2\nbat 1 → 2\nand 1 more", body);
    }
}
