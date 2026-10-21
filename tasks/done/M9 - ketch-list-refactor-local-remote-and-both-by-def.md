---
id: M9
title: "`ketch list` refactor: `local`, `remote`, and both by default"
status: done
created_at: 1792583218
updated_at: 1792583218
---

Today `ketch list` (`cmd/query.rs:40`) prints only installed packages from the state file (package, version with `(pinned)` / `(+N retained)`, source), with `--json` and `--names-only`. The registry is visible only through `ketch search`, and newer versions only through `ketch outdated`.

New syntax: `ketch list [local|remote] [--json] [--names-only]`.

1. `ketch list local`: installed packages only, from the state file, no network. Columns `package`, `installed`, `source`, keeping the `(pinned)` and `(+N retained)` notes. This is today's `ketch list` output.
2. `ketch list remote`: packages in the registry that can be installed. Columns `package`, `latest`, `description` (trimmed to the terminal width). Needs the network for `latest`.
3. `ketch list` with no argument: every package, installed and available, sorted by name, one table with columns `package`, `installed`, `latest`, `source`:
   - Installed packages are marked: a `●` in the first column and bold name on a TTY (plain `*` without colour), with both versions. When `latest` is newer than `installed`, the row says `update available` (yellow on a TTY) and a footer prints `N updates available: ketch upgrade <names>`. A pinned package shows `(pinned)` and is not offered as an update.
   - Packages not installed show only `latest`, with `installed` empty.
   - Installed packages that are not in the registry (installed from `owner/repo` or a local config) are still listed, with `latest` from their own source.

Where `latest` comes from: the registry manifests (`manifest.rs`) carry no version, so `latest` is the newest release of each package's source, from the same lookup `ketch outdated` uses (`cmd/query.rs:87`, prerelease rules from `resolve::list_opts`). Requests run in parallel with a small limit, results are cached with a short TTL (reuse the existing cache directory), and a rate-limited or unreachable package shows `?` in `latest` with a one-line note under the table instead of failing the whole list. `ketch list` with no network prints the local part plus `latest: offline` and exits 0; `ketch list remote` with no network is an error. A spinner or progress bar (`N/M packages`) runs while versions load, per F8.

Output: a compact table through the existing `ui::table`, one row per package, no blank lines; `ketch list local` with nothing installed prints `nothing installed`; `ketch list remote` with an empty registry prints `registry is empty; run ketch update`.

`--json`:
- `local`: an array of `{"name","installed","pinned","retained","source"}`.
- `remote`: an array of `{"name","latest","description","source"}`.
- no argument: `{"packages":[{"name","installed":null|"x.y.z","latest":null|"x.y.z","update_available":bool,"pinned":bool,"source"}],"unreachable":["name"]}`.
- `--names-only` prints names only, for each of the three modes.

Compatibility: `ketch list` without an argument changes from installed-only to everything, and its JSON shape changes. Scripts should use `ketch list local`; note it in `CHANGELOG.md` and the docs as a breaking change, and keep `ketch list --installed` as a hidden alias of `local` for one release.

Documentation (required; the task is not done without it):
- Update the `ketch list` section of `docs/COMMANDS.md`, written so a user understands it without reading the code:
  - the three modes (`local`, `remote`, no argument), what each shows and whether it needs the network;
  - every column (`package`, `installed`, `latest`, `source`, `description`) and the markers (`●` / `*`, bold, `update available`, `(pinned)`, `(+N retained)`, `?`);
  - how `update available` is decided: `latest` is the newest release of the package's source from the same lookup as `ketch outdated`, compared with the installed version, prerelease rules as in `resolve::list_opts`, never for pinned packages;
  - pinned packages: listed with both versions, marked `(pinned)`, not offered as an update, not in the footer;
  - packages installed from outside the registry (`owner/repo`, local config): listed, with `latest` from their own source;
  - offline behaviour: `ketch list` shows the local part and `latest: offline`, `ketch list remote` errors; unreachable packages show `?` and are named under the table; the cache and its TTL;
  - `--json` for each mode, with the full field list, and `--names-only`;
  - the breaking change from installed-only to everything, `ketch list local` for scripts, and the hidden `--installed` alias kept for one release.
- Each mode gets a command example with real output copied from a run (not invented), including one row with `update available` and one pinned row.
- Links: README's command overview links the section (`[ketch list](docs/COMMANDS.md#ketch-list)`); `docs/TROUBLESHOOTING.md` gets an entry for `?` / `latest: offline`; `CHANGELOG.md` names the breaking change and links the section. The landing site picks the docs up through the existing docs sync (not edited by this task).

Execution plan:

1. CLI: `ListMode { Local, Remote }` positional in `src/cli.rs`, hidden `--installed` alias of `local`; body stays thin in `src/cmd/query.rs`.
2. Merge logic (state + registry tiers + per-package `latest`) in its own module with the unit tests listed below; `latest` reuses the `ketch outdated` lookup and `resolve::list_opts`, parallel with a small limit, cached with a short TTL in the existing cache directory.
3. Output through `ui::table` and the existing `indicatif` progress in `src/ui.rs` (`N/M packages`); F8 is not a blocker, it generalises the same helper later.
4. `--json` and `--names-only` for all three modes; offline and unreachable handling as specified.
5. Tests as listed, snapshots with `insta`/`trycmd`, colour off; `docs/COMMANDS.md`, README link, `docs/TROUBLESHOOTING.md`, breaking-change commit (`feat!:`), examples copied from a real run against a scratch `KETCH_ROOT`.
6. Verify: `just check` clean.


Tests (required; all must pass in `just check` and CI on macOS, Linux and Windows):
- Unit:
  - merging state and registry: installed only, available only, both, installed but not in the registry, pinned;
  - `update_available`: newer, equal, older, prerelease versus stable per `list_opts`, pinned always false.
- Integration with a fake registry and a mock release API (the existing test HTTP fixtures), with colour off so snapshots are stable:
  - table snapshots of `ketch list local`, `ketch list remote` and `ketch list`;
  - `--json` snapshots of all three modes, checked against the documented fields;
  - bare `ketch list` marks installed packages (`*` without colour, `●` and bold with colour forced on) and shows both `installed` and `latest` for them, and only `latest` for the rest;
  - `update available` and the footer appear only for installed, unpinned packages with a newer `latest`;
  - a pinned package with a newer `latest` shows `(pinned)` and no `update available`;
  - a package installed from `owner/repo` or a local config, not in the registry, is listed with `latest` from its own source;
  - offline: bare `ketch list` prints the local part and `latest: offline` and exits 0; `ketch list remote` exits non-zero with a clear message;
  - one unreachable package: its `latest` is `?`, the note under the table names it, it appears in `unreachable` in JSON, and every other row is still printed;
  - `--names-only` in each of the three modes;
  - `ketch list --installed` gives the same output as `ketch list local`;
  - empty cases: `nothing installed` for `local`, `registry is empty; run ketch update` for `remote`.

Status: done 2026-09-27, PR https://github.com/pyrlyn/ketch/pull/152. Implemented with unit, snapshot and end-to-end tests.
