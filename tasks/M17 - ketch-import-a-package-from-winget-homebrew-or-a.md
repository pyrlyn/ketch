---
id: M17
title: "`ketch import`: a package from winget, Homebrew or a Linux repository"
status: in-progress
created_at: 1792583218
updated_at: 1792583218
---

Requested by the creator (2026-10-02). `ketch import winget|brew|linux <name>` looks a package up by name in another package manager, converts its definition to a ketch manifest, writes it as a user manifest (`~/.ketch/manifests/<name>.toml`) and installs it the normal way. Re-running it is idempotent: an unchanged conversion with the release already installed prints that everything is up to date and touches nothing; a changed conversion or a newer upstream version rewrites the manifest and installs. The manifest holds only what ketch needs (`name`, `source`, `kind` for an app, `bin`, `[asset.target]` pins); nothing else from the source is copied.

Hard rule: only packages whose artifacts are GitHub Release assets convert. Anything else writes nothing and exits non-zero with `<name> can't be converted: it is not distributed through GitHub Releases, and that is not supported yet.`

Done when the three converters, the command, docs (`docs/COMMANDS.md`, `docs/MANIFESTS.md`) and offline tests (recorded fixtures, a temp root, never the real config) are in, and `just check` is green.

Research (sources checked 2026-10-02):

- Homebrew: `formulae.brew.sh/api/formula/<name>.json` and `/api/cask/<token>.json` (https://formulae.brew.sh/docs/api/). A cask's `url` is the macOS arm64 download; `variations` keyed by macOS release (`sequoia`, …) are the Intel ones, `arm64_<release>` the arm64 ones; `sha256` may be `no_check`; `artifacts` lists `app`, `binary`, `pkg`, `installer` and others (response of `/api/cask.json`, 7778 casks). A formula's `urls.stable.url` is the source it builds from; its binaries are bottles on `ghcr.io` (`bottle.stable.files`), never GitHub Releases. Of 817 core formulae whose stable URL is under `releases/download/`, none is a prebuilt archive naming an OS and an architecture (`/api/formula.json`) — so a formula converts only in that case and is otherwise "source only".
- winget: the community source is the `microsoft/winget-pkgs` repository, `manifests/<first letter>/<Id with dots as folders>/<version>/<Id>.installer.yaml` (https://github.com/microsoft/winget-pkgs/tree/master/doc/manifest/schema/1.12.0). The community source has no public REST endpoint (the REST protocol is for private sources and the msstore source), so versions are listed with the GitHub contents API and the manifest read raw. `InstallerType`, `NestedInstallerType`, `NestedInstallerFiles` and `Commands` may sit at the root or per installer; one architecture may have several installers (per `Scope` or `InstallerLocale`), often with the same URL (`Git.Git` 2.55.0.5).
- YAML: `serde_yaml` is deprecated (crates.io, `0.9.34+deprecated`), `serde_yaml_ng` and `serde_norway` last released 2024; `serde-saphyr` 1.3.0 (2026-09-16, MIT/Apache-2.0, pure Rust) is maintained, so it reads the winget manifests. Its `rust-version` is 1.89, above ketch's declared MSRV 1.86, which nothing in CI checks — flagged for the creator.
- Linux: there is no cross-distribution source that names an artifact URL. Repology (https://repology.org/api) maps names across distributions but carries no download URLs (**unverified**: the API page could not be reached on 2026-10-02). Debian, Fedora and the official Arch repositories build from source, so their recipes point at source archives. Flathub is keyed by reverse-DNS app IDs, not package names. The AUR is name-based with a JSON API (https://wiki.archlinux.org/title/Aurweb_RPC_interface, `rpc/v5/info`, `rpc/v5/search?by=provides`), and its `-bin` packages repackage upstream's prebuilt artifacts, with per-architecture `source_<arch>` URLs and `sha256sums_<arch>` in a machine-readable `.SRCINFO` (https://wiki.archlinux.org/title/.SRCINFO). So `linux` means Arch Linux: the official repositories (https://wiki.archlinux.org/title/Official_repositories_web_interface, `.SRCINFO` from `gitlab.archlinux.org/archlinux/packaging/packages/<pkgbase>`) and the AUR, the official one first.

Execution plan:
1. This card.
2. `serde-saphyr` for the winget YAML (one commit, `toolchain.md` and `rust.md` rows).
3. Core `crates/ketch-core/src/import/`: the shared rules (a GitHub release URL, the tag, per-target asset globs, the rejection error) and one converter per source on plain data, with recorded fixtures under `crates/ketch-core/src/import/fixtures/`; the fetching behind a small trait so tests never reach the network; base URLs overridable by `KETCH_IMPORT_BREW`, `KETCH_IMPORT_WINGET_API`, `KETCH_IMPORT_WINGET_RAW`, `KETCH_IMPORT_ARCH`, `KETCH_IMPORT_ARCH_GITLAB`, `KETCH_IMPORT_AUR`.
4. Writing the manifest through `manifest.rs` (the module that owns user-manifest files), rendering through `wizard::render`, and the idempotency decision in the core.
5. `ketch import` in `cli.rs` and `cmd/import.rs`; end-to-end tests in `tests/import.rs` against a local mock of the sources and of the GitHub API.
6. Docs: `docs/COMMANDS.md`, `docs/MANIFESTS.md`, help snapshots, man pages.

Rules decided here:
- GitHub Releases means every artifact URL is `https://github.com/<owner>/<repo>/releases/download/<tag>/<file>` (or `releases/latest/download/<file>`), all of one repository and one tag. A GitHub homepage or an `archive/` source tarball does not count.
- Mixed installers: if any artifact ketch would use, on any architecture, is hosted elsewhere, nothing converts — one manifest has one source, and a partial conversion would install on one machine and silently fall back to guessing on another.
- An artifact that is a source archive (an archive naming neither an OS nor an architecture, for a Homebrew formula) is not a release artifact.
- Installer formats ketch cannot place (winget `msi`, `msix`, `exe`, `inno`, `nullsoft`, `wix`, `burn`; cask `pkg`, `installer` and the other non-`app`, non-`binary` artifacts; Linux `.deb`/`.rpm`) are refused with their own message, after the GitHub check.
- A file at the manifest path that does not start with the ``# Written by `ketch import`` header is the user's and is never replaced; the command refuses instead.
- The host must be one of the converted targets, or nothing is written: a manifest this machine cannot install from would leave a package `ketch install` fails on.
- Idempotency: the rendered file is compared byte for byte with the one on disk, the installed version with the source's tag. Same file and installed ≥ source → "Everything is up to date", nothing touched; a newer tag → rewrite and upgrade; same version, changed file → reinstall (`force`); installed newer than the source → the installed release stays and only the manifest is redone. The install pins the source's asset (`asset_override`) and checksum (`expected_sha256`); a missing checksum warns and installs, checked the usual way.
- Bin paths and asset patterns have the version replaced by `*`, so the manifest keeps matching after the next release.

Status: steps 1–6 landed in #215. `docs/ru` and `docs/uk` translate the user-facing docs, including `ketch import`. Still open for the creator: `serde-saphyr`'s MSRV is 1.89, above the declared 1.86. A cask `binary` whose source starts with `$APPDIR` stays inside the app bundle; `kind = "app"` places the bundle and links nothing.
