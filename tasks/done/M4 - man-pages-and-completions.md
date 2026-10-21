---
id: M4
title: Man pages and completions
status: done
created_at: 1792583854
updated_at: 1792583854
---

`extra_paths` is classified as a man page or a completion from explicit `{ path, kind }` metadata or the path rules in `docs/MANIFESTS.md`. Ambiguous or untyped entries are refused at validate rather than guessed. Entries resolve only under the extracted payload; destinations go in `LinkRecord` with `role` man/completion so uninstall and relink use the same ownership proof as binaries. Old state without `role` still loads as binaries.

Platform methods name the user man root (`$XDG_DATA_HOME/man` or `~/.local/share/man`) and per-shell completion directories. `ketch doctor` reports those paths before anything is written. `ketch self install` / `self update` generate ketch's own man page and completions into the store prefix and link them; `ketch completions <shell> --install` does the same for one shell.

Tests: classification and plan in `src/extra.rs`, place/unplace extras in `src/platform/macos.rs`, doctor dests in `src/platform/mod.rs`, backward-read in `src/state.rs`.
