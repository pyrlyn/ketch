---
id: B39
title: "`ketch history --limit 0` says nothing was recorded"
status: done
created_at: 1792583218
updated_at: 1792583218
---

`--limit` is a `u32`, `LIMIT 0` yields no rows, and the empty-result branch printed "no history recorded yet" (or "for {pkg}") for both an empty database and a limit that asked for nothing.

`history` in `src/cmd/query.rs` now returns quietly when `--limit 0` yields no rows, so that case is not read as an empty database. JSON mode still emits `[]`. Integration test `history_with_a_zero_limit_does_not_claim_nothing_was_recorded` covers text and JSON output after an install.
