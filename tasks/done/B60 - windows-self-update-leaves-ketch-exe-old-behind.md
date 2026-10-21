---
id: B60
title: Windows self-update leaves `ketch.exe.old` behind
status: done
created_at: 1792583854
updated_at: 1792583854
---

Observed on Windows: `ketch self update` 0.4.4 → 0.5.1 succeeded, but the old binary stayed in the bin dir as `ketch.exe.old`. Windows will not delete the file backing a running image, and the process doing the cleanup at the end of `replace_binary` is that renamed image, so the delete failed and the error was dropped. `install_self` on Windows uses `with_extension("old")`, which names the same leftover `ketch.old`, and a stale aside also becomes a destination that can fail the next swap's rename.

`aside_candidates` names both leftovers. `sweep_stale_asides` deletes them at the start of `self install` and `self upgrade` (not on `--dry-run`), and `replace_binary` sweeps its own destination again before the rename: by then the earlier updater has exited, so the delete works. A failure warns and continues. The end-of-swap delete stays one silent best-effort try, because on Windows it cannot succeed until this process exits. `ketch doctor` warns (`backup`) when a leftover is still in the bin dir, or beside the running binary when that binary lives somewhere else.

Tests: `aside_names_cover_the_swap_and_the_flat_install`, `a_previous_swap_backup_is_removed_and_a_missing_one_is_fine`, `a_stale_sweep_clears_every_aside_and_warns_on_one_it_cannot_remove`, `doctor_notes_a_stale_aside_and_stays_quiet_without_one`.

---

LOCKFILE.md's "Refused" table omitted the unknown-`target` check that `Lockfile::validate` performs.

Added a row to the table in `docs/LOCKFILE.md`: a `target` ketch does not recognise is refused because it silently turns the entry into a cross-target one, so the recorded asset and hash stop applying and a hash that drifted under the tag reads as clean.
