---
id: K913
title: Offer matches when a short name is unknown
status: open
created_at: 1792603865
updated_at: 1792603865
priority: 1
---

`ketch install <unknown>` stops with advice to run `ketch search` by hand (`manifest.rs:122-130`). One prompt turns that dead end into an install.

Done when, on a terminal, an unknown name shows the top registry and GitHub matches through the `Decider` and installs the one chosen; under `--yes` or without a TTY it still fails, listing them; an end-to-end test runs against the fixture plugin.
