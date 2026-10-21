---
id: B68
title: Update installs into a fresh folder so stale files cannot interfere
status: done
created_at: 1792583218
updated_at: 1792583218
---

Ivan: update must clean or delete the program folder and install into a fresh one.

Today the per-version prefix is already fresh. `move_into_store` stages the payload as `<version>.incoming` and swaps it in through `<version>.old`, so a new version and a `--force` reinstall of the same version both replace the directory whole. Gaps: (a) stale `.incoming` / `.old` siblings survive when their best-effort removal fails; (b) old links and copied files the new version no longer has are removed by `platform.unplace(&stale)`, and a failure there is only a warning; (c) retained prefixes of earlier versions stay on purpose, because `ketch rollback` (M6) needs them.

Plan:
1. Sweep stale siblings (B67's helper) at the start of every install and upgrade, before hooks run.
2. If a stale sibling or a stale link cannot be removed, fail the update before anything is placed, naming the path. Do not warn and continue.
3. Decision for Ivan: "delete the program folder" must not break rollback. Proposal: keep retained prefixes (they are separate directories, so they cannot leak files into the new one) and say so in `docs/COMMANDS.md`. The alternative is to drop retention by default (`retain = 0`).
4. `ketch self upgrade` replaces the binary in place (`replace_binary`). Its leftovers are covered by B60, and nothing more is needed here.

Check: e2e: a file present in 1.0.0 and absent from 1.1.0 is gone after upgrade, and a same-version `--force` reinstall leaves no stale file; a planted `1.1.0.incoming` does not end up inside the new prefix; `just check`.

Done (Claude Code / opus-5.5): Ivan chose to keep retained prefixes (step 3), now stated in `docs/COMMANDS.md` under `ketch upgrade`. `install::commit` runs `sweep_swap_leftovers` before the hooks and before anything is placed: every `*.incoming` / `*.old` entry in `store/<name>/` goes, except a prefix the package still records; one that cannot be removed fails the install or upgrade with its path. `package_dir_candidate` is the guard shared with B67's `remove_package_dir`. Step 2's stale *links* stay a warning: they are only known after the new placement, which is placed first on purpose so a failed placement keeps the working links, and a leftover link points at a retained prefix, never into the new one. Tests: unit tests for the sweep (leftovers go, versions and recorded prefixes stay, a failure names the path), e2e `upgrade_installs_into_a_fresh_prefix_that_nothing_stale_reaches` and `a_forced_reinstall_of_the_same_version_leaves_no_stale_file` (macOS).
