// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// FfiKetchCore as the app's store drives it: the real core through the
// binding, on a scratch root and a `local:` payload so nothing touches the
// network or ~/.ketch, and the mappings it makes onto the app's types.

using Ketch.AppCore;
using Ketch.AppCore.Tests;
using Ketch.FfiCore;
using Ffi = Ketch.Ffi;

namespace Ketch.FfiCore.Tests;

[TestClass]
public sealed class FfiKetchCoreTests
{
    /// <summary>A throwaway directory holding the root and a one-file local package.</summary>
    private sealed class Scratch : IDisposable
    {
        public string Dir { get; } =
            Path.Combine(Path.GetTempPath(), "ketch-ffi-core-tests-" + Guid.NewGuid().ToString("N"));

        public string Root => Path.Combine(Dir, "root");

        public string Payload()
        {
            var file = Path.Combine(Dir, "payload", "hello");
            Directory.CreateDirectory(Path.GetDirectoryName(file)!);
            File.WriteAllText(file, "#!/bin/sh\necho hi\n");
            if (!OperatingSystem.IsWindows())
            {
                File.SetUnixFileMode(
                    file,
                    UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
            }

            return "local:" + file;
        }

        public void Dispose()
        {
            if (Directory.Exists(Dir))
            {
                Directory.Delete(Dir, recursive: true);
            }
        }
    }

    /// <summary>Holds the first event it gets until released, keeping the core inside the call that sent it.</summary>
    private sealed class BlockingReporter : IReporter, IDisposable
    {
        public ManualResetEventSlim Entered { get; } = new();

        public ManualResetEventSlim Release { get; } = new();

        public void Event(CoreEvent coreEvent)
        {
            Entered.Set();
            Release.Wait(TimeSpan.FromSeconds(30));
        }

        public void Dispose()
        {
            Release.Set();
            Entered.Dispose();
            Release.Dispose();
        }
    }

    private static string Clean(string path) =>
        path.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);

    [TestMethod]
    public void AScratchRootReadsAsEmptyAndAnEmptySearchBrowses()
    {
        using var scratch = new Scratch();
        using var core = new FfiKetchCore(scratch.Root);
        Assert.AreEqual(Clean(scratch.Root), Clean(core.Root));
        Assert.AreEqual(0, core.Installed().Count);
        // Whitespace is an empty query: the built-in shelf, and no source is asked.
        Assert.IsTrue(core.Search("  ").Count > 0);
        var findings = core.Doctor();
        Assert.IsTrue(findings.Count > 0);
        Assert.IsTrue(findings.All(f => f.Id.Length > 0 && f.Message.Length > 0));
    }

    [TestMethod]
    public void AnInstallReportsTheAppsStagesAndAnUninstallTakesItBack()
    {
        using var scratch = new Scratch();
        using var core = new FfiKetchCore(scratch.Root);
        var reporter = new RecordingReporter();
        var cancel = new CancelToken();
        core.Install(scratch.Payload(), new InstallOptions(), reporter, new ScriptedDecider(), cancel);

        var package = core.Installed().Single();
        Assert.AreEqual("local", package.Source);
        Assert.IsNull(package.Repo);
        Assert.IsFalse(string.IsNullOrEmpty(package.Path));
        var stages = reporter.Events.OfType<CoreEvent.Step>().Select(s => s.Stage).ToList();
        CollectionAssert.Contains(stages, Stage.Link);

        // The app's token outlives the call; cancelling it now reaches no
        // disposed FFI token.
        cancel.Cancel();

        core.Uninstall([package.Name], reporter, new CancelToken());
        Assert.AreEqual(0, core.Installed().Count);
    }

    [TestMethod]
    public void ACancelledInstallIsCancelledAndPlacesNothing()
    {
        using var scratch = new Scratch();
        using var core = new FfiKetchCore(scratch.Root);
        var cancel = new CancelToken();
        cancel.Cancel();
        var error = Assert.ThrowsExactly<KetchException>(() =>
            core.Install(scratch.Payload(), new InstallOptions(), new RecordingReporter(), new ScriptedDecider(), cancel));
        Assert.AreEqual(KetchErrorKind.Cancelled, error.Kind);
        Assert.AreEqual(0, core.Installed().Count);
    }

    [TestMethod]
    public void UninstallingAnUnknownNameIsNotFound()
    {
        using var scratch = new Scratch();
        using var core = new FfiKetchCore(scratch.Root);
        var error = Assert.ThrowsExactly<KetchException>(() =>
            core.Uninstall(["ghost"], new RecordingReporter(), new CancelToken()));
        Assert.AreEqual(KetchErrorKind.NotFound, error.Kind);
        Assert.AreEqual(KetchException.NotFound("ghost").Message, error.Message);
    }

