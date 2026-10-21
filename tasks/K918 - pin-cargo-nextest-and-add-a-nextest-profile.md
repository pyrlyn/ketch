---
id: K918
title: Pin cargo-nextest and add a nextest profile
status: open
created_at: 1792603869
updated_at: 1792603869
priority: 1
---

Every documented test command needs nextest, but `mise.toml` does not pin it: `cargo nextest run` fails locally with "No version is set for shim: cargo-nextest". There is no `.config/nextest.toml`, and the Windows run reports leaked children (`upgrade_stops_a_process_holding_the_binary_when_yes`, `lists_a_child_running_a_cmd_script`).

Done when nextest is pinned in `mise.toml` and `toolchain.md`, and a `ci` profile sets slow and leak timeouts (a leak fails) and uploads JUnit output.
