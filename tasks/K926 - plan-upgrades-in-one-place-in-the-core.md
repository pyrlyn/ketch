---
id: K926
title: Plan upgrades in one place in the core
status: open
created_at: 1792603879
updated_at: 1792603929
blocked_by:
- K925
---

Three paths answer "what is outdated?" differently: CLI `upgrade` checks serially and fresh (`src/cmd/pkg.rs:259-425`), CLI `outdated` runs its own uncancellable thread pool (`src/cmd/query.rs:288-392`), FFI uses the TTL-cached `listing::fill_latest`. The CLI and the GUI can disagree on "latest", and the command bodies break the thin-`cmd/` rule. The same hand-rolled worker pool appears in `install.rs` and `listing.rs` too.

Done when one core `upgrade::plan` (parallel, cancellable) returns upgrades, unreachable and skipped packages for CLI `upgrade`/`outdated` and FFI `upgrade`/`outdated`, one ordered cancel-aware pool helper replaces the three, and `pkg::upgrade` is under 80 lines.
