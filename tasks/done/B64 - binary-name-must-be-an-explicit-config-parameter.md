---
id: B64
title: Binary name must be an explicit config parameter (bug + fix)
status: done
created_at: 1792583854
updated_at: 1792583854
---

Bug: when several binaries in a release share the same name prefix, selection differs per OS. On macOS and Linux the intended binary is picked (first in the list). On Windows, alphabetical sorting picks `rtok hook` instead of the intended binary, so the wrong CLI runs.

Fix:

1. The config must name the binary that the CLI invokes.
2. On project/config creation this parameter is required.
3. For configs that already exist it stays optional (backward compatible).
4. If it is missing and more than one binary matches the expected name, prompt the user in select mode listing all candidate binaries. Write the chosen binary into the config and use it on subsequent runs.

Decisions (creator, 2026-09-27): a choice made for a registry or inferred (`owner/repo`) package is stored in the package's state record and reused on upgrade and reinstall; the registry manifest keeps updating. A local project `ketch.toml` gets the choice written into the file itself. Without a prompt (no TTY or `--yes`), the binary whose name equals the package name wins (`rtok` over `rtok-hook`, `.exe` ignored, case-insensitive); if that still leaves more than one, it is an error listing the candidates and how to set `bin`.

Execution plan:

1. Reproduce: find where inference picks the binary when `bin` is empty (`discover_executables` + its caller in `src/platform/unix.rs` and `src/platform/windows.rs`) and why Windows differs (executable filter, sort order). Write the failing unit test first.
2. One OS-independent selection function (not duplicated per platform): exact package-name match → remembered choice from state → TTY select prompt through `ui::` → error with candidates.
3. State: an optional field on `InstalledPackage` for the chosen binary, old state files load unchanged (serde default + a state test).
4. Local `ketch.toml`: write the chosen `bin` entry back, leaving the rest of the file byte-for-byte (`toml_edit`), through the module that owns manifest editing.
5. Creation: the `ketch config` wizard (`src/cmd/config.rs::ask_bins`, `src/wizard.rs`) requires a binary name; `Manifest::validate` stays lenient for existing files.
6. Tests: unit tests for the selection order; e2e in `tests/` with a fixture holding `rtok` and `rtok-hook` (non-TTY exact match, non-TTY ambiguity error, remembered choice on upgrade, local file write-back). Docs: `docs/MANIFESTS.md`, `docs/TROUBLESHOOTING.md`.
7. Verify: `just check` clean; run the binary against a `KETCH_ROOT` scratch tree.

Findings: with no `bin`, every OS linked every discovered executable; only the order differed (`rtok-hook.exe` sorts before `rtok.exe`, `rtok` before `rtok-hook`). The Windows symptom of linking the hook as `rtok` was B62's glob fallback. The only file manifest a user installs from today is `~/.ketch/manifests/<name>.toml`, so that is where the choice is written back.

Follow-up decisions (creator, 2026-09-27):

- A choice drops only the losing binaries that share the package name; every other executable in the release is linked as before.
- `ketch.lock` records the choice (new optional field, validated, `docs/LOCKFILE.md` row); `ketch sync` reuses it, so a fresh machine without a TTY does not stop on the ambiguity.
- `ketch install --bin <name>` makes the choice without a TTY and wins over every other rule; it is stored in state like a prompt answer.
- B65 stays with its own owner (creator, 2026-09-27). B64's branch already has `tests/bin_choice.rs` (`rtok` against `rtok-hook`, not gated by OS); B65 builds on it rather than adding a second fixture.
- The same directory-order fallback in `glob_preferred` is recorded in `ideas.md`, not fixed here.

Status: done 2026-09-27, PR https://github.com/pyrlyn/ketch/pull/156.
