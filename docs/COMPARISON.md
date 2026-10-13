# How ketch compares

## How it compares

Every tool below is good at what it was built for; this is where ketch sits
among them. Checked against each project's documentation in September 2026.

| | ketch | Homebrew | apt | mise |
| --- | --- | --- | --- | --- |
| Needs a formula or package written first | No — any `owner/repo` with release assets; a manifest only when inference picks wrong | Yes — a formula or cask, in core or a tap | Yes — a `.deb` in a configured repository | No for `github:` / `aqua:` backends; short names come from its registry |
| Platforms | macOS, Linux, Windows | macOS, Linux | Debian-family Linux | macOS, Linux, Windows |
| Verification by default | SHA-256 when the release publishes one (checksum file or GitHub asset digest); publisher signature when the manifest declares `trust` | SHA-256 pinned in every formula | Signed repository metadata plus per-package hashes | Checksums when recorded (config or `mise.lock`); GitHub attestations and SLSA provenance when the release has them |
| Versioned store with rollback | Yes — previous version retained; `ketch rollback` relinks it | Old versions stay until `brew cleanup`; no rollback command | No — downgrade only if the old version is still available | Versions side by side; switch with `mise use tool@version` |
| Clean uninstall | `ketch uninstall`; `ketch self uninstall` removes the whole `~/.ketch` tree and its PATH block | `brew uninstall` (`--zap` for cask leftovers) | `apt purge` removes the package and its config files | `mise uninstall`; `mise implode` removes mise itself |
| Builds from source | Never | When no bottle fits | Never (binary packages) | Some backends do |

Where ketch is the odd one out: `.app` bundles into `/Applications`, an
explanation for every asset it picks, and a store it can roll back without
touching the network.
