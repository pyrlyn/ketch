// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// The one place that decides which core the app runs on: ketch-ffi on the
// ketch root the CLI uses, or the fake for working on the screens. Nothing
// else in the app names either.

using Ketch.AppCore;
using Ketch.FfiCore;

namespace KetchApp;

internal static class CoreFactory
{
    /// <summary>Set to 1 to run on the fake core, which replays the contract scenarios and never touches the disk.</summary>
    public const string FakeSwitch = "KETCH_APP_FAKE_CORE";

    /// <summary>
    /// The real core on `KETCH_ROOT`, or `~/.ketch` when it is unset, exactly
    /// as the CLI resolves it; point `KETCH_ROOT` at a scratch tree to try the
    /// app without touching your own.
    /// </summary>
    public static IKetchCore Create() =>
        Environment.GetEnvironmentVariable(FakeSwitch) == "1" ? FakeCoreSetup.Create() : new FfiKetchCore();
}
