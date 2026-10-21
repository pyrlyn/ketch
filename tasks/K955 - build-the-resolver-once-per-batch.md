---
id: K955
title: Build the resolver once per batch
status: open
created_at: 1792603925
updated_at: 1792603930
priority: 3
blocked_by:
- K905
- K929
---

`manifest.rs:63` says the `Resolver` is built once per command, but `prepare` builds one per package and FFI `search` one per call, each re-parsing the built-in registry, every registry manifest and every user manifest. The cost grows with the registry.

Done when the resolver is passed into `prepare` and `batch`, and a test counts one load per batch.
