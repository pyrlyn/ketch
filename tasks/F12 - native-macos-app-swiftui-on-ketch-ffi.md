---
id: F12
title: Native macOS app (SwiftUI) on `ketch-ffi`
status: in-progress
created_at: 1792583218
updated_at: 1792583218
priority: 3
---

A SwiftUI app in the repository (e.g. `desktop/macos/`), consuming R9's XCFramework and Swift package: installed and registry packages (list, search), install, upgrade, uninstall, changelog, doctor, with progress from the reporter callback and dialogs for the decider. It manages the same ketch root as the CLI and respects the same lock (R8).

Decided (creator, 2026-09-30): the app shares the ketch root (`~/.ketch`, or `KETCH_ROOT`) with the CLI — one state, one lock, one store. Also decided: a menu-bar extra is in scope (status and pending upgrades at a glance, quick actions), and the app follows ketch's triple licence (GPL-3.0-only, royalty-free, commercial). Minimum macOS version: 26 (the deployment target of the app and of R9's XCFramework).

Design (creator, 2026-10-01): the UI is frosted (matte) or glossy glass with a 3D effect. On macOS 26 that means the native Liquid Glass material (SwiftUI `.glassEffect`, `GlassEffectContainer`), with depth from layering, shadows and specular highlights — system materials, not a hand-drawn imitation, so it follows accessibility settings (Reduce Transparency, Increase Contrast).

Plan:
1. Project: `desktop/macos/` with the app target described in a text spec (XcodeGen or Tuist — pick at start with a sourced comparison, add to `toolchain.md`) so the project is reviewable in diffs; bundle id under the creator's team, deployment target macOS 26, Swift 6 strict concurrency, depends on the local `KetchCore` package from R9. `AGENTS.md` layout table gets the new paths.
2. Architecture: one `@Observable` `KetchStore` on the main actor owning the state; every core call runs in `Task.detached` and reports back through the `Reporter` callback hopped to the main actor. A `KetchCoreProtocol` wraps the FFI object so view models are testable with a fake.
3. Main window (`NavigationSplitView`): Installed (name, version, source, update badge; upgrade, uninstall, reveal in Finder), Discover (registry search, install), package detail (description, versions, changelog rendered from sanitized Markdown via `AttributedString(markdown:)`, release link), Activity (current operation with per-package progress and a log, Cancel button), Doctor (findings with fix actions the core offers).
4. Decisions: the binary choice becomes a sheet listing candidates; confirmations for uninstall/upgrade-all are app dialogs (the CLI's `cmd/` confirmations, re-done in the frontend per R7).
5. Menu bar: `MenuBarExtra` with the number of pending upgrades, "Upgrade all", the running operation's progress, "Open ketch", "Quit"; an update check on launch and on a timer while running (interval in Settings). Optional "Open at login" via `SMAppService.mainApp`.
6. Busy and shared root: a `Busy` error shows "ketch is running in another process (pid N)" with Retry; the app re-reads state when the window becomes key, so installs made from the CLI show up. The root shown in Settings (`KETCH_ROOT` honoured when set in the app's environment).
7. Settings: update-check interval, include prereleases, GitHub token presence (read-only note pointing to the CLI's config — the app does not store tokens), open `config.toml`.
8. About screen names the licence (GPL-3.0-only / royalty-free / commercial) and links the repository.
9. Tests: Swift Testing for `KetchStore` with the fake core (install flow, busy, cancel, decider sheet); one UI smoke test that launches the app against a scratch root.

Check: the app builds and runs on macOS 26 on both architectures; manual pass against a scratch `KETCH_ROOT`: install a fixture package, see it in the CLI's `ketch list`, uninstall from the CLI and see it disappear in the app; a held CLI lock shows the busy state; `swift test` and the UI smoke test pass in CI.

Status: part 1 (app shell on a fake core) merged as PR https://github.com/pyrlyn/ketch/pull/185 (2026-10-01). XcodeGen 2.46.0 chosen over Tuist and plain SwiftPM (sources in `docs/research-desktop.md`). `Theme.swift` now reads F14's generated tokens and the screens were rebuilt on the Liquid glass design (F16 and F17, `done.md`). R9 (PR https://github.com/pyrlyn/ketch/pull/208) is merged, so the core is available. Remaining: a `LiveKetchCore` adapter over the generated `KetchCore` (`Ketch/Core/CoreFactory.swift` still returns `FakeKetchCore`; the adapter maps R9's structured `Changelog`), and the manual checks against the real core.
