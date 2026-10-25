# Contract scenarios

Language-neutral JSON scenarios that every app's fake core replays, so the
macOS, Windows and Linux fakes speak the records, event streams and errors the
real core does and cannot drift from it or from each other.

The files in `scenarios/` are generated, never edited. They are built from the
Rust values `ketch-ffi` hands a front end, in
`crates/ketch-ffi/tests/contract.rs`. That test fails when a file is stale or
has no generator; regenerate with:

```bash
KETCH_BLESS=1 cargo nextest run -p ketch-ffi contract
```

## A scenario

```json
{
  "name": "install-binary-choice",
  "description": "What the scenario shows.",
  "call": { "operation": "install", "specs": ["uv"], "options": { "...": "..." } },
  "script": [ { "type": "event", "event": { "type": "step", "package": "uv", "stage": "resolving" } } ],
  "outcome": { "type": "ok", "value": [ "..." ] }
}
```

- `call`: the operation and the arguments the app passes. `operation` is one
  of `installed`, `search`, `outdated`, `install`, `upgrade`, `uninstall`,
  `changelog_range`, `doctor`. A `search` with an empty `query` browses: every
  known package and no repositories (`search-browse`).
- `script`: what happens during the call, in order. A `type: "event"` step is
  something the core reports (`step`, `status`, `success`, `warn`, `note`,
  `debug`, `began`, `sized`, `progress`, `ended`, `abandoned`). A
  `type: "ask"` step is a question the core puts to the app (`choose_binary`,
  `stop_processes`); the `answer` shows what a person answered when the
  scenario was written, and a fake lets its test's decider answer instead.
- `outcome`: `type: "ok"` with the returned `value`, whose shape is the call's
  return type in `ketch-ffi` (`Vec<Package>`, `SearchResults`, `Vec<Upgrade>`,
  `Vec<Installed>`, `Vec<Changelog>`, `Vec<Check>`), or `type: "error"` with a
  `KetchError` (`busy` with an optional `pid`, `cancelled`, `not_found`,
  `network`, `verification`, `already_installed`, `update_available`,
  `pinned`, `no_compatible_asset`, `source_conflict`, `rate_limited`,
  `other`). The install conflicts carry the fields an app needs to offer
  Update, Unpin or a rename; `rate_limited` is not a `network` failure.

Field names are the Rust field names, in snake case. `Option` fields are
`null` when absent. Task ids in events are unique within a scenario.

## What a fake does with one

A fake core reads the file for the call it is asked to make. A read returns
the `value`. A mutating call plays the `script` in order, handing events to
the reporter and questions to the decider, stops with `Cancelled` if the caller
cancels between steps, and then returns the `value` or throws the `error`.
`Busy` shows a banner with Retry, `Cancelled` is logged and not shown as an
alert, and every other error is an alert with the core's wording.

The macOS app's fake is `desktop/macos/Ketch/Core/FakeKetchCore.swift`
(decoding in `ContractScenario.swift`), tested by
`desktop/macos/KetchTests/ContractScenarioTests.swift`.

The Windows app's fake is `desktop/windows/Ketch.AppCore/FakeKetchCore.cs`
(decoding in `ContractScenario.cs`), tested by
`desktop/windows/Ketch.AppCore.Tests`. The app runs on the real core through
`desktop/windows/Ketch.FfiCore/FfiKetchCore.cs`, which maps the binding's
records, events and errors through the same `ContractScenario.cs` classes, so
the fake and the live core show the same things. Under
`KETCH_APP_FAKE_CORE=1` it runs on the fake instead: the scenarios ship beside
its binary, and Settings can play one for the next install, upgrade or
uninstall.
The Linux app's fake is `desktop/linux/src/fake-core.vala` (decoding in
`scenario.vala`), tested by `desktop/linux/tests/fake-core-test.vala`. It keeps
the scenarios as JSON and answers in ketch-capi's envelopes. An empty search
browses (`search-browse`); a declined question goes on as the core does.
