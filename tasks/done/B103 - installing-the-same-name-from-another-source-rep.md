---
id: B103
title: Installing the same name from another source replaces a package
status: done
created_at: 1792583218
updated_at: 1792583218
---

Found by the 2026-10-12 review. Done: `prepare` refuses when `existing.source` differs from `manifest.source` and points at `--name`; `ketch sync` reports it. Plan: compare in `install.rs` `prepare` and the lockfile plan.
Model: Claude Code / opus-5.5 · Status: done, merged in #298 · Priority: P2 · Complexity: 3
Check: an end-to-end test installing `otherowner/x` over `owner/x` that fails and leaves the first install intact.
