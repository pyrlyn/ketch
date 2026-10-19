// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// The app's `IKetchCore` on `ketch-ffi`, through the C# binding D10 generates.
//
// Records, events and errors go through the wire classes and the event mapper
// in `ContractScenario.cs`, the ones the fake replays the contract scenarios
// with: the scenarios are those same Rust values serialised, so one mapping
// serves both cores and the fake cannot drift from what the live core shows.
//
// Threading is the store's: every method here blocks, `KetchStore` runs each
// call on its own thread and posts what comes back to the UI thread.

using Ketch.AppCore;
using Ffi = Ketch.Ffi;

namespace Ketch.FfiCore;

public sealed class FfiKetchCore : IKetchCore, IDisposable
{
    /// <summary>
    /// What a search asks for. The registry's matches come first; what is left
    /// goes to GitHub's search, which is rate limited, so a page and no more.
    /// </summary>
    private const uint SearchLimit = 30;

    private readonly Ffi.KetchCore core;

    /// <param name="root">The ketch root; null is `KETCH_ROOT` or `~/.ketch`, the tree the CLI uses.</param>
    public FfiKetchCore(string? root = null)
    {
        core = new Ffi.KetchCore(root);
        try
        {
            Root = core.Root();
        }
        catch (Ffi.KetchException)
        {
            // Every call resolves the root again and fails with the reason,
            // where the store shows it; the window still opens.
            Root = root ?? "";
        }
    }

    public string Root { get; }

    public IReadOnlyList<InstalledPackage> Installed() =>
        Read(() => core.Installed().Select(AsInstalled).ToList());

    public IReadOnlyList<RegistryPackage> Search(string query) =>
        Read(() =>
        {
            // An empty query browses the registry and the built-ins. The core
            // does that without asking a source, so Discover's first paint is
            // a shelf rather than an alert.
            var found = core.Search(query, SearchLimit, null);
            var known = found.Known.Select(p =>
                new ContractScenario.RegistryEntry
                {
                    Name = p.Name,
                    Source = p.Source,
                    Description = p.Description,
                    Latest = p.Latest,
                });
            // A repository is installed by its spec, so the spec is its name.
            var repositories = found.Repositories.Select(r =>
                new ContractScenario.RegistryEntry
                {
                    Name = r.Spec,
                    Source = r.Spec,
                    Description = r.Description,
                });
            return known.Concat(repositories).Select(e => e.AsRegistryPackage()).ToList();
        });

    public IReadOnlyList<Upgrade> Outdated() =>
        Read(() => core.Outdated(null).Select(u => new ContractScenario.UpgradeEntry
        {
            Name = u.Name,
            Installed = u.Installed,
            Latest = u.Latest,
            Pinned = u.Pinned,
            HeldBy = u.HeldBy,
        }.AsUpgrade()).ToList());

    public void Install(string spec, InstallOptions options, IReporter reporter, IDecider decider, CancelToken cancel) =>
        Operate(reporter, decider, cancel, (r, d, token) => core.Install(
            [spec],
            new Ffi.InstallOptions(Force: options.Force, Prerelease: options.IncludePrereleases),
            r,
            d,
            token));

    public void Upgrade(IReadOnlyList<string> names, IReporter reporter, IDecider decider, CancelToken cancel) =>
        Operate(reporter, decider, cancel, (r, d, token) => core.Upgrade([.. names], r, d, token));

    public void Uninstall(IReadOnlyList<string> names, IReporter reporter, CancelToken cancel) =>
        Operate(reporter, null, cancel, (r, d, token) => core.Uninstall([.. names], r, d, token));

    public string Changelog(string name, string? from, string? to) =>
        Read(() =>
        {
            var range = core.ChangelogRange(name, from, to, null);
            if (range.Length > 0)
            {
                return ContractScenario.Markdown(range.Select(AsEntry));
            }

            // Nothing newer than `from`: an up-to-date package. Its own notes
            // are the useful answer, and with no version the core reads the
            // installed one's, from the payload's changelog file when it has one.
            return ContractScenario.Markdown([AsEntry(core.Changelog(name, null, null))]);
        });

    public IReadOnlyList<Finding> Doctor() =>
        Read(() => core.Doctor(null).Select(c => new ContractScenario.CheckEntry
        {
            Name = c.Name,
            Outcome = c.Outcome switch
            {
                Ffi.CheckOutcome.Ok => "ok",
                Ffi.CheckOutcome.Warn => "warn",
                _ => "fail",
            },
            Detail = c.Detail,
            Fix = c.Fix,
        }.AsFinding()).ToList());

    public void Dispose() => core.Dispose();

    /// <summary>The binding's error as the app's, with the wording the fake gives the same error.</summary>
    internal static KetchException Map(Ffi.KetchException error) => (error switch
    {
        Ffi.KetchException.Busy busy => new ContractScenario.WireError { Type = "busy", Pid = busy.pid },
        Ffi.KetchException.Cancelled => new ContractScenario.WireError { Type = "cancelled" },
        Ffi.KetchException.NotFound missing => new ContractScenario.WireError { Type = "not_found", Name = missing.name },
        Ffi.KetchException.Network network => new ContractScenario.WireError { Type = "network", Message = network.message },
        Ffi.KetchException.Verification failed => new ContractScenario.WireError { Type = "verification", Message = failed.message },
        Ffi.KetchException.Other other => new ContractScenario.WireError { Type = "other", Message = other.message },
        _ => new ContractScenario.WireError { Type = "other", Message = error.Message },
    }).AsException();

