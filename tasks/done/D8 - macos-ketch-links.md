---
id: D8
title: "macOS: `ketch://` links"
status: done
created_at: 1792583854
updated_at: 1792583854
---

A link on a web page or in the registry could open a package in the app. Whatever a link carries is untrusted input, so it is validated by the core. A link only opens a package page and never starts an install (creator, 2026-10-01, open decision 10). Research: section 1, "Deep links".

Done when `CFBundleURLTypes` registers `ketch`, `onOpenURL` opens the package page a valid link names, through the core's validation, any other action is refused, and tests cover malformed and hostile links.

Execution plan:

1. Core: `crates/ketch-core/src/link.rs`, `package_name(raw) -> Result<String>`. The one accepted shape is `ketch://package/<name>`: ASCII only, no query, fragment, userinfo, port or percent-escapes, at most 512 bytes; any other host (an install, uninstall or upgrade "action") is refused by name. The name must pass the existing `usable_file_name` guard (`config::sanitize_component` unchanged) and a stricter ASCII charset, then goes through `normalize_name` like any typed name. Rust tests: a table of valid, malformed and hostile links.
2. FFI: one free function `package_for_link` next to `ketch_version`; Swift binding test through `just ffi-test`.
3. App: `packageName(forLink:)` on `KetchCoreProtocol` (the fake only tells a package page from anything else; the grammar and its test table stay in the core), `CFBundleURLTypes` for `ketch` in `Ketch/Info.plist`, `onOpenURL` in the main window handing the URL to `KetchStore`, which asks the core, then looks the package up (installed or registry) before opening its page; a refused or unknown link shows the error alert and opens nothing. It never calls `install`.
4. Verify: `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo nextest run`, `just ffi-test`, `just macos-test`, and the registered scheme opened through `open ketch://package/ripgrep` against the built app.

Status: done.