    [TestMethod]
    public async Task AMutationWhileAnotherHoldsTheLockIsBusy()
    {
        using var scratch = new Scratch();
        using var first = new FfiKetchCore(scratch.Root);
        using var second = new FfiKetchCore(scratch.Root);
        using var blocking = new BlockingReporter();
        var install = Task.Run(() =>
            first.Install(scratch.Payload(), new InstallOptions(), blocking, new ScriptedDecider(), new CancelToken()));
        Assert.IsTrue(blocking.Entered.Wait(TimeSpan.FromSeconds(30)), "the install never reported");

        var error = Assert.ThrowsExactly<KetchException>(() =>
            second.Uninstall(["hello"], new RecordingReporter(), new CancelToken()));
        Assert.AreEqual(KetchErrorKind.Busy, error.Kind);
        StringAssert.StartsWith(error.Message, "ketch is running in another process");

        blocking.Release.Set();
        await install;
        Assert.AreEqual(1, first.Installed().Count);
    }

    [TestMethod]
    public void EveryCoreErrorReadsAsTheFakeWordsIt()
    {
        var cases = new (Ffi.KetchException From, KetchException To)[]
        {
            (new Ffi.KetchException.Busy(42), KetchException.Busy(42)),
            (new Ffi.KetchException.Busy(null), KetchException.Busy(null)),
            (new Ffi.KetchException.Cancelled(), KetchException.Cancelled()),
            (new Ffi.KetchException.NotFound("rg"), KetchException.NotFound("rg")),
            (new Ffi.KetchException.Network("offline"), KetchException.Network("offline")),
            (new Ffi.KetchException.Verification("bad sum"), KetchException.Verification("bad sum")),
            (new Ffi.KetchException.Other("odd"), KetchException.Other("odd")),
        };
        foreach (var (from, to) in cases)
        {
            var mapped = FfiKetchCore.Map(from);
            Assert.AreEqual(to.Kind, mapped.Kind);
            Assert.AreEqual(to.Message, mapped.Message);
            Assert.AreEqual(to.Pid, mapped.Pid);
        }
    }

    [TestMethod]
    public void ADownloadsProgressIsKeyedByItsPackageAndStagesAreTheAppsCoarserOnes()
    {
        var recorder = new RecordingReporter();
        var reporter = new FfiKetchCore.ForeignReporter(recorder);
        reporter.Event(new Ffi.Event.Step("rg", Ffi.Stage.Trusting));
        reporter.Event(new Ffi.Event.Began(7, new Ffi.TaskKind.Download("rg", null)));
        reporter.Event(new Ffi.Event.Progress(7, 5, 10));
        reporter.Event(new Ffi.Event.Ended(7, null));
        reporter.Event(new Ffi.Event.Progress(7, 9, 10));
        reporter.Event(new Ffi.Event.Success("installed", "rg 14.1.1"));
        reporter.Event(new Ffi.Event.Debug("noise"));

        CollectionAssert.AreEqual(
            new CoreEvent[]
            {
                new CoreEvent.Step("rg", Stage.Verify),
                new CoreEvent.Progress("rg", 5, 10),
                new CoreEvent.Status("installed rg 14.1.1"),
            },
            recorder.Events.ToArray());
    }

    [TestMethod]
    public void AnAnsweredQuestionPassesThroughAndADeclinedOneCancels()
    {
        using var token = new Ffi.CancelToken();
        var picks = new FfiKetchCore.ForeignDecider(new ScriptedDecider(binary: 1), token);
        Assert.AreEqual(1u, picks.ChooseBinary("uv", ["uv", "uvx"]));
        Assert.IsTrue(picks.StopProcesses([new Ffi.Holder(5150, "/bin/uv")]));
        Assert.IsFalse(picks.ConfirmRemoveLeftover("/tmp/leftover"));
        Assert.IsFalse(token.IsCancelled());

        foreach (var declined in new IDecider[] { new ScriptedDecider(binary: null), new ScriptedDecider(binary: 5) })
        {
            using var cancelled = new Ffi.CancelToken();
            Assert.IsNull(new FfiKetchCore.ForeignDecider(declined, cancelled).ChooseBinary("uv", ["uv", "uvx"]));
            Assert.IsTrue(cancelled.IsCancelled());
        }

        using var refused = new Ffi.CancelToken();
        Assert.IsFalse(new FfiKetchCore.ForeignDecider(new ScriptedDecider(stop: false), refused)
            .StopProcesses([new Ffi.Holder(5150, "/bin/uv")]));
        Assert.IsTrue(refused.IsCancelled());
    }
}
