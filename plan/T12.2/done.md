# T12.2. Done

`desktop/src/main/kotlin/ketch/desktop/store/`: `StoreState`, one immutable snapshot behind a
`StateFlow`, and `KetchStore` on `Core`, ported from the macOS and Windows stores.

- Reads: installed and outdated (`updates` and `held`, held being `pinned` or `held_by`),
  search with only the newest answer shown, search after a 250 ms typing pause, changelog (one
  version or a range, as `Changelog` records; rendering is the screens' job) and doctor.
- Install, upgrade and uninstall run one at a time; a second is refused with an alert, as the
  macOS app does. Per-package stage and download progress: a `progress` event reaches its
  package through the download task its `began` opened.
- The core's events and questions are queued to the operation's coroutine, so they land in
  order and before the call's result. Cancel declines the open question and anything asked
  after it. The binary-choice, stop and leftover questions are one `pending` answered with the
  wire's `Answer`.
- Busy is a banner with Retry, cancelled is a log line only, every other error is an alert in
  the core's wording. The log keeps 500 entries.
- `FakeCore` answers `outdated` with nothing newer until a scenario says otherwise, since the
  store reads it after every operation.

Not here, as planned: settings and the prerelease setting (T12.4), `ketch://` links and
changelog rendering (T12.3).

Commits: `4812851 T12.2: claim and plan`, `dc961d4 T12.2: the store on Core`.

Checked: `./gradlew :desktop:build` green. 31 new tests on kotlinx-coroutines-test
(`ContractScenarioTest` 17, `KetchStoreTest` 14) next to the 14 from T12.1. They cover the
macOS and Windows contract tests and the store tests that apply to the scenario fake. Tests
written for the old hand-made fake's sample registry now use the scenarios. The search and
changelog timing tests use a small routing core with virtual time.

Reuse: nothing in the project or in `packages/` held app state. The store builds on T12.1's
`Core`, `describe` and `CancelToken` and on ketch-wire's `decline`.
