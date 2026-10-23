---
id: K914
title: Ship a setup-ketch GitHub Action
status: open
created_at: 1792603865
updated_at: 1792603866
priority: 1
---

CI is where teams adopt a tool installer, and mise, aqua and pkgx each offer a one-line step; ketch has no `action.yml`.

Done when a composite action installs a pinned ketch verified against `SHA256SUMS`, adds the bin dir to `GITHUB_PATH`, passes the token, caches the store keyed on the `ketch.lock` hash, optionally runs `ketch sync`, and a CI job uses it on all three OSes.
