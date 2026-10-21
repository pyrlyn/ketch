---
id: B99
title: Extraction has no byte or entry budget
status: done
created_at: 1792583218
updated_at: 1792583218
---

Row B99 in the 2026-10-12 findings. Done: one shared budget (bytes, entries, ratio plus ceiling) bounds every extractor.

Plan: a budget type in `extract/`, threaded through zip, tar and lone-file extractors. Check: tests with a small gzip bomb and a many-entry zip that fail with a clear error.

Status: done. `Budget` in `extract/mod.rs` caps bytes and entries, with a ratio of the archive size, a 256 MiB floor and a 32 GiB ceiling. Zip, tar and the lone gzip/xz/bzip2 extractors take it. Tests cover a gzip bomb, a tar member over the budget, a tar and a zip with too many members, and a zip bomb.
