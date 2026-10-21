// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// The settings file round-trips, and a file that is not JSON does not stop
// the app from opening on the defaults.

using Ketch.AppCore;

namespace Ketch.AppCore.Tests;

[TestClass]
public sealed class AppSettingsTests
{
    [TestMethod]
    public void A_saved_file_loads_the_same_switches_and_announced_upgrades()
    {
        var dir = TempDir();
        try
        {
            var settings = AppSettings.Load(dir);
            Assert.IsTrue(settings.ShowTray);
            Assert.IsFalse(settings.NotifiesOfUpdates);
            Assert.IsTrue(settings.HandleLinks);
            Assert.AreEqual("Default", settings.Theme);

            settings.ShowTray = false;
            settings.Theme = "Dark";
            settings.NotifiesOfUpdates = true;
            settings.OpenAtLogin = true;
            settings.HandleLinks = false;
            settings.Remember([new Upgrade("ripgrep", "1", "2", null)]);
            settings.Save();

            var loaded = AppSettings.Load(dir);
            Assert.IsFalse(loaded.ShowTray);
            Assert.IsTrue(loaded.NotifiesOfUpdates);
            Assert.IsTrue(loaded.OpenAtLogin);
            Assert.IsFalse(loaded.HandleLinks);
            Assert.AreEqual("Dark", loaded.Theme);
            Assert.IsTrue(loaded.NotifiedUpgrades.Contains("ripgrep@2"));
        }
        finally
        {
            Directory.Delete(dir, true);
        }
    }

    [TestMethod]
    public void A_broken_file_loads_the_defaults()
    {
        var dir = TempDir();
        try
        {
            File.WriteAllText(Path.Combine(dir, "settings.json"), "{");

            var loaded = AppSettings.Load(dir);

            Assert.IsTrue(loaded.ShowTray);
            Assert.IsFalse(loaded.NotifiesOfUpdates);
            Assert.IsTrue(loaded.HandleLinks);
            Assert.AreEqual("Default", loaded.Theme);
        }
        finally
        {
            Directory.Delete(dir, true);
        }
    }

    private static string TempDir()
    {
        var dir = Path.Combine(Path.GetTempPath(), "ketch-settings-" + Guid.NewGuid().ToString("n"));
        Directory.CreateDirectory(dir);
        return dir;
    }
}
