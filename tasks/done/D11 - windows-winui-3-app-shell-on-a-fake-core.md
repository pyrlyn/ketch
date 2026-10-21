---
id: D11
title: "Windows: WinUI 3 app shell on a fake core"
status: done
created_at: 1792583218
updated_at: 1792583218
priority: 3
---

The Windows app's screens can be built before the binding is settled, the way F12 started on macOS. Research: sections 3b and 3c.

Done when a WinUI 3 app (Windows App SDK, .NET 10, built with `dotnet build`, no Visual Studio required) has the common screens in a `NavigationView` with a `TitleBar` over Mica, `ContentDialog` and `InfoBar` for decisions and busy, light, dark and contrast themes, all on a fake core reading D4's fixtures, with a Windows CI job that builds and runs its tests.

Execution plan:

1. `desktop/windows/Ketch.AppCore` (net10.0, no UI types, so it builds and tests anywhere): `IKetchCore`, the records, `CancelToken`, `ContractScenario` (System.Text.Json over `desktop/contract/scenarios`, the same mapping as `ContractScenario.swift`), `FakeKetchCore` (a port of the macOS fake: samples, simulated pipeline, scripted replay, lock switch) and `KetchStore` (`INotifyPropertyChanged`; installed, updates, held, search, doctor, activity, log, busy, pending binary choice; install, upgrade, uninstall, cancel, retry).
2. `desktop/windows/Ketch.AppCore.Tests` (MSTest, as `KetchCore.Tests`): every scenario decodes and maps, the fake replays them (events, questions, busy, cancelled, errors), and the store's states.
3. `desktop/windows/Ketch.App` (WinUI 3, `WindowsPackageType=None`, self-contained, built by `dotnet build`): `App.xaml` merges `desktop/design/generated/KetchTokens.xaml`; `MainWindow` with a `TitleBar` over `MicaBackdrop` and a `NavigationView` (Installed, Discover, Updates, Activity, Doctor, Settings pinned at the bottom); a package detail page; `ContentDialog` for uninstall, upgrade-all and binary choice; `InfoBar` for busy and errors; Settings picks light, dark or system, and high contrast follows Windows through the token file's `HighContrast` dictionary.
4. CI: a `ketch-win-app` job on `windows-latest` with SHA-pinned actions: `dotnet test` for `Ketch.AppCore.Tests`, `dotnet build` for `Ketch.App`. If the Windows App SDK cannot build without Visual Studio, stop and report rather than work around it.
5. `toolchain.md` rows (Windows App SDK, `Microsoft.WindowsAppSDK`), `desktop/windows/README`-level notes in the contract README (the Windows fake is now here), SPDX headers on every new source file.
6. Verify: `dotnet test` for `Ketch.AppCore.Tests` locally (net10.0 builds on this Mac), `dotnet build` of the WinUI project only in CI (not buildable on macOS; noted in the PR).

Status: done. The shell, the fake core and the `ketch-win-app` job landed in #247. See `done.md`.
