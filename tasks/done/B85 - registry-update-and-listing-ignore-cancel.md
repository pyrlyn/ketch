---
id: B85
title: Registry update and listing ignore cancel
status: done
created_at: 1792583218
updated_at: 1792583218
---

Row B85 in the 2026-10-08 findings. `registry::update` downloads with `cx.cancel`. `ask_all` checks that token before each package, so a cancel stops the parallel lookup.

Status: done. See `done.md`.