    private static T Read<T>(Func<T> body)
    {
        try
        {
            return body();
        }
        catch (Ffi.KetchException error)
        {
            throw Map(error);
        }
    }

    /// <summary>
    /// Runs a mutating call with its own FFI token, cancelled when the app's
    /// is. A failure once the token fired is reported as the cancel it is: a
    /// declined question can surface as the core's ambiguity error, which the
    /// person, having pressed Cancel, should not see as an alert.
    /// </summary>
    private static void Operate(
        IReporter reporter,
        IDecider? decider,
        CancelToken cancel,
        Action<Ffi.Reporter, Ffi.Decider?, Ffi.CancelToken> body)
    {
        var token = new Ffi.CancelToken();
        var gate = new object();
        var finished = false;
        // The app's token outlives the call; a cancel after it must not reach
        // the FFI token once that is disposed.
        cancel.OnCancel(() =>
        {
            lock (gate)
            {
                if (!finished)
                {
                    token.Cancel();
                }
            }
        });

        try
        {
            body(new ForeignReporter(reporter), decider is null ? null : new ForeignDecider(decider, token), token);
        }
        catch (Ffi.KetchException error)
        {
            throw token.IsCancelled() ? KetchException.Cancelled() : Map(error);
        }
        finally
        {
            lock (gate)
            {
                finished = true;
            }

            token.Dispose();
        }
    }

    private static InstalledPackage AsInstalled(Ffi.Package package) =>
        new ContractScenario.Package
        {
            Name = package.Name,
            Version = package.Version,
            Source = package.Source,
            Prefix = package.Prefix,
        }.AsInstalled();

    private static ContractScenario.ChangelogEntry AsEntry(Ffi.Changelog entry) =>
        new() { Version = entry.Version, Heading = entry.Heading, Body = entry.Body };

    /// <summary>The binding's event in the scenarios' wire shape, which the event mapper reads.</summary>
    internal static ContractScenario.Event AsWire(Ffi.Event coreEvent) => coreEvent switch
    {
        Ffi.Event.Step step => new() { Type = "step", Package = step.Package, Stage = step.Stage.ToString().ToLowerInvariant() },
        Ffi.Event.Status status => new() { Type = "status", Verb = status.Verb, Detail = status.Detail },
        Ffi.Event.Success success => new() { Type = "success", Verb = success.Verb, Detail = success.Detail },
        Ffi.Event.Warn warn => new() { Type = "warn", Detail = warn.Detail },
        Ffi.Event.Note note => new() { Type = "note", Detail = note.Detail },
        Ffi.Event.Debug debug => new() { Type = "debug", Detail = debug.Detail },
        Ffi.Event.Began began => new() { Type = "began", Id = began.Id, Task = AsWire(began.Task) },
        Ffi.Event.Sized sized => new() { Type = "sized", Id = sized.Id, Total = sized.Total },
        Ffi.Event.Progress progress => new() { Type = "progress", Id = progress.Id, Done = progress.Done, Total = progress.Total },
        Ffi.Event.Ended ended => new() { Type = "ended", Id = ended.Id },
        Ffi.Event.Abandoned abandoned => new() { Type = "abandoned", Id = abandoned.Id },
        _ => new() { Type = "unknown" },
    };

    private static ContractScenario.TaskKind AsWire(Ffi.TaskKind task) => task switch
    {
        Ffi.TaskKind.Download download => new() { Type = "download", Label = download.Label },
        Ffi.TaskKind.Activity => new() { Type = "activity" },
        Ffi.TaskKind.Counter => new() { Type = "counter" },
        _ => new() { Type = "batch" },
    };

    /// <summary>
    /// The app's reporter as the binding's. The core reports from several
    /// threads during a batch and the mapper remembers task ids, so one lock
    /// keeps both its state and the order events reach the app.
    /// </summary>
    internal sealed class ForeignReporter(IReporter reporter) : Ffi.Reporter
    {
        private readonly ContractEventMapper mapper = new();

        public void Event(Ffi.Event coreEvent)
        {
            lock (mapper)
            {
                foreach (var mapped in mapper.Map(AsWire(coreEvent)))
                {
                    reporter.Event(mapped);
                }
            }
        }
    }

    /// <summary>
    /// The app's decider as the binding's. A binary choice or a stop question
    /// whose only other button is Cancel declines by cancelling the operation,
    /// as the fake does, rather than leaving the core to its fixed rules. A
    /// leftover folder has no dialog yet: declining keeps it, and does not
    /// cancel the uninstall the person asked for.
    /// </summary>
    internal sealed class ForeignDecider(IDecider decider, Ffi.CancelToken token) : Ffi.Decider
    {
        public uint? ChooseBinary(string package, string[] candidates)
        {
            if (decider.ChooseBinary(package, candidates) is { } pick && pick >= 0 && pick < candidates.Length)
            {
                return (uint)pick;
            }

            token.Cancel();
            return null;
        }

        public bool StopProcesses(Ffi.Holder[] holders)
        {
            if (decider.StopProcesses([.. holders.Select(h => new ProcessHolder(h.Pid, h.Path))]))
            {
                return true;
            }

            token.Cancel();
            return false;
        }

        public bool ConfirmRemoveLeftover(string path) => false;
    }
}
