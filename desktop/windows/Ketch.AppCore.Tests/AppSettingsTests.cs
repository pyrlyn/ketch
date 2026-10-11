// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// The settings file: what is saved reads back, and a missing or broken file
// is the defaults, never an error.

using Ketch.AppCore;

namespace Ketch.AppCore.Tests;

[TestClass]
public sealed class AppSettingsTests
{
    private string dir = "";

    private string File => Path.Combine(dir, "ketch", "app-settings.json");

    [TestInitialize]
    public void Create() =>
        dir = Path.Combine(Path.GetTempPath(), "ketch-app-settings-" + Guid.NewGuid().ToString("N"));

    [TestCleanup]
    public void Remove()
    {
        if (Directory.Exists(dir))
        {
            Directory.Delete(dir, recursive: true);
        }
    }

    [TestMethod]
    public void AMissingFileIsTheDefaults() =>
        Assert.AreEqual("Default", AppSettings.Load(File).Theme);

    [TestMethod]
    public void WhatIsSavedReadsBackAndTheFolderIsCreated()
    {
        Assert.IsTrue(new AppSettings { Theme = "Dark" }.Save(File));
        Assert.AreEqual("Dark", AppSettings.Load(File).Theme);
        Assert.IsFalse(System.IO.File.Exists(File + ".tmp"));

        Assert.IsTrue(new AppSettings { Theme = "Light" }.Save(File));
        Assert.AreEqual("Light", AppSettings.Load(File).Theme);
    }

    [TestMethod]
    public void ABrokenFileIsTheDefaults()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(File)!);
        System.IO.File.WriteAllText(File, "{ not json");
        Assert.AreEqual("Default", AppSettings.Load(File).Theme);
    }

    [TestMethod]
    public void AFileThatCannotBeWrittenIsReportedNotThrown()
    {
        // A directory where the file should be: the move cannot replace it.
        Directory.CreateDirectory(File);
        Assert.IsFalse(new AppSettings { Theme = "Dark" }.Save(File));
    }
}
