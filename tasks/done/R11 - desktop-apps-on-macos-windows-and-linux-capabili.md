---
id: R11
title: "Desktop apps on macOS, Windows and Linux: capabilities, shared layer and per-platform interfaces"
status: done
created_at: 1792583854
updated_at: 1792583854
---

The creator asked (2026-10-01) for research on the desktop app for Windows and Linux, macOS included: what the app can do, what is common and written once versus what each platform does its own way, the common interface and each platform's interface, and tasks for each platform. Decided by the creator the same day: Windows is C# + WinUI 3 (Windows App SDK) over `ketch-ffi` (UniFFI, kept at 0.32 for now); Linux is written in Vala, with the toolkit (GTK or Qt/KDE) and the UI markup to be chosen by this research.

Done when `docs/research-desktop-platforms.md` (linked from `docs/research-desktop.md` and `docs/research-desktop-windows-linux.md`) holds a capability matrix per platform with the native API and its maturity, the common/specific split, the common core and app contract (with the gaps R9 left), the common UI and each platform's departures from it per its HIG, the way forward for the C# binding gap and for Vala reaching the core, a recommendation and the open decisions; every fact with a primary source and a version or check date, secondary-only facts marked **unverified**. The resulting tasks are in `plan.md` as `todo`, mirrored in `todo.md`.

Execution plan (Claude Code / opus-5.5):
1. Read the repository facts: R9's exported surface (`crates/ketch-ffi`), the macOS app's `KetchCoreProtocol.swift` and views, the design pipeline (`desktop/macos/design/`), R10's page; list the contract gaps between the app and `ketch-ffi`.
2. Primary sources, checked 2026-10-01: Apple, Microsoft Learn and GNOME/KDE developer docs and HIGs; crates.io, NuGet and GitHub/GitLab release APIs for UniFFI, `uniffi-bindgen-cs`, cbindgen, Vala, GTK, libadwaita, Blueprint, Qt binding projects for Vala, third-party UniFFI generators.
3. Write the page: matrix, common vs specific, interfaces, the C#/UniFFI version gap and the Vala/C ABI route, recommendation, open decisions.
4. Tasks: new `D` ids (desktop), shared first, then macOS (referencing F12/F13/F14 rather than repeating them), Windows, Linux; rows appended to the table, cards appended at the end, `todo.md` in sync.
5. Close at 90% with a `Status:` line; PR against `main`, CI watched until green.

Status: done 2026-10-01, PR https://github.com/pyrlyn/ketch/pull/211. The creator's answers to all ten open decisions are recorded in docs/research-desktop-platforms.md; D6 moved to ideas.md (no localisation for now).
