---
id: K902
title: Restore a green main
status: open
created_at: 1792603856
updated_at: 1792603856
priority: 0
---

`main` has had no green `ci` run since 2026-10-18; run 39601341616 at `57d8a67` fails on Linux and Windows. `platform/linux.rs:259` still sets `Placement.version`, which #440 removed (E0560); `shell.rs` calls the private `exe` on Windows after #441 (E0624); `install.ps1` fails to parse under Windows PowerShell 5.1 (UTF-8 without a BOM, `✓` at line 459), and README tells Windows users to run that file from `main`. Every open branch inherits the breakage.

Done when a full `ci` run on `main` is green on every target, `install.ps1` is ASCII-only (or has a BOM) with a test that fails on a non-ASCII byte, and the 5.1 step passes.
