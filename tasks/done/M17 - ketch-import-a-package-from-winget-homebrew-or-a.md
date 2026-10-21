---
id: M17
title: "`ketch import`: a package from winget, Homebrew or a Linux repository"
status: done
created_at: 1792583854
updated_at: 1792583854
---

`ketch import winget|brew|linux <name>` converts a package whose artifacts are GitHub Release assets into a user manifest and installs it. Anything else writes nothing and exits non-zero. The three converters live in `crates/ketch-core/src/import/` (recorded fixtures, no network), the command is `src/cmd/import.rs`, and `tests/import.rs` drives it against a local mock. `docs/COMMANDS.md`, `docs/MANIFESTS.md` and the `docs/ru` and `docs/uk` translations describe it. Landed in #215.

Two notes stay with the creator. `serde-saphyr`, which reads the winget YAML, declares MSRV 1.89, above ketch's declared 1.86, and nothing in CI checks that floor. A cask `binary` whose source starts with `$APPDIR` stays inside the app bundle; `kind = "app"` places the bundle and links nothing.
Tests: `an_upgrade_that_drops_the_checksum_is_refused_without_consent`, `yes_consents_to_an_upgrade_that_drops_the_checksum`.
