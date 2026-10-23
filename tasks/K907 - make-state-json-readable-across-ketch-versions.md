---
id: K907
title: Make `state.json` readable across ketch versions
status: open
created_at: 1792603860
updated_at: 1792603860
priority: 1
---

The CLI, the Homebrew bootstrap and each desktop app (released separately, each linking its own `ketch-ffi`) share one `~/.ketch`. `STATE_VERSION` (`state.rs:27`) has never moved; the stored manifest snapshot inherits `deny_unknown_fields` (`model.rs:840`), the stored enums have no fallback and unknown fields are dropped on save. One new manifest key or enum value written by a newer build makes every older build fail on "could not parse state.json".

Done when the stored snapshot is parsed leniently (user-written files stay strict), stored enums have an `Unknown` fallback, unknown fields round-trip via `serde(flatten)`, a test loads and re-saves a state with an unknown key, enum value and package field unchanged, and the rule for bumping `STATE_VERSION` is written down.
