---
id: R10
title: Toolkit choice for the Windows and Linux desktop apps
status: done
created_at: 1792583854
updated_at: 1792583854
---

`ROADMAP.md` ("Native desktop apps for Windows and Linux", approved 2026-09-30) gives each OS a native UI over the same core: Windows reuses R9's UniFFI binding from a native front end, Linux may link `ketch-core` directly from a Rust toolkit native to the desktop. It says the toolkit choice is its own research task, with sources; the creator asked on 2026-10-01 to take it now. This task is that research only: the apps themselves stay on the roadmap, and no implementation task is added here.

Scope: Windows — WinUI 3 / Windows App SDK (C# over UniFFI or a C ABI), WPF, and Rust options (windows-rs with WinUI, Slint, iced/egui as non-native contrast). Linux — GTK4 + libadwaita (gtk4-rs, relm4) linking `ketch-core` directly, Qt (cxx-qt), Slint, COSMIC/iced. For each: maintenance (latest release and date from the registry or the repository's releases), licence fit with ketch's triple licence, native look and accessibility, packaging (MSIX/winget, Flatpak), how it consumes the core (UniFFI binding or direct Rust), fit with the core's threading rules (Lock, Cancel, per-operation Config), effort.

Done when `docs/research-desktop.md` (or a page it links) holds the comparison with a primary source and a version or check date on every fact, secondary-only facts marked **unverified**, a recommendation per OS and the open decisions for the creator.

Plan:
1. Read `docs/research-desktop.md`, R9 and the licences; extend that research in the same style instead of contradicting it.
2. Collect facts from primary sources only: crates.io, NuGet and GitHub releases APIs, vendor docs (Microsoft Learn, GNOME, Flathub, Qt, Slint, UniFFI and its C# generator), checked 2026-10-01.
3. Write the comparison, per-OS recommendations and open decisions; decide whether it lives in `docs/research-desktop.md` or a linked page and say why.
4. Close: readiness 90%, a `Status:` line with the PR link and the recommendation; PR against `main`, CI checked once.

Check: every table row cites a URL plus a version or date; `just check` (or the docs-relevant part of CI) passes on the PR.

Status: done 2026-10-01, PR https://github.com/pyrlyn/ketch/pull/205. Research in `docs/research-desktop-windows-linux.md` (linked from `docs/research-desktop.md`). Recommendation: WinUI 3 in C# over R9's UniFFI binding (via `uniffi-bindgen-cs`, unpackaged through winget) for Windows, with `windows-reactor` as the Rust alternative to spike, and GTK 4 + libadwaita through gtk4-rs linking `ketch-core` directly for Linux; the open decisions are listed at the end of the research page.
