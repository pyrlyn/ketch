// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// A ketch:// link only opens a package page, and a refresh posts one notice
// for the upgrades that are actually new.

using Ketch.AppCore;

namespace Ketch.AppCore.Tests;

[TestClass]
public sealed class LinkAndNoticeTests
{
    [TestMethod]
    public async Task A_package_link_opens_that_page_and_installs_nothing()
    {
        var core = new FakeKetchCore();
        var store = new KetchStore(core);

        await store.OpenLinkAsync("ketch://package/zoxide");

        Assert.AreEqual("zoxide", store.LinkedPackage);
        Assert.IsNull(store.ErrorMessage);
        Assert.IsFalse(core.Calls.Any(call => call.StartsWith("install", StringComparison.Ordinal)));
    }

    [TestMethod]
    public async Task An_install_link_is_refused_and_opens_nothing()
    {
        var core = new FakeKetchCore();
        var store = new KetchStore(core);

        await store.OpenLinkAsync("ketch://install/ripgrep");

        Assert.IsNull(store.LinkedPackage);
        Assert.AreEqual("ketch link is not a package page", store.ErrorMessage);
        Assert.IsFalse(core.Calls.Any(call => call.StartsWith("install", StringComparison.Ordinal)));
    }

    [TestMethod]
    public async Task An_unknown_package_link_is_an_alert()
    {
        var store = new KetchStore(new FakeKetchCore());

        await store.OpenLinkAsync("ketch://package/nope");

        Assert.IsNull(store.LinkedPackage);
        Assert.AreEqual("No package named nope.", store.ErrorMessage);
    }

    [TestMethod]
    public async Task Refresh_posts_one_notice_for_new_upgrades_and_stays_quiet_the_next_time()
    {
        var dir = Path.Combine(Path.GetTempPath(), "ketch-notices-" + Guid.NewGuid().ToString("n"));
        Directory.CreateDirectory(dir);
        try
        {
            var settings = AppSettings.Load(dir);
            settings.NotifiesOfUpdates = true;
            var notices = new RecordingNotifier();
            var store = new KetchStore(new FakeKetchCore(), settings: settings, notifier: notices);

            await store.RefreshAsync();

            Assert.AreEqual(1, notices.Posts.Count);
            Assert.AreEqual("2 updates available", notices.Posts[0].Title);
            Assert.AreEqual("ripgrep 14.1.0 → 14.1.1\nbat 0.24.0 → 0.25.0", notices.Posts[0].Body);

            await store.RefreshAsync();

            Assert.AreEqual(1, notices.Posts.Count);
        }
        finally
        {
            Directory.Delete(dir, true);
        }
    }

    [TestMethod]
    public async Task Notices_stay_off_until_the_user_turns_them_on()
    {
        var dir = Path.Combine(Path.GetTempPath(), "ketch-notices-off-" + Guid.NewGuid().ToString("n"));
        try
        {
            var settings = AppSettings.Load(dir);
            var notices = new RecordingNotifier();
            var store = new KetchStore(new FakeKetchCore(), settings: settings, notifier: notices);

            await store.RefreshAsync();
            Assert.AreEqual(0, notices.Posts.Count);
            Assert.IsNull(store.RequestedSection);

            store.SetNotifies(true);

            Assert.AreEqual(1, notices.Posts.Count);
            Assert.IsTrue(AppSettings.Load(dir).NotifiesOfUpdates);
            store.OpenUpdates();
            Assert.AreEqual("updates", store.RequestedSection);
            store.ClearRequestedSection();
            Assert.IsNull(store.RequestedSection);
        }
        finally
        {
            if (Directory.Exists(dir))
            {
                Directory.Delete(dir, true);
            }
        }
    }

    private sealed class RecordingNotifier : IUpdateNotifier
    {
        public List<(string Title, string Body)> Posts { get; } = [];

        public void Post(string title, string body) => Posts.Add((title, body));
    }
}
