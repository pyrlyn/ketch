---
id: M5
title: Registry maturity
status: done
created_at: 1792583218
updated_at: 1792583218
---

This-repo half of registry maturity. `ketch registry validate` already failed on parse errors and name/alias collisions; it now also offline-installs `--changed` entries against `--fixture` (a file named after the package, or a folder of one file) in a throwaway root, so a `github:` source never hits the network. Clients (`ketch update`, lookup) still warn and skip a published bad registry.

`ketch update` writes `registry.meta.toml` under the ketch root (repo, GitHub tarball revision SHA, optional ETag, `fetched_at`), separate from the package folders. `ketch registry status` and the doctor registry line report age and source from that file with no network call. `ketch update` remains the only refresh.

Author/maintainer workflow, the exact command (`ketch registry validate .`, plus `--fixture` / `--changed`), and the fail-closed-CI / best-effort-client compatibility policy are in `docs/REGISTRY.md`.

F2 is not closed: the ketch-registry repository still needs a GitHub Actions workflow that runs `ketch registry validate` (and fixture installs for changed entries) on every pull request. Other tasks besides F2 remain in `plan.md`.
