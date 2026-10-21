---
id: S9
title: "`ketch registry validate`"
status: done
created_at: 1792583218
updated_at: 1792583218
---

Fail-closed check of a registry tree: every `ketch.toml` through `Manifest::validate`, plus name/alias collisions. The client still warns and skips; this is what `pyrlyn/ketch-registry` CI should run. `--json` for machines.
