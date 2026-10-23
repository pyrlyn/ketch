---
id: K920
title: Test the macOS live core against a scratch root
status: open
created_at: 1792603872
updated_at: 1792603872
priority: 1
---

`LiveKetchCore.swift`, the adapter the first notarised build ships, has no test and no recorded manual pass; Windows has `FfiKetchCoreTests` doing exactly that.

Done when Swift tests drive `LiveKetchCore` offline through install, uninstall, a binary choice, cancel, `Busy` and `NotFound` on a scratch root, the `macos-app` job runs them, and a manual pass is recorded in F13.
