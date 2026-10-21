---
id: B49
title: User manifest documented as contributable as-is
status: done
created_at: 1792583218
updated_at: 1792583218
---

`docs/REGISTRY.md` claimed the same per-package checks apply to `~/.ketch/manifests/*.toml`, so a manifest that works locally can be contributed as-is. User manifests only pass [`Manifest::validate`](MANIFESTS.md); registry entries also require folder-name agreement and refuse `local:` sources.

The "What ketch checks" section now states that registry-only rules do not apply to user manifests, and that a manifest that installs locally is not necessarily contributable as-is.
