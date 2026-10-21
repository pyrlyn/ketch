---
id: B34
title: Crate-wide `allow(dead_code)` on non-macOS
status: done
created_at: 1792583854
updated_at: 1792583854
---

`#![cfg_attr(not(any(target_os = "macos", target_os = "linux", target_os = "windows")), allow(dead_code))]` in `src/main.rs` suppressed dead-code warnings for the whole crate on unsupported hosts and was meant to paper over macOS-only code compiled on other targets. Removed the crate-wide attribute; macOS-only items already carry `#[cfg_attr(not(target_os = "macos"), allow(dead_code))]` in `src/platform/mod.rs` and `src/platform/scoring.rs`.
