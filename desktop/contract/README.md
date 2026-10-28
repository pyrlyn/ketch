# Contract scenarios

Language-neutral JSON scenarios that a fake core replays, so the desktop app's
tests speak the records, event streams and errors the real core does and cannot
drift from it.

The files in `scenarios/` are copied unchanged from the Rust tree's
`desktop/contract/scenarios/`, where `crates/ketch-ffi/tests/contract.rs`
generates them from the values `ketch-ffi` hands a front end. They are never
edited here: when Rust regenerates them, copy them again.

`ketch-wire`'s `ScenarioTest` reads every file into the wire types
(`ketch.wire.Scenario`, the call's value through `resultSerializer`) and writes
it back, failing on a field that was renamed, dropped or added.

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

- `call`: the operation and its arguments, as the app sends it to
  `ketch serve` (`ketch.wire.Call`). A `search` with an empty `query` browses:
  every known package and no repositories (`search-browse`).
- `script`: what happens during the call, in order. A `type: "event"` step is
  something the core reports (`step`, `status`, `success`, `warn`, `note`,
  `debug`, `began`, `sized`, `progress`, `ended`, `abandoned`). A
  `type: "ask"` step is a question the core puts to the app (`choose_binary`,
  `stop_processes`); the `answer` shows what a person answered when the
  scenario was written, and a fake lets its test's decider answer instead.
- `outcome`: `type: "ok"` with the returned `value`, whose shape is the call's
  return type, or `type: "error"` with a `KetchError` (`busy` with an optional
  `pid`, `cancelled`, `not_found`, `network`, `verification`, `other`).

Field names are the Rust field names, in snake case. Absent values are `null`.
Task ids in events are unique within a scenario.

## What a fake does with one

A fake core reads the file for the call it is asked to make. A read returns
the `value`. A mutating call plays the `script` in order, handing events to
the reporter and questions to the decider, stops with `Cancelled` if the caller
cancels between steps, and then returns the `value` or throws the `error`.
`Busy` shows a banner with Retry, `Cancelled` is logged and not shown as an
alert, and every other error is an alert with the core's wording.
