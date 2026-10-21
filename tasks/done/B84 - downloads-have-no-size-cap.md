---
id: B84
title: Downloads have no size cap
status: done
created_at: 1792583218
updated_at: 1792583218
---

Found by the 2026-10-08 review. Done: `Http::download` aborts over a configurable cap. Plan: a `max_download` setting in `config.rs` with a sane default, enforced while streaming in `http.rs`.
Model: Claude Code / opus-5.5 · Status: done, merged in #301 · Priority: P2 · Complexity: 3
Check: a test with a cap below the body size that errors and leaves no partial file.
