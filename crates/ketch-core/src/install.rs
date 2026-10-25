// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The install pipeline.
//!
//! resolve manifest → resolve release → score and pick an asset → download →
//! verify checksum → extract → verify trust → place → record state.
//!
//! Every step is expressed against the `Source`, `Platform` and `Extractor`
//! traits, so this file contains no GitHub-specific and no macOS-specific code.

use crate::bin_choice::{self, Picked};
use crate::cancel::Cancel;
use crate::config::{sanitize_component, Config};
use crate::error::{Error, Result};
use crate::hooks;
use crate::manifest::Resolver;
use crate::model::{
    now_unix, AssetSelector, BinSpec, Channel, InstalledPackage, LinkRecord, LocalKind, PackageRef,
    PackageSpec, Release, ReleaseAsset, RetainedVersion, TrustResult, Version, VersionSpec,
};
use crate::platform::{AssetScore, Placement, Platform, TrustVerdict};
use crate::report::{Ctx, ProgressSink, Report, Stage};
use crate::source::{ListOpts, SourceRegistry};
use crate::state::State;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Mutex;

/// One install, fully specified.
#[derive(Debug, Clone)]
pub struct InstallRequest {
    pub spec: PackageSpec,
    /// Reinstall even when the resolved version is already present.
    pub force: bool,
    pub prerelease: bool,
    /// `--draft`: consider draft releases too, and prereleases with them.
    /// Refused for a source that has no drafts.
    pub draft: bool,
    /// Create links in the bin dir (and copy `.app`s) after unpacking.
    pub link: bool,
    /// Fail rather than record a first-seen hash when no checksum is published.
    pub require_checksum: bool,
    /// Exact asset file name, bypassing scoring.
    pub asset_override: Option<String>,
    /// SHA-256 this asset is already known to have, from a lockfile. Checked
    /// before anything is unpacked.
    pub expected_sha256: Option<String>,
    /// When the build a lockfile recorded under a floating tag was
    /// published. A tag that now names a build published at another time is
    /// refused before anything is downloaded.
    pub expected_published: Option<String>,
    /// Override the resolved package name (used by `ketch install --path --name`).
    pub name_override: Option<String>,
    /// `--bin`: which of several binaries sharing the package's name to
    /// link, answered before anyone asks. Wins over every other rule.
    pub bin: Option<String>,
    /// The choice `ketch.lock` recorded, consulted after state's: a fresh
    /// machine has no state, and no terminal to ask on during `ketch sync`.
    pub locked_bin: Option<String>,
    /// `ketch install` without `--yes`: an installed package with a newer
    /// release stops with `Error::UpdateAvailable`, so the command can ask
    /// before it updates, instead of updating on its own.
    pub offer_update: bool,
    /// Install even when the package was installed against a published
    /// checksum and this release publishes none (`--allow-unverified`,
    /// `--yes`).
    pub allow_unverified: bool,
    /// Stops this install at its next check: before the download, between
    /// chunks, and before anything is placed. Clones share one flag, so a host
    /// keeps a clone to cancel with. `Cancel::new()` never fires on its own.
    pub cancel: Cancel,
}

impl InstallRequest {
    /// A plain install of one package: latest, linked, nothing overridden.
    pub fn new(spec: PackageSpec) -> Self {
        InstallRequest {
            spec,
            force: false,
            prerelease: false,
            draft: false,
            link: true,
            require_checksum: false,
            asset_override: None,
            expected_sha256: None,
            expected_published: None,
            name_override: None,
            bin: None,
            locked_bin: None,
            offer_update: false,
            allow_unverified: false,
            cancel: Cancel::new(),
        }
    }
}

/// What an install actually did.
#[derive(Debug, Clone)]
pub struct Installed {
    pub package: InstalledPackage,
    /// The version that was replaced, when this was an upgrade or reinstall.
    pub replaced: Option<Version>,
}

/// An asset and the score that won it the selection.
pub use crate::resolve::ScoredAsset;

/// Everything an install downloads, checks and unpacks, before it touches the
/// install tree.
///
/// Split out from `commit` so a batch can do this part for several packages at
/// once. Nothing here writes outside the cache, so two of these running side by
/// side cannot collide: the store, the bin directory and `state.json` are only
/// reached from `commit`, which stays sequential.
pub struct Prepared {
    /// TUI/progress row id. `prepare` stages with `PackageSpec::label()`; `commit`
    /// must use the same string, not `manifest.name`, or an alias/path install
    /// leaves a second row spinning on Installing.
    label: String,
    manifest: crate::model::Manifest,
    origin: crate::model::ManifestOrigin,
    release: Release,
    asset_name: String,
    sha256: String,
    checksum_verified: bool,
    trust: TrustResult,
    /// The publisher signature the manifest's `trust` table asked for.
    provenance: Option<crate::model::Provenance>,
    /// Carried from the request: `commit` is the only place that links.
    link: bool,
    /// Carried from the request: `commit` is where a binary is chosen.
    bin: Option<String>,
    /// Carried from the request, like `bin`.
    locked_bin: Option<String>,
    /// Root of the unpacked payload, inside `unpack`.
    payload: PathBuf,
    /// Held so the unpacked payload outlives this function.
    unpack: tempfile::TempDir,
    /// When this install began. Carried through so the statistic `commit`
    /// records covers the download and the unpack too — the parts that take the
    /// time — rather than only the placement it can see for itself.
    started: std::time::Instant,
    /// Set for `local:` installs so list/info can show how the path was used.
    local_kind: Option<LocalKind>,
    local_path: Option<PathBuf>,
    /// Carried from the request so `commit` can stop before it places anything.
    cancel: Cancel,
    /// Decided from the request's `--pre` and the release, recorded by `commit`.
    channel: Channel,
}

/// Run the pipeline. Mutates `state` in memory; the caller saves it, so a batch
/// install writes `state.json` once.
pub fn install(
    cx: &Ctx<'_>,
    sources: &SourceRegistry,
    state: &mut State,
    req: &InstallRequest,
) -> Result<Installed> {
    let progress = cx.report.download(&req.spec.label());
    let prepared = prepare(cx, sources, state, req, &progress)?;
    commit(cx, state, prepared)
}

/// Resolves, downloads, verifies, and unpacks a package for installation.
///
/// This phase does not modify the installation tree. The returned preparation
/// can be passed to `commit` after all requested packages have been prepared.
///
/// # Examples
///
/// Does not run here: resolving `ripgrep` talks to GitHub.
///
/// ```no_run
/// use ketch_core::config::Config;
/// use ketch_core::install::{commit, prepare, InstallRequest};
/// use ketch_core::model::PackageSpec;
/// use ketch_core::report::{Ctx, Report};
/// use ketch_core::source::SourceRegistry;
/// use ketch_core::state::State;
///
/// let report = Report::silent();
/// let cfg = Config::load(None, &report)?;
/// let cx = Ctx::new(&cfg, &report);
/// let sources = SourceRegistry::load(&cx);
/// let mut state = State::default();
/// let request = InstallRequest::new(PackageSpec::parse("ripgrep"));
/// let progress = report.download("ripgrep");
/// let prepared = prepare(&cx, &sources, &state, &request, &progress)?;
/// let _installed = commit(&cx, &mut state, prepared)?;
/// Ok::<(), ketch_core::error::Error>(())
/// ```
///
/// # Errors
///
/// Returns an error if resolution, asset selection, downloading, checksum
/// verification, extraction, or other preparation steps fail.
///
/// # Parameters
///
/// * `cx` - Installation configuration, and where progress is reported.
/// * `sources` - Registered package sources.
/// * `state` - Current installation state used to reject pinned or already-installed packages.
/// * `req` - Package and installation options.
/// * `progress` - Receives download progress updates.
///
/// # Returns
///
/// The verified and unpacked package data required by `commit`.
pub fn prepare(
    cx: &Ctx<'_>,
    sources: &SourceRegistry,
    state: &State,
    req: &InstallRequest,
    progress: &dyn ProgressSink,
) -> Result<Prepared> {
    req.cancel.check()?;
    let (cfg, report) = (cx.cfg, cx.report);
    let started = std::time::Instant::now();
    let platform = crate::platform::host()?;
    let label = req.spec.label();
    report.stage(&label, Stage::Resolving);
    let (mut manifest, origin) = Resolver::new(cx)?.resolve(&req.spec)?;

    // Local refs are recorded with an absolute path so list/info survive a
    // later change of working directory. Classification also needs the path
    // to exist before anything is copied.
    let (local_kind, local_path) = if manifest.source.scheme == "local" {
        let abs = crate::source::local::resolve_path(&manifest.source.id)?;
        let kind = crate::source::local::classify(&abs)?;
        manifest.source = crate::source::local::package_ref(&abs);
        (Some(kind), Some(abs))
    } else {
        (None, None)
    };

    if let Some(name) = &req.name_override {
        let name = sanitize_component(&crate::model::normalize_name(name));
        if name.is_empty() {
            return Err(Error::msg("--name produced an empty package name"));
        }
        manifest.name = name;
        manifest.validate()?;
    }

    // A local single file should appear on PATH under the package name, not
    // whatever the file happened to be called on disk (`a.out`, a symlink
    // leaf, a version-stamped build artifact).
    if matches!(local_kind, Some(LocalKind::Binary | LocalKind::Symlink)) {
        if let Some(path) = &local_path {
            let leaf = path
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_else(|| manifest.name.clone());
            if manifest.bin.is_empty() {
                manifest.bin = vec![BinSpec {
                    // Spelled the way the download is staged below, or a leaf
                    // that sanitizing changes (`.tool`, `-tool`) is never found.
                    path: Some(sanitize_component(&leaf)),
                    name: Some(manifest.name.clone()),
                }];
            }
        }
    }

    let source = sources.for_ref(&manifest.source)?;

    let mut opts = crate::resolve::list_opts(cfg, &manifest, req.prerelease);
    if req.draft {
        opts.ask_for_drafts(source.as_ref(), &manifest.source.scheme)?;
    }
    report.step(
        "resolving",
        &format!("{} ({})", manifest.name, manifest.source),
    );
    let release = report
        .activity(&format!("resolving {}", manifest.name))
        .run(|| source.resolve(&manifest.source.id, &req.spec.version, &opts))?;
    check_published(req, &manifest.name, &release)?;

    // Nothing is downloaded until we know the install is actually wanted.
    let existing = state.get(&manifest.name).cloned();
    if let Some(old) = &existing {
        // State is keyed by name alone. Another project under the same name
        // would take over this one's links, run its update hooks and mix two
        // sources' versions in one retained list — a replacement nobody asked
        // for by name.
        if !crate::manifest::same_source(&old.source, &manifest.source) {
            // `--name` is a CLI flag, so it stays in the binary's hint. A GUI
            // matches `SourceConflict` and offers its own way to rename.
            return Err(Error::SourceConflict {
                name: old.name.clone(),
                installed_from: old.source.to_string(),
                requested: manifest.source.to_string(),
            });
        }
        if old.pinned && !matches!(req.spec.version, VersionSpec::Exact(_)) {
            return Err(Error::Pinned {
                name: old.name.clone(),
                version: old.version.to_string(),
            });
        }
        let unversioned = !matches!(req.spec.version, VersionSpec::Exact(_));
        // The same floating tag can name a new build, which is not "already
        // installed" however equal the tags are.
        let same_build =
            old.channel != Channel::Dev || !rebuilt(&InstalledBuild::of(old), &release);
        if old.tag == release.tag && same_build && !req.force {
            // `pkg@1.2.0` asked for that version and keeps the old message.
            return Err(if unversioned && req.offer_update {
                Error::NoUpdate {
                    name: old.name.clone(),
                    version: old.version.to_string(),
                }
            } else {
                Error::AlreadyInstalled {
                    name: old.name.clone(),
                    version: old.version.to_string(),
                }
            });
        }
        if unversioned && req.offer_update && !req.force {
            return Err(Error::UpdateAvailable {
                name: old.name.clone(),
                installed: old.version.to_string(),
                latest: release.version.to_string(),
                tag: release.tag.clone(),
            });
        }
    }

    let chosen = choose_asset(cfg, platform.as_ref(), &release, &manifest, req)?;
    let asset = chosen.asset;
    report.debug(&format!(
        "selected {} — {}",
        asset.name, chosen.score.reason
    ));
    if chosen.score.emulated {
        report.warn(&format!(
            "{} is an {} build and will run under emulation",
            asset.name, chosen.score.arch
        ));
    }

    std::fs::create_dir_all(&cfg.cache_dir).map_err(|e| Error::io(&cfg.cache_dir, e))?;
    let unpack = tempfile::tempdir_in(&cfg.cache_dir).map_err(|e| Error::io(&cfg.cache_dir, e))?;

    // Local `.app` bundles are directories: copy the tree into the unpack root
    // rather than pretending they are a downloadable archive.
    let (sha256, asset_name, checksum_verified, provenance, payload) =
        if local_kind == Some(LocalKind::App) {
            let app_path = local_path.as_ref().ok_or_else(|| {
                Error::msg("internal error: local .app install without a recorded path")
            })?;
            report.stage(&label, Stage::Downloading);
            let dest_name = app_path
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_else(|| "App.app".into());
            let dest = unpack.path().join(&dest_name);
            crate::source::local::copy_tree(app_path, &dest)?;
            // The copy is hashed, not the original, so the digest describes exactly
            // what gets placed. Like a local file, a bundle has no published
            // checksum to require, but a lockfile's hash still holds it.
            let sha256 = crate::source::local::sha256_tree(&dest)?;
            progress.finish("copied");
            report.stage(&label, Stage::Verifying);
            check_locked(req, &manifest.name, &dest_name, &sha256)?;
            // A bundle on disk has no release to carry a signature, so a policy
            // that requires one cannot be met.
            let provenance = match &manifest.trust {
                Some(policy) => crate::trust::refuse(
                    policy,
                    &dest_name,
                    "a local app bundle has no published signature",
                    report,
                )?,
                None => None,
            };
            let payload = payload_root(unpack.path(), manifest.strip_prefix)?;
            (sha256, dest_name, false, provenance, payload)
        } else {
            // --- download -------------------------------------------------------
            report.stage(&label, Stage::Downloading);
            // A directory of its own, not a name under the cache. Two `prepare`s
            // run side by side, and an alias and a repo path naming the same
            // package would pick the same file name: they would overwrite each
            // other's archive, extract whichever landed last, and delete it from
            // under each other. The archive is staging, never a cache — it is
            // deleted as soon as the payload is unpacked — so a unique directory
            // costs nothing.
            let staging =
                tempfile::tempdir_in(&cfg.cache_dir).map_err(|e| Error::io(&cfg.cache_dir, e))?;
            let download_path = staging.path().join(sanitize_component(&asset.name));
            // For a local symlink, point the asset URL at the origin path so
            // LocalSource::download follows it; classification already recorded
            // that the user named a link.
            let mut asset = asset;
            if let Some(path) = &local_path {
                asset.url = path.to_string_lossy().into_owned();
            }
            let sha256 = source.download(&asset, &download_path, progress, &req.cancel)?;

            // --- checksum -------------------------------------------------------
            check_locked(req, &manifest.name, &asset.name, &sha256)?;

            report.stage(&label, Stage::Verifying);
            // Local packages never publish a checksum; requiring one would make
            // every `local:` install fail for a reason the user cannot fix.
            let require = if local_kind.is_some() {
                false
            } else {
                req.require_checksum || cfg.require_checksums
            };
            let checksum_verified = verify_checksum(
                source.as_ref(),
                &manifest.source.id,
                &release,
                &asset,
                &sha256,
                require,
                report,
            )?;
            refuse_checksum_downgrade(req, existing.as_ref(), checksum_verified, &asset)?;

            // --- signature ------------------------------------------------------
            // Before extraction: the unpacker is the widest attack surface ketch
            // has, and a file its publisher did not vouch for need not reach it.
            let provenance = crate::trust::verify(
                manifest.trust.as_ref(),
                source.as_ref(),
                &release,
                &asset,
                &download_path,
                &sha256,
                staging.path(),
                report,
            )?;

            // --- extract --------------------------------------------------------
            report.stage(&label, Stage::Extracting);
            let format = report
                .activity(&format!("extracting {}", asset.name))
                .run(|| {
                    crate::extract::extract_auto(
                        &download_path,
                        unpack.path(),
                        &platform.extractors(),
                        report,
                    )
                })?;
            report.debug(&format!("unpacked {} as {format}", asset.name));
            let payload = payload_root(unpack.path(), manifest.strip_prefix)?;
            (sha256, asset.name, checksum_verified, provenance, payload)
        };

    report.stage(&label, Stage::Trusting);
    let trust = check_trust(platform.as_ref(), cfg, &payload, &manifest.name, report);

    // Only the request's own opt-in, not `config.toml` or the manifest: both
    // are read again on every `upgrade`, so recording them here would keep a
    // package on prereleases after the person switched them off.
    let channel = Channel::of(&release, req.prerelease, req.draft);
    Ok(Prepared {
        label,
        manifest,
        origin,
        release,
        asset_name,
        sha256,
        checksum_verified,
        trust,
        provenance,
        link: req.link,
        bin: req.bin.clone(),
        locked_bin: req.locked_bin.clone(),
        payload,
        unpack,
        started,
        local_kind,
        local_path,
        cancel: req.cancel.clone(),
        channel,
    })
}

/// The binary chosen for a package whose manifest names none, out of several
/// in its payload that share the package's name.
struct BinPick {
    /// What placement links in place of everything it would discover: the
    /// chosen binary and every executable outside its family, in discovery
    /// order. Only the family members that lost are left out.
    specs: Vec<BinSpec>,
    /// The chosen file's name, as state remembers it.
    file: String,
    how: Picked,
}

/// Decide which binary `payload` links when the manifest names none and more
/// than one executable answers to `name` — before anything is placed, so the
/// choice is made once and by `bin_choice`'s fixed order rather than by the
/// order this platform's discovery sorts files in (B64). `None` leaves
/// placement to its usual rules: the manifest's `bin`, or every executable
/// discovered when nothing competes for the name. A `--bin` in `known` is
/// checked either way, so a name that matches nothing is an error, not a
/// silent no-op.
fn pick_bin(
    cx: &Ctx<'_>,
    platform: &dyn Platform,
    payload: &Path,
    manifest: Option<&crate::model::Manifest>,
    name: &str,
    known: bin_choice::Known<'_>,
) -> Result<Option<BinPick>> {
    let cfg = cx.cfg;
    if manifest.is_some_and(|m| !m.bin.is_empty()) {
        if let Some(flag) = known.flag {
            return Err(Error::msg(format!(
                "--bin `{flag}`: the manifest for `{name}` already names the binaries it links; \
                 change its `bin` instead"
            )));
        }
        return Ok(None);
    }
    let kind = manifest.map(|m| m.kind).unwrap_or_default();
    let found = platform.bin_candidates(payload, kind, name);
    let files: Vec<String> = found
        .iter()
        .map(|p| {
            p.file_name()
                .unwrap_or_default()
                .to_string_lossy()
                .into_owned()
        })
        .collect();
    let contenders = bin_choice::contenders(name, &files);
    if contenders.is_empty() {
        if let Some(flag) = known.flag {
            bin_choice::check_flag(flag, &files)?;
        }
        return Ok(None);
    }
    let names: Vec<String> = contenders.iter().map(|&i| files[i].clone()).collect();
    let hint = crate::manifest::user_manifest_path(cfg, name)
        .display()
        .to_string();
    let mut ask = |candidates: &[String]| cx.decider.choose_binary(name, candidates);
    let (i, how) = bin_choice::choose(name, &names, known, &mut ask, &hint)?;
    let chosen = contenders[i];
    // The exact paths, not globs or bare names: a release can carry a
    // completion script or a man page with the same file name elsewhere.
    let specs = found
        .iter()
        .zip(&files)
        .enumerate()
        .filter(|&(j, _)| j == chosen || !contenders.contains(&j))
        .map(|(_, (path, file))| BinSpec {
            path: Some(
                path.strip_prefix(payload)
                    .unwrap_or(path)
                    .to_string_lossy()
                    .into_owned(),
            ),
            name: Some(file.clone()),
        })
        .collect();
    Ok(Some(BinPick {
        specs,
        file: files[chosen].clone(),
        how,
    }))
}

/// What state remembers after a pick: a choice somebody made — now or before —
/// and otherwise whatever it remembered already, for the next release that
/// needs it. An exact name match is not a choice and is not recorded.
fn remembered_choice(pick: Option<&BinPick>, remembered: Option<&str>) -> Option<String> {
    match pick {
        Some(p) if p.how != Picked::Exact => Some(p.file.clone()),
        _ => remembered.map(str::to_string),
    }
}

/// Write a pick into the user manifest it came from, so the file names its
/// binaries from now on: the chosen one and every other it links. Best
/// effort: the install has already succeeded, and state remembers the choice
/// even when the file cannot be written.
fn record_in_manifest(
    path: &Path,
    manifest: &mut crate::model::Manifest,
    pick: &BinPick,
    report: &Report,
) {
    let commands: Vec<String> = pick
        .specs
        .iter()
        .filter_map(|s| s.name.as_deref())
        .map(|n| bin_choice::command_name(n).to_string())
        .collect();
    match crate::manifest::write_bins(path, &manifest.name, &commands) {
        Ok(true) => {
            let listed: Vec<String> = commands
                .iter()
                .map(|c| format!("{{ name = \"{c}\" }}"))
                .collect();
            report.note(&format!(
                "{} now names its binaries: bin = [{}]",
                path.display(),
                listed.join(", ")
            ));
            manifest.bin = commands
                .into_iter()
                .map(|c| BinSpec {
                    path: None,
                    name: Some(c),
                })
                .collect();
        }
        Ok(false) => {}
        Err(e) => report.warn(&format!(
            "could not record the chosen binary in {}: {e}",
            path.display()
        )),
    }
}

fn extra_placements(
    platform: &dyn Platform,
    extra_paths: &[crate::model::ExtraPath],
) -> Result<Vec<crate::extra::ExtraPlacement>> {
    crate::extra::plan(extra_paths, &platform.user_man_root(), |shell| {
        platform.completion_dir(shell)
    })
}

/// Commits a prepared package installation to the install tree and records its state.
///
/// The package payload is placed, links are created or updated, replaced content is
/// retired, and the resulting package is stored in `state`. Failures while placing
/// the payload are returned; cleanup and statistics failures are reported without
/// invalidating a successful installation.
///
/// # Examples
///
/// `prepared` is what [`prepare`] returned. Same network caveat as that example.
///
/// ```no_run
/// use ketch_core::config::Config;
/// use ketch_core::install::{commit, prepare, InstallRequest};
/// use ketch_core::model::PackageSpec;
/// use ketch_core::report::{Ctx, Report};
/// use ketch_core::source::SourceRegistry;
/// use ketch_core::state::State;
///
/// let report = Report::silent();
/// let cfg = Config::load(None, &report)?;
/// let cx = Ctx::new(&cfg, &report);
/// let sources = SourceRegistry::load(&cx);
/// let mut state = State::default();
/// let request = InstallRequest::new(PackageSpec::parse("ripgrep"));
/// let progress = report.download("ripgrep");
/// let prepared = prepare(&cx, &sources, &state, &request, &progress)?;
/// let _installed = commit(&cx, &mut state, prepared)?;
/// Ok::<(), ketch_core::error::Error>(())
/// ```
///
/// # Errors
///
/// Returns an error if the host platform cannot be resolved or the payload cannot
/// be placed.
pub fn commit(cx: &Ctx<'_>, state: &mut State, prepared: Prepared) -> Result<Installed> {
    let (cfg, report) = (cx.cfg, cx.report);
    let Prepared {
        label,
        manifest,
        origin,
        release,
        asset_name,
        sha256,
        checksum_verified,
        trust,
        provenance,
        link,
        bin,
        locked_bin,
        payload,
        unpack,
        started,
        local_kind,
        local_path,
        cancel,
        channel,
    } = prepared;
    // Before the first hook or file: later steps are short and leave the tree
    // consistent on their own, so this is the last point a stop is free.
    cancel.check()?;
    let mut manifest = manifest;
    let platform = crate::platform::host()?;
    report.stage(&label, Stage::Installing);

    // Read again rather than trusting what `prepare` saw: in a batch, another
    // package may have been placed since.
    let existing = state.get(&manifest.name).cloned();

    let version = release.version.to_string();
    let store_dir = cfg.package_dir(&manifest.name, &version);

    // --- sweep --------------------------------------------------------------
    // Before the hooks and before anything is placed: a leftover the sweep
    // cannot remove is a reason to stop, not to install beside it.
    sweep_swap_leftovers(cfg, &manifest.name, existing.as_ref())?;

    // --- hooks: before ------------------------------------------------------
    // Refused before anything is placed: a manifest that may not run hooks
    // must not install as though it had none, or the user never learns why
    // the hook they wrote did nothing.
    if !manifest.hooks.is_empty() && !hooks::allowed(&origin) {
        return Err(hooks::refusal(&origin, &manifest.name));
    }
    // A reinstall of the same version is an install, not an update: nothing
    // the update hooks exist to migrate has changed.
    let previous = existing
        .as_ref()
        .filter(|p| p.version != release.version)
        .map(|p| p.version.to_string());
    let (before, after) = match previous {
        Some(_) => (hooks::Event::BeforeUpdate, hooks::Event::AfterUpdate),
        None => (hooks::Event::BeforeInstall, hooks::Event::AfterInstall),
    };
    hooks::run(
        &manifest.hooks,
        before,
        &hooks::Context {
            name: &manifest.name,
            version: &version,
            previous: previous.as_deref(),
            prefix: &store_dir,
            bin_dir: &cfg.bin_dir,
            root: &cfg.root,
            report,
        },
    )?;

    // --- place --------------------------------------------------------------
    // Reinstalling the same version writes into the directory the current
    // install already occupies, and failing there must not delete it. A
    // retained copy of this version is the same: placement restores it on
    // failure, and deleting it afterwards would lose the rollback target.
    let in_place = existing
        .as_ref()
        .is_some_and(|p| p.prefix == store_dir || p.retained.iter().any(|r| r.prefix == store_dir));
    let mut orphan = ScopedDir((!in_place).then(|| store_dir.clone()));
    let extras = extra_placements(platform.as_ref(), &manifest.extra_paths)?;
    let remembered = existing.as_ref().and_then(|p| p.bin_choice.as_deref());
    let earlier: Vec<&str> = remembered
        .into_iter()
        .chain(locked_bin.as_deref())
        .collect();
    let known = bin_choice::Known {
        flag: bin.as_deref(),
        remembered: &earlier,
    };
    let pick = if link || known.flag.is_some() {
        pick_bin(
            cx,
            platform.as_ref(),
            &payload,
            Some(&manifest),
            &manifest.name,
            known,
        )?
    } else {
        None
    };
    let picked_specs = pick.as_ref().map(|p| p.specs.clone());
    let links = platform.place(&Placement {
        name: &manifest.name,
        payload_dir: &payload,
        store_dir: &store_dir,
        bin_dir: &cfg.bin_dir,
        apps_dir: &cfg.apps_dir,
        kind: manifest.kind,
        bin_specs: picked_specs.as_deref().unwrap_or(&manifest.bin),
        replacing: existing.as_ref().map(|p| p.links.as_slice()).unwrap_or(&[]),
        link_apps: cfg.link_apps,
        link,
        extras: &extras,
    })?;
    drop(unpack);

    // --- retire stale links; keep the old prefix for rollback -----------------
    let mut retained = existing
        .as_ref()
        .map(|p| p.retained.clone())
        .unwrap_or_default();
    if let Some(old) = &existing {
        let stale: Vec<LinkRecord> = old
            .links
            .iter()
            .filter(|l| !links.iter().any(|new| new.link == l.link))
            .cloned()
            .collect();
        // A failure here leaves a dangling link, not a broken install, so it is
        // reported rather than propagated.
        if let Err(e) = platform.unplace(&stale, report) {
            report.warn(&format!("could not remove old links for {}: {e}", old.name));
        }
        retain_replaced(cfg, old, &store_dir, &release.version, &mut retained);
    }

    if let (Some(pick), crate::model::ManifestOrigin::User(path)) = (&pick, &origin) {
        record_in_manifest(path, &mut manifest, pick, report);
    }
    let bin_choice = remembered_choice(pick.as_ref(), remembered);

    let package = InstalledPackage {
        name: manifest.name.clone(),
        version: release.version.clone(),
        source: manifest.source.clone(),
        tag: release.tag.clone(),
        target: platform.target(),
        asset_name,
        sha256,
        checksum_verified,
        installed_at: now_unix(),
        prefix: store_dir,
        links,
        pinned: existing.as_ref().is_some_and(|p| p.pinned),
        origin,
        manifest: Some(manifest),
        local_kind,
        local_path,
        trust,
        retained,
        provenance,
        bin_choice,
        channel,
        draft: release.draft,
        published_at: if channel == Channel::Dev {
            release.published_at.clone()
        } else {
            None
        },
    };
    state.insert(package.clone());
    orphan.keep();

    if let Some(manifest) = &package.manifest {
        hooks::run_or_warn(
            &manifest.hooks,
            after,
            &hooks::Context {
                name: &package.name,
                version: &version,
                previous: previous.as_deref(),
                prefix: &package.prefix,
                bin_dir: &cfg.bin_dir,
                root: &cfg.root,
                report,
            },
        );
    }

    // Everything above has already happened: the payload is placed and `state`
    // names it. Recording is the last thing and the least important thing, so
    // `stats::record` warns rather than returning — an install that succeeded
    // must not report failure because a statistic did not land.
    let replaced = existing.map(|p| p.version);
    let previous = replaced.as_ref().map(|v| v.to_string());
    let source = package.source.to_string();
    let target = package.target.to_string();
    crate::stats::record(
        cx,
        &crate::stats::install_event(
            &package,
            previous.as_deref(),
            i32::try_from(started.elapsed().as_millis()).ok(),
            &version,
            &source,
            &target,
        ),
    );

    Ok(Installed { package, replaced })
}

/// Install several packages: download and unpack them concurrently, place them
/// one at a time. One result per request, in request order.
///
/// The install tree is reached from `commit` alone, and exactly one thread is
/// ever inside it — so the store, the links and `state.json` see the same
/// sequence of writes a one-at-a-time batch would have made. What overlaps is
/// `prepare`, which writes nothing outside the cache and spends its time
/// waiting on a network.
///
/// A worker holds its slot until its package is placed, so at most `jobs`
/// unpacked payloads sit in the cache at once rather than the whole batch.
pub fn batch(
    cx: &Ctx<'_>,
    sources: &SourceRegistry,
    state: &mut State,
    reqs: &[InstallRequest],
    jobs: usize,
) -> Vec<Result<Installed>> {
    if jobs <= 1 || reqs.len() <= 1 {
        return reqs
            .iter()
            .map(|req| install(cx, sources, state, req))
            .collect();
    }

    // `prepare` reads a snapshot taken before the batch started, which is all
    // it needs: it decides whether an install is wanted at all. `commit` reads
    // the live state again before it places anything.
    let snapshot = state.clone();
    let live = Mutex::new(state);
    let bars = cx.report.batch();
    let next = AtomicUsize::new(0);
    let done: Mutex<Vec<(usize, Result<Installed>)>> = Mutex::new(Vec::with_capacity(reqs.len()));

    // Scoped threads so `cfg`, `sources` and the snapshot are borrowed rather
    // than cloned into an `Arc` apiece; the scope cannot outlive any of them.
    std::thread::scope(|scope| {
        for _ in 0..jobs.min(reqs.len()) {
            scope.spawn(|| loop {
                let i = next.fetch_add(1, Ordering::Relaxed);
                let Some(req) = reqs.get(i) else { return };
                let sink = bars.download(&req.spec.label());
                let result = prepare(cx, sources, &snapshot, req, &sink).and_then(|p| {
                    let mut live = live.lock().unwrap_or_else(|e| e.into_inner());
                    commit(cx, &mut live, p)
                });
                done.lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .push((i, result));
            });
        }
    });

    let mut results = done.into_inner().unwrap_or_else(|e| e.into_inner());
    // Workers finish in whatever order the network allowed. The user asked in
    // a particular one, and that is the order everything downstream reports in.
    results.sort_by_key(|(i, _)| *i);
    results.into_iter().map(|(_, result)| result).collect()
}

/// The hooks a recorded package may run: none when its manifest has none, and
/// an error when the manifest's origin may not run any.
fn recorded_hooks(pkg: &InstalledPackage) -> Result<Option<&crate::model::Hooks>> {
    let Some(h) = pkg
        .manifest
        .as_ref()
        .map(|m| &m.hooks)
        .filter(|h| !h.is_empty())
    else {
        return Ok(None);
    };
    if hooks::allowed(&pkg.origin) {
        Ok(Some(h))
    } else {
        Err(hooks::refusal(&pkg.origin, &pkg.name))
    }
}

/// Removes a package's links and stored files, then removes its state entry.
///
/// # Errors
///
/// Returns an error if the package is not installed or its links cannot be removed.
///
/// # Examples
///
/// Does not run here: it removes whatever `example` is in the real ketch root.
///
/// ```no_run
/// use ketch_core::config::Config;
/// use ketch_core::install::uninstall;
/// use ketch_core::report::{Ctx, Report};
/// use ketch_core::state::State;
///
/// let report = Report::silent();
/// let cfg = Config::load(None, &report)?;
/// let cx = Ctx::new(&cfg, &report);
/// let mut state = State::load(&cfg)?;
/// let removed = uninstall(&cx, &mut state, "example")?;
/// assert_eq!(removed.name, "example");
/// Ok::<(), ketch_core::error::Error>(())
/// ```
pub fn uninstall(cx: &Ctx<'_>, state: &mut State, name: &str) -> Result<InstalledPackage> {
    let (cfg, report) = (cx.cfg, cx.report);
    let pkg = state
        .find(name)
        .cloned()
        .ok_or_else(|| Error::NotInstalled(name.to_string()))?;
    let platform = crate::platform::host()?;
    let version = pkg.version.to_string();
    let hook_ctx = hooks::Context {
        name: &pkg.name,
        version: &version,
        previous: None,
        prefix: &pkg.prefix,
        bin_dir: &cfg.bin_dir,
        root: &cfg.root,
        report,
    };
    // Skipped rather than refused, unlike install and rollback: an uninstall
    // must stay possible whatever the recorded manifest says.
    let pkg_hooks = recorded_hooks(&pkg).unwrap_or_else(|_| {
        report.warn(&format!(
            "{}: skipping hooks from a {} manifest",
            pkg.name,
            pkg.origin.tier()
        ));
        None
    });
    if let Some(h) = pkg_hooks {
        hooks::run(h, hooks::Event::BeforeUninstall, &hook_ctx)?;
    }
    platform.unplace(&pkg.links, report)?;
    // The whole `store/<name>/` goes, not just the prefixes state knows of:
    // a `.incoming` or `.old` sibling a failed swap left behind would
    // otherwise keep the package's folder alive forever.
    let package_dir = remove_package_dir(cfg, &pkg.name, report);
    for prefix in std::iter::once(&pkg.prefix).chain(pkg.retained.iter().map(|r| &r.prefix)) {
        // Already attempted as part of the package folder; a second try
        // would only repeat its warning.
        if package_dir
            .as_deref()
            .is_some_and(|d| prefix.starts_with(d))
        {
            continue;
        }
        remove_store_dir(cfg, prefix, report);
    }
    state.remove(&pkg.name);
    if let Some(h) = pkg_hooks {
        hooks::run_or_warn(h, hooks::Event::AfterUninstall, &hook_ctx);
    }

    // Recorded after the removal has happened, for the same reason `commit`
    // records last: the package is gone either way.
    let version = pkg.version.to_string();
    let source = pkg.source.to_string();
    let target = pkg.target.to_string();
    crate::stats::record(
        cx,
        &crate::stats::uninstall_event(&pkg, &version, &source, &target),
    );

    Ok(pkg)
}

/// Re-create links for an already-installed package.
pub fn relink(cx: &Ctx<'_>, state: &mut State, name: &str) -> Result<()> {
    let (cfg, report) = (cx.cfg, cx.report);
    let pkg = state
        .find(name)
        .cloned()
        .ok_or_else(|| Error::NotInstalled(name.to_string()))?;
    if !pkg.prefix.is_dir() {
        return Err(Error::EmptyPayload(pkg.prefix.clone()));
    }
    let platform = crate::platform::host()?;
    let manifest = pkg.manifest.clone();
    // Place first, like `commit`: a failed placement must not take the
    // working links with it. `replacing` lets the new links reclaim the
    // destinations this package already owns.
    let extras = extra_placements(
        platform.as_ref(),
        manifest
            .as_ref()
            .map(|m| m.extra_paths.as_slice())
            .unwrap_or(&[]),
    )?;
    let remembered = pkg.bin_choice.as_deref();
    let pick = pick_bin(
        cx,
        platform.as_ref(),
        &pkg.prefix,
        manifest.as_ref(),
        &pkg.name,
        bin_choice::Known {
            flag: None,
            remembered: remembered.as_slice(),
        },
    )?;
    let picked_specs = pick.as_ref().map(|p| p.specs.clone());
    let links = platform.place(&Placement {
        name: &pkg.name,
        // Already in the store: placement is idempotent over its own output.
        payload_dir: &pkg.prefix,
        store_dir: &pkg.prefix,
        bin_dir: &cfg.bin_dir,
        apps_dir: &cfg.apps_dir,
        kind: manifest.as_ref().map(|m| m.kind).unwrap_or_default(),
        bin_specs: picked_specs
            .as_deref()
            .or_else(|| manifest.as_ref().map(|m| m.bin.as_slice()))
            .unwrap_or(&[]),
        replacing: &pkg.links,
        link_apps: cfg.link_apps,
        link: true,
        extras: &extras,
    })?;

    let stale: Vec<LinkRecord> = pkg
        .links
        .iter()
        .filter(|l| !links.iter().any(|new| new.link == l.link))
        .cloned()
        .collect();
    if let Err(e) = platform.unplace(&stale, report) {
        report.warn(&format!("could not remove old links for {}: {e}", pkg.name));
    }

    if let Some(entry) = state.get_mut(&pkg.name) {
        entry.links = links;
        entry.bin_choice = remembered_choice(pick.as_ref(), remembered);
    }
    Ok(())
}

/// Remove links but keep the package installed.
pub fn unlink(cx: &Ctx<'_>, state: &mut State, name: &str) -> Result<()> {
    let report = cx.report;
    let pkg = state
        .find(name)
        .cloned()
        .ok_or_else(|| Error::NotInstalled(name.to_string()))?;
    crate::platform::host()?.unplace(&pkg.links, report)?;
    if let Some(entry) = state.get_mut(&pkg.name) {
        entry.links.clear();
    }
    Ok(())
}

/// Hold `name` at its installed version, or let it go again. A pinned package
/// is never offered an update and `upgrade` leaves it alone. Changes only the
/// record; the caller saves `state`.
pub fn pin(state: &mut State, name: &str, pinned: bool) -> Result<InstalledPackage> {
    let installed = state
        .find(name)
        .map(|p| p.name.clone())
        .ok_or_else(|| Error::NotInstalled(name.to_string()))?;
    let entry = state
        .get_mut(&installed)
        .ok_or_else(|| Error::NotInstalled(name.to_string()))?;
    entry.pinned = pinned;
    Ok(entry.clone())
}

/// Switch an installed package to a retained prefix already on disk.
///
/// Never downloads. Preflights every destination (via `place`) before the
/// current links are retired, so a blocked path leaves the working version
/// in place.
pub fn rollback(
    cx: &Ctx<'_>,
    state: &mut State,
    name: &str,
    to: Option<&str>,
) -> Result<Installed> {
    let (cfg, report) = (cx.cfg, cx.report);
    let pkg = state
        .find(name)
        .cloned()
        .ok_or_else(|| Error::NotInstalled(name.to_string()))?;
    if pkg.pinned {
        return Err(Error::Pinned {
            name: pkg.name,
            version: pkg.version.to_string(),
        });
    }
    let idx = select_retained(&pkg, to)?;
    let target = pkg.retained[idx].clone();
    if !target.prefix.is_dir() {
        return Err(Error::msg(format!(
            "retained {} {} is missing from {}",
            pkg.name,
            target.version,
            target.prefix.display()
        )));
    }

    let platform = crate::platform::host()?;
    let version = target.version.to_string();
    let manifest = pkg.manifest.clone();
    let linked = !pkg.links.is_empty();

    // A rollback is an update back to the retained version, so it runs the
    // update hooks — and, as at install, refuses a manifest that may not run
    // any before a single link moves.
    let pkg_hooks = recorded_hooks(&pkg)?.cloned();
    let leaving = pkg.version.to_string();
    if let Some(h) = &pkg_hooks {
        hooks::run(
            h,
            hooks::Event::BeforeUpdate,
            &hooks::Context {
                name: &pkg.name,
                version: &version,
                previous: Some(&leaving),
                prefix: &target.prefix,
                bin_dir: &cfg.bin_dir,
                root: &cfg.root,
                report,
            },
        )?;
    }

    let extras = extra_placements(
        platform.as_ref(),
        manifest
            .as_ref()
            .map(|m| m.extra_paths.as_slice())
            .unwrap_or(&[]),
    )?;
    let remembered = pkg.bin_choice.as_deref();
    let pick = if linked {
        pick_bin(
            cx,
            platform.as_ref(),
            &target.prefix,
            manifest.as_ref(),
            &pkg.name,
            bin_choice::Known {
                flag: None,
                remembered: remembered.as_slice(),
            },
        )?
    } else {
        None
    };
    let bin_choice = remembered_choice(pick.as_ref(), remembered);
    let picked_specs = pick.as_ref().map(|p| p.specs.clone());
    let links = platform.place(&Placement {
        name: &pkg.name,
        payload_dir: &target.prefix,
        store_dir: &target.prefix,
        bin_dir: &cfg.bin_dir,
        apps_dir: &cfg.apps_dir,
        kind: manifest.as_ref().map(|m| m.kind).unwrap_or_default(),
        bin_specs: picked_specs
            .as_deref()
            .or_else(|| manifest.as_ref().map(|m| m.bin.as_slice()))
            .unwrap_or(&[]),
        replacing: &pkg.links,
        link_apps: cfg.link_apps,
        link: linked,
        extras: &extras,
    })?;

    let stale: Vec<LinkRecord> = pkg
        .links
        .iter()
        .filter(|l| !links.iter().any(|new| new.link == l.link))
        .cloned()
        .collect();
    if let Err(e) = platform.unplace(&stale, report) {
        report.warn(&format!("could not remove old links for {}: {e}", pkg.name));
    }

    let replaced = pkg.version.clone();
    let snapshot = RetainedVersion::from_installed(&pkg);
    let mut retained = pkg.retained.clone();
    retained.remove(idx);
    retained.retain(|r| r.prefix != pkg.prefix && r.version != pkg.version);
    if pkg.prefix.is_dir() && is_inside_store(&cfg.store_dir, &pkg.prefix) {
        retained.insert(0, snapshot);
    }

    let mut package = pkg;
    package.version = target.version;
    package.prefix = target.prefix;
    package.sha256 = target.sha256;
    package.checksum_verified = target.checksum_verified;
    package.trust = target.trust;
    package.provenance = target.provenance;
    package.links = links;
    package.tag = target.tag;
    package.asset_name = target.asset_name;
    package.installed_at = now_unix();
    package.retained = retained;
    package.bin_choice = bin_choice;
    state.insert(package.clone());

    let previous = replaced.to_string();
    let version = package.version.to_string();
    if let Some(h) = &pkg_hooks {
        hooks::run_or_warn(
            h,
            hooks::Event::AfterUpdate,
            &hooks::Context {
                name: &package.name,
                version: &version,
                previous: Some(&previous),
                prefix: &package.prefix,
                bin_dir: &cfg.bin_dir,
                root: &cfg.root,
                report,
            },
        );
    }
    let source = package.source.to_string();
    let target_spec = package.target.to_string();
    crate::stats::record(
        cx,
        &crate::stats::rollback_event(&package, &previous, &version, &source, &target_spec),
    );

    Ok(Installed {
        package,
        replaced: Some(replaced),
    })
}

fn select_retained(pkg: &InstalledPackage, to: Option<&str>) -> Result<usize> {
    if pkg.retained.is_empty() {
        return Err(Error::NoRetained(pkg.name.clone()));
    }
    match to {
        None => Ok(0),
        Some(spec) => {
            if pkg.version.matches_request(spec) || pkg.tag.eq_ignore_ascii_case(spec.trim()) {
                return Err(Error::AlreadyInstalled {
                    name: pkg.name.clone(),
                    version: pkg.version.to_string(),
                });
            }
            pkg.find_retained(spec).map(|(i, _)| i).ok_or_else(|| {
                let available = pkg
                    .retained
                    .iter()
                    .map(|r| r.version.to_string())
                    .collect::<Vec<_>>()
                    .join(", ");
                Error::msg(format!(
                    "`{}` has no retained version {spec} (retained: {available})",
                    pkg.name
                ))
            })
        }
    }
}

/// Drop retained prefixes beyond `keep`, oldest first. The current version
/// is never removed. Missing prefixes are dropped from state too.
pub fn prune(cx: &Ctx<'_>, state: &mut State, name: &str, keep: u32) -> Result<Vec<Version>> {
    let (cfg, report) = (cx.cfg, cx.report);
    let pkg = state
        .find(name)
        .cloned()
        .ok_or_else(|| Error::NotInstalled(name.to_string()))?;
    let keep = keep as usize;
    let mut kept = Vec::new();
    let mut dropped = Vec::new();
    for previous in pkg.retained {
        if previous.prefix.is_dir() && kept.len() < keep {
            kept.push(previous);
        } else {
            // A state file written before retention dropped the live version
            // can still list it; the entry goes, the directory every link
            // points into stays.
            if previous.prefix.exists() && previous.prefix != pkg.prefix {
                remove_store_dir(cfg, &previous.prefix, report);
            }
            dropped.push(previous.version);
        }
    }
    if let Some(entry) = state.get_mut(&pkg.name) {
        entry.retained = kept;
    }
    Ok(dropped)
}

/// Newest release available for an installed package, for `outdated`/`upgrade`.
/// `draft` widens the lookup to drafts for this run, as `prerelease` does to
/// prereleases.
pub fn latest_release(
    sources: &SourceRegistry,
    pkg: &InstalledPackage,
    prerelease: bool,
    draft: bool,
) -> Result<Release> {
    let build = InstalledBuild::of(pkg);
    release_of(
        sources,
        &pkg.source,
        build.followed_tag(),
        &upgrade_opts(pkg, prerelease, draft),
    )
}

/// The options `latest_release` looks with, which `is_upgrade` needs too: what
/// the lookup allowed decides what may count as an upgrade.
pub fn upgrade_opts(pkg: &InstalledPackage, prerelease: bool, draft: bool) -> ListOpts {
    let mut opts = installed_list_opts(pkg, prerelease);
    opts.include_draft |= draft;
    opts.include_prerelease |= draft;
    opts
}

/// The release an installed package is compared against: the newest under
/// `opts`, or for a package on a floating tag that tag again, since it has no
/// successor to look for, only a newer build under the same name. Drafts are
/// not asked of a source that has none: across many packages, one without
/// drafts is simply answered without them.
pub fn release_of(
    sources: &SourceRegistry,
    source: &PackageRef,
    tag: Option<&str>,
    opts: &ListOpts,
) -> Result<Release> {
    let found = sources.for_ref(source)?;
    let mut opts = opts.clone();
    opts.include_draft &= found.supports_drafts();
    let version = match tag {
        Some(tag) => VersionSpec::Exact(tag.to_string()),
        None => VersionSpec::Latest,
    };
    found.resolve(&source.id, &version, &opts)
}

/// What an upgrade decision needs to know of an installed package, apart from
/// the rest of its record, so `ketch list` can carry it for every row.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InstalledBuild {
    pub name: String,
    pub version: Version,
    pub tag: String,
    pub channel: Channel,
    /// The installed release was a draft.
    pub draft: bool,
    pub asset_name: String,
    pub sha256: String,
    pub published_at: Option<String>,
}

impl InstalledBuild {
    pub fn of(pkg: &InstalledPackage) -> InstalledBuild {
        InstalledBuild {
            name: pkg.name.clone(),
            version: pkg.version.clone(),
            tag: pkg.tag.clone(),
            channel: pkg.channel,
            draft: pkg.draft,
            asset_name: pkg.asset_name.clone(),
            sha256: pkg.sha256.clone(),
            published_at: pkg.published_at.clone(),
        }
    }

    /// The tag a package on a floating tag keeps resolving; `None` for every
    /// other channel, which looks for the newest release instead.
    pub fn followed_tag(&self) -> Option<&str> {
        (self.channel == Channel::Dev).then_some(self.tag.as_str())
    }
}

/// Whether `latest`, found for `have` with `opts`, should replace what is
/// installed. The one rule behind `ketch upgrade`, `ketch outdated` and
/// `ketch list`.
///
/// Versions are compared, not tags: a retagged release is not an upgrade, and
/// neither is a source that briefly reports an older one, or a prerelease
/// `opts` did not ask for — GitHub's `latest` can be an unflagged `-rc` tag.
/// A draft is the exception: once published, the release under its tag is
/// what the draft was a preview of. A draft that is no longer listed was
/// deleted or published under another tag; that is said, and is not an error.
/// A package on a floating tag is upgraded by a new build under that tag.
pub fn is_upgrade(
    report: &Report,
    have: &InstalledBuild,
    latest: &Release,
    opts: &ListOpts,
) -> bool {
    if have.draft && latest.tag == have.tag {
        return !latest.draft;
    }
    if have.channel == Channel::Dev && latest.tag == have.tag {
        return rebuilt(have, latest);
    }
    let unasked = !opts.include_prerelease && (latest.prerelease || latest.version.is_prerelease());
    if latest.tag != have.tag && latest.version > have.version && !unasked {
        return true;
    }
    if have.draft {
        report.warn(&format!(
            "{}: the draft release {} it was installed from is gone; \
             it was deleted or published under another tag",
            have.name, have.tag
        ));
    }
    false
}

/// Whether `release`, under the tag `pkg` was installed from, is another
/// build than the one installed.
///
/// The installed asset's digest decides when the source publishes one: it
/// describes the bytes, which a republish changes even when the date does
/// not. Without it the publication date is all there is, and a date that is
/// missing on either side is no evidence of a new build.
pub(crate) fn rebuilt(pkg: &InstalledBuild, release: &Release) -> bool {
    let Some(asset) = release.assets.iter().find(|a| a.name == pkg.asset_name) else {
        // Nightlies often stamp a date or commit into their file names, so a
        // vanished asset is a new build rather than an incomplete release.
        return true;
    };
    match &asset.digest {
        Some(digest) if digest.algo == "sha256" => !digest.hex.eq_ignore_ascii_case(&pkg.sha256),
        _ => matches!(
            (&pkg.published_at, &release.published_at),
            (Some(installed), Some(now)) if installed != now
        ),
    }
}

/// The request that moves installed `pkg` to the release tagged `tag`, as
/// `ketch upgrade` makes it: the exact tag that was reported, so nothing can
/// change between the plan a person approved and what is installed.
pub fn upgrade_request(
    cfg: &Config,
    pkg: &InstalledPackage,
    tag: &str,
    bin: Option<String>,
    cancel: Cancel,
) -> InstallRequest {
    InstallRequest {
        spec: PackageSpec {
            raw: format!("{}@{tag}", pkg.source),
            reference: Some(pkg.source.clone()),
            alias: None,
            version: VersionSpec::Exact(tag.to_string()),
        },
        force: true,
        // The tag is exact, so these only carry the channel forward: an
        // upgrade that lands on a stable release must not end prereleases, or
        // drafts.
        prerelease: pkg.channel == Channel::Pre,
        draft: pkg.channel == Channel::Draft,
        // A package installed with --no-link stays unlinked.
        link: !pkg.links.is_empty(),
        require_checksum: cfg.require_checksums,
        asset_override: None,
        expected_sha256: None,
        expected_published: None,
        // The installed name, which `--name` may have chosen. Resolving the
        // source alone would infer another and install a second copy.
        name_override: Some(pkg.name.clone()),
        bin,
        locked_bin: None,
        offer_update: false,
        allow_unverified: false,
        cancel,
    }
}

/// The listing options `latest_release` asks an installed package's source
/// with; `ketch list` needs them apart from the lookup, to key its cache.
pub fn installed_list_opts(pkg: &InstalledPackage, prerelease: bool) -> ListOpts {
    // A manifest that asks for prereleases got one at install time; asking for
    // `latest` without it here would then report the package as up to date
    // forever, however many prereleases it has moved through since.
    let draft = pkg.channel == Channel::Draft;
    ListOpts {
        include_prerelease: prerelease
            || draft
            || pkg.channel == Channel::Pre
            || pkg.manifest.as_ref().is_some_and(|m| m.prerelease),
        include_draft: draft,
        ..Default::default()
    }
}

/// Rank a release's assets for this platform, best first. Assets the platform
/// rejects are dropped, so an empty result means nothing here is installable.
pub fn score_assets(
    cfg: &Config,
    platform: &dyn Platform,
    release: &Release,
    selector: &AssetSelector,
) -> Vec<ScoredAsset> {
    crate::resolve::score_assets(cfg, platform, release, selector)
}

// ---------------------------------------------------------------------------
// Steps
// ---------------------------------------------------------------------------

fn choose_asset(
    cfg: &Config,
    platform: &dyn Platform,
    release: &Release,
    manifest: &crate::model::Manifest,
    req: &InstallRequest,
) -> Result<ScoredAsset> {
    if let Some(wanted) = &req.asset_override {
        let asset = release
            .assets
            .iter()
            .find(|a| a.name == *wanted)
            .ok_or_else(|| {
                Error::msg(format!(
                    "release `{}` has no asset named `{wanted}`",
                    release.tag
                ))
            })?;
        return Ok(ScoredAsset {
            asset: asset.clone(),
            score: AssetScore {
                score: i32::MAX,
                arch: cfg.target.arch,
                emulated: false,
                reason: "chosen with --asset".to_string(),
            },
        });
    }

    crate::resolve::score_assets(cfg, platform, release, &manifest.asset)
        .into_iter()
        .next()
        .ok_or_else(|| Error::NoCompatibleAsset {
            id: manifest.source.to_string(),
            tag: release.tag.clone(),
            target: cfg.target.to_string(),
        })
}

/// Returns whether the hash was confirmed against a published checksum.
pub(crate) fn verify_checksum(
    source: &dyn crate::source::Source,
    id: &str,
    release: &Release,
    asset: &ReleaseAsset,
    actual: &str,
    require: bool,
    report: &Report,
) -> Result<bool> {
    let published = match &asset.digest {
        Some(digest) => Some(digest.hex.clone()),
        // Only worth the extra requests when the asset carries no digest.
        None => match source.checksums(id, release, &asset.name) {
            Ok(published) => published.get(&asset.name).cloned(),
            // `Ok(empty)` is the only "publishes no checksum" answer; an
            // error means the lookup itself failed (network, rate limit)
            // while a checksum may well exist. The install continues
            // first-use below, but the user hears about it.
            Err(e) => {
                report.warn(&format!(
                    "could not read published checksums for {}: {e}; \
                     recording the downloaded hash on first use",
                    asset.name
                ));
                None
            }
        },
    };

    match published {
        Some(expected) if expected.eq_ignore_ascii_case(actual) => Ok(true),
        Some(expected) => Err(Error::ChecksumMismatch {
            name: asset.name.clone(),
            expected,
            actual: actual.to_string(),
        }),
        None if require => Err(Error::ChecksumMissing(asset.name.clone())),
        None => {
            report.debug(&format!(
                "{} publishes no checksum; recording {} on first use",
                asset.name,
                &actual[..actual.len().min(12)]
            ));
            Ok(false)
        }
    }
}

/// Refuse to replace a package installed against a published checksum with a
/// release that publishes none, unless the request says to.
///
/// Without this, a release whose checksums were removed — by its publisher or
/// by whoever took over the repository — installs with only the first-use note,
/// and the one check that catches a replaced asset quietly disappears. A
/// lockfile hash is a stronger pin than either, so it stands in for consent.
fn refuse_checksum_downgrade(
    req: &InstallRequest,
    existing: Option<&InstalledPackage>,
    checksum_verified: bool,
    asset: &ReleaseAsset,
) -> Result<()> {
    let dropped = !checksum_verified && existing.is_some_and(|old| old.checksum_verified);
    if dropped && !req.allow_unverified && req.expected_sha256.is_none() {
        return Err(Error::ChecksumDropped {
            name: existing.map(|old| old.name.clone()).unwrap_or_default(),
            asset: asset.name.clone(),
        });
    }
    Ok(())
}

/// Apply `strip_prefix`, or unwrap the single wrapper directory most tarballs
/// use, so the payload root is where the files actually are.
fn payload_root(unpacked: &Path, strip: Option<usize>) -> Result<PathBuf> {
    match strip {
        Some(0) | None => crate::extract::unwrap_single_dir(unpacked),
        Some(n) => {
            let mut root = unpacked.to_path_buf();
            for _ in 0..n {
                root = crate::extract::unwrap_single_dir(&root)?;
            }
            Ok(root)
        }
    }
}

/// Hold a payload to the hash a lockfile recorded for it.
///
/// Checked apart from the source's own checksum: that one says the download
/// was not corrupted, this one says the release is still the one that was
/// locked — and a release that changed under a tag it already published is
/// exactly what a lockfile exists to catch. Every payload path calls it before
/// anything is placed, so no kind of package can skip the lock.
/// Refuse a floating tag that a lockfile pinned to one build and that now
/// names another, before anything is downloaded. A source that does not say
/// when a release was published leaves nothing to compare.
fn check_published(req: &InstallRequest, name: &str, release: &Release) -> Result<()> {
    match (&req.expected_published, &release.published_at) {
        (Some(locked), Some(now)) if locked != now => Err(Error::msg(format!(
            "{name}: {} now names another build (published {now}, the lockfile \
             recorded {locked}); run `ketch lock` again to accept it",
            release.tag
        ))),
        _ => Ok(()),
    }
}

fn check_locked(req: &InstallRequest, name: &str, asset: &str, sha256: &str) -> Result<()> {
    match &req.expected_sha256 {
        Some(expected) if !expected.eq_ignore_ascii_case(sha256) => Err(Error::msg(format!(
            "{name}: {asset} does not match the lockfile\n  locked {expected}\n  got    {sha256}\n\
             The release was replaced after the lock was written. Install it \
             deliberately and re-run `ketch lock` rather than accepting a payload \
             nobody recorded."
        ))),
        _ => Ok(()),
    }
}

/// Inspect the payload and strip quarantine only when the platform says the
/// code is genuinely trusted. A failed check never blocks an install the user
/// explicitly asked for; it is reported instead.
fn check_trust(
    platform: &dyn Platform,
    cfg: &Config,
    payload: &Path,
    name: &str,
    report: &Report,
) -> TrustResult {
    let verdict = match platform.verify_trust(payload) {
        Ok(v) => v,
        Err(e) => {
            report.debug(&format!("trust check failed for {name}: {e}"));
            return TrustResult::NotApplicable;
        }
    };
    match &verdict {
        TrustVerdict::Trusted { authority } => report.debug(&format!("signed by {authority}")),
        TrustVerdict::Weak { detail } => report.debug(&format!("weak signature: {detail}")),
        TrustVerdict::Untrusted { detail } => report.debug(&format!("unsigned: {detail}")),
        TrustVerdict::NotApplicable => {}
    }
    if cfg.strip_quarantine && verdict.may_strip_quarantine() {
        if let Err(e) = platform.clear_quarantine(payload) {
            report.debug(&format!("could not clear quarantine: {e}"));
        }
    }
    trust_result(verdict)
}

fn trust_result(verdict: TrustVerdict) -> TrustResult {
    match verdict {
        TrustVerdict::Trusted { authority } => TrustResult::Trusted { authority },
        TrustVerdict::Weak { detail } => TrustResult::Weak { detail },
        TrustVerdict::Untrusted { detail } => TrustResult::Untrusted { detail },
        TrustVerdict::NotApplicable => TrustResult::NotApplicable,
    }
}

/// Record the replaced prefix instead of deleting it. Missing or out-of-store
/// prefixes are not eligible: promising a version that is already gone is
/// worse than keeping nothing.
///
/// The new version leaves the list too: upgrading back to a version kept
/// since a rollback makes its prefix the live one, and a retained entry still
/// naming it would let `prune` delete the store every link points into.
fn retain_replaced(
    cfg: &Config,
    old: &InstalledPackage,
    new_prefix: &Path,
    new_version: &Version,
    retained: &mut Vec<RetainedVersion>,
) {
    retained.retain(|r| {
        r.prefix != old.prefix
            && r.version != old.version
            && r.prefix != new_prefix
            && r.version != *new_version
    });
    if old.prefix == new_prefix {
        return;
    }
    if !old.prefix.is_dir() || !is_inside_store(&cfg.store_dir, &old.prefix) {
        return;
    }
    retained.insert(0, RetainedVersion::from_installed(old));
}

/// Delete a store directory, and its now-empty package parent.
///
/// Refuses anything outside the store: a corrupted state file must never turn
/// an uninstall into a `rm -rf` of somewhere else. Lexical `starts_with` alone
/// is not enough — `store/pkg/../../outside` starts with `store`, and a
/// symlink planted inside the store can point anywhere — so the check
/// resolves both paths when it can and rejects `..` components otherwise.
fn remove_store_dir(cfg: &Config, prefix: &Path, report: &Report) {
    if !is_inside_store(&cfg.store_dir, prefix) {
        report.warn(&format!(
            "refusing to remove {} — it is not inside the ketch store",
            prefix.display()
        ));
        return;
    }
    if let Err(e) = std::fs::remove_dir_all(prefix) {
        if e.kind() != std::io::ErrorKind::NotFound {
            report.warn(&format!("could not remove {}: {e}", prefix.display()));
        }
    }
    if let Some(parent) = prefix.parent().filter(|p| *p != cfg.store_dir) {
        let _ = std::fs::remove_dir(parent); // only succeeds when empty
    }
}

/// Remove the `<version>.incoming` and `<version>.old` folders an interrupted
/// swap in `move_into_store` left in `store/<name>/`.
///
/// Their own cleanup is best effort, so one that failed would otherwise wait
/// for the next swap of the same version — and a stale `.incoming` is where
/// that swap stages the new payload. A prefix the package still records is
/// never touched, whatever its name ends with.
///
/// # Errors
///
/// Returns an error naming the leftover that could not be removed.
fn sweep_swap_leftovers(
    cfg: &Config,
    name: &str,
    existing: Option<&InstalledPackage>,
) -> Result<()> {
    let Some(dir) = package_dir_candidate(cfg, name) else {
        return Ok(());
    };
    let Ok(entries) = std::fs::read_dir(&dir) else {
        return Ok(());
    };
    let recorded: Vec<&Path> = existing
        .into_iter()
        .flat_map(|p| std::iter::once(&p.prefix).chain(p.retained.iter().map(|r| &r.prefix)))
        .map(PathBuf::as_path)
        .collect();
    for entry in entries.flatten() {
        let path = entry.path();
        let file_name = entry.file_name();
        let leftover = file_name
            .to_str()
            .is_some_and(|n| n.ends_with(".incoming") || n.ends_with(".old"));
        if !leftover || recorded.contains(&path.as_path()) {
            continue;
        }
        // `file_type` does not follow a symlink, so a link is removed as a
        // link and never followed out of the store.
        let removed = match entry.file_type() {
            Ok(t) if t.is_dir() => std::fs::remove_dir_all(&path),
            _ => std::fs::remove_file(&path),
        };
        match removed {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(Error::io(&path, e)),
        }
    }
    Ok(())
}

/// `store/<name>/` when `name` is one plain path component and the folder
/// exists inside the store after symlinks resolve.
///
/// The folder's own name on disk must be `name` byte for byte. A file system
/// that folds case (APFS, NTFS) or drops a trailing dot (Windows) opens
/// `store/ripgrep` for `RipGrep` or `ripgrep.`, and a lookup by path alone
/// would hand an installed package's folder to whoever spelled it nearly.
fn package_dir_candidate(cfg: &Config, name: &str) -> Option<PathBuf> {
    use std::path::Component;
    let mut components = Path::new(name).components();
    if !matches!(
        (components.next(), components.next()),
        (Some(Component::Normal(c)), None) if c == name
    ) {
        return None;
    }
    let dir = cfg.store_dir.join(name);
    if dir.symlink_metadata().is_err() || !is_inside_store(&cfg.store_dir, &dir) {
        return None;
    }
    let spelled_so = std::fs::read_dir(&cfg.store_dir)
        .ok()?
        .flatten()
        .any(|entry| entry.file_name() == std::ffi::OsStr::new(name));
    spelled_so.then_some(dir)
}

/// `store/<name>/` when it is a folder no state entry accounts for: a
/// candidate as `remove_package_dir` takes it, holding no prefix any installed
/// package records. What `ketch uninstall` of an unknown name may offer to
/// remove.
pub fn leftover_package_dir(cfg: &Config, state: &State, name: &str) -> Option<PathBuf> {
    let dir = package_dir_candidate(cfg, name)?;
    let recorded = state
        .packages
        .values()
        .flat_map(|p| std::iter::once(&p.prefix).chain(p.retained.iter().map(|r| &r.prefix)));
    for prefix in recorded {
        if *prefix == dir || is_inside_store(&dir, prefix) {
            return None;
        }
    }
    Some(dir)
}

/// Delete `store/<name>/` whole, with whatever is left in it.
///
/// Returns the folder when it was a candidate — a direct child of the store
/// named exactly like the package, and inside it after symlinks resolve — so
/// the caller knows which prefixes it already covered. A name that is not one
/// plain path component is never joined onto the store.
pub fn remove_package_dir(cfg: &Config, name: &str, report: &Report) -> Option<PathBuf> {
    let dir = package_dir_candidate(cfg, name)?;
    if let Err(e) = std::fs::remove_dir_all(&dir) {
        if e.kind() != std::io::ErrorKind::NotFound {
            report.warn(&format!("could not remove {}: {e}", dir.display()));
        }
    }
    Some(dir)
}

/// True when `prefix` is a proper subdirectory of `store`, after resolving
/// symlinks and rejecting `..` escapes a corrupted state file could invent.
fn is_inside_store(store: &Path, prefix: &Path) -> bool {
    use std::path::Component;
    // When both exist, resolve symlinks so a decoy link inside the store
    // cannot point `remove_dir_all` at an outside victim. Prefer this before
    // the lexical walk: canonicalize also folds Windows short/long names.
    if let (Ok(store), Ok(resolved)) = (
        fs_kit::path::canonicalize(store),
        fs_kit::path::canonicalize(prefix),
    ) {
        return crate::platform::path_is_strict_within(&resolved, &store);
    }
    // Lexical fallback for a missing payload (idempotent uninstall). A root
    // written with a `..` in it (`KETCH_ROOT=../ketch`) carries that component
    // in both paths, and refusing it there would quietly disable every cleanup
    // — uninstall would drop the state entry and leave the payload forever.
    // Only the part *below* the store is checked for `..`.
    if !crate::platform::path_is_strict_within(prefix, store) {
        return false;
    }
    let below_comps: Vec<_> = {
        let store_len = store.components().count();
        prefix.components().skip(store_len).collect()
    };
    // Case-insensitive within() may match a differently-cased store prefix
    // whose component count still equals `store_len`.
    if below_comps.is_empty() {
        return false;
    }
    if below_comps
        .iter()
        .any(|c| matches!(c, Component::ParentDir))
    {
        return false;
    }
    true
}

/// Deletes a store directory when dropped, unless the install got far enough to
/// keep it.
///
/// Placement moves the payload into the store before creating any link, so a
/// failure after that point — a binary name another package already owns, a
/// payload with nothing runnable in it — leaves a full store directory that no
/// state entry mentions: invisible to `ketch list`, out of reach of `ketch
/// uninstall`, and taken for a finished install by the next run.
struct ScopedDir(Option<PathBuf>);

impl ScopedDir {
    fn keep(&mut self) {
        self.0 = None;
    }
}

impl Drop for ScopedDir {
    fn drop(&mut self) {
        if let Some(path) = &self.0 {
            let _ = std::fs::remove_dir_all(path);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{Arch, Os, ReleaseAsset, TargetSpec};
    use std::collections::BTreeMap;

    struct FakePlatform;

    impl Platform for FakePlatform {
        fn id(&self) -> &str {
            "fake"
        }
        fn target(&self) -> TargetSpec {
            TargetSpec {
                os: Os::MacOs,
                arch: Arch::Aarch64,
            }
        }
        fn score_asset(&self, name: &str, _emu: bool) -> Option<AssetScore> {
            // Stands in for a real platform: macOS assets only, longer names
            // never outrank shorter ones by accident.
            name.contains("darwin").then(|| AssetScore {
                score: 50,
                arch: Arch::Aarch64,
                emulated: false,
                reason: "fake".into(),
            })
        }
        fn extractors(&self) -> Vec<Box<dyn crate::extract::Extractor>> {
            Vec::new()
        }
        fn place(&self, _plan: &Placement<'_>) -> Result<Vec<LinkRecord>> {
            Ok(Vec::new())
        }
        fn unplace(&self, _links: &[LinkRecord], _report: &crate::report::Report) -> Result<()> {
            Ok(())
        }
        fn is_executable(&self, _path: &Path) -> bool {
            true
        }
        fn doctor(&self, _cfg: &Config) -> Vec<crate::platform::DoctorCheck> {
            Vec::new()
        }
    }

    fn asset(name: &str) -> ReleaseAsset {
        ReleaseAsset {
            name: name.to_string(),
            url: format!("https://example.invalid/{name}"),
            size: 1,
            content_type: None,
            digest: None,
            headers: BTreeMap::new(),
        }
    }

    fn release(names: &[&str]) -> Release {
        Release {
            version: Version::parse("1.0.0"),
            tag: "v1.0.0".into(),
            prerelease: false,
            draft: false,
            published_at: None,
            notes: None,
            assets: names.iter().map(|n| asset(n)).collect(),
        }
    }

    fn config() -> Config {
        let mut cfg = Config::load(
            Some(std::env::temp_dir().join("ketch-test-root")),
            &crate::report::Report::silent(),
        )
        .unwrap();
        cfg.target = TargetSpec {
            os: Os::MacOs,
            arch: Arch::Aarch64,
        };
        cfg
    }

    #[test]
    fn drops_assets_the_platform_cannot_run() {
        let picked = score_assets(
            &config(),
            &FakePlatform,
            &release(&["tool-linux.tar.gz", "tool-darwin.tar.gz"]),
            &AssetSelector::default(),
        );
        assert_eq!(picked.len(), 1);
        assert_eq!(picked[0].asset.name, "tool-darwin.tar.gz");
    }

    #[test]
    fn exclude_wins_over_include_and_over_the_target_pin() {
        let cfg = config();
        let selector = AssetSelector {
            include: vec!["*darwin*".into()],
            exclude: vec!["*.dmg".into()],
            target: BTreeMap::from([("macos-aarch64".to_string(), "*.dmg".to_string())]),
        };
        let picked = score_assets(
            &cfg,
            &FakePlatform,
            &release(&["tool-darwin.dmg", "tool-darwin.tar.gz"]),
            &selector,
        );
        // The pin would have taken the dmg; the exclusion removes it first, and
        // with a pin present the tarball is not considered either.
        assert!(picked.is_empty());
    }

    #[test]
    fn a_target_pin_overrides_platform_scoring() {
        let cfg = config();
        let selector = AssetSelector {
            target: BTreeMap::from([(
                "macos-aarch64".to_string(),
                "*-mac-universal.zip".to_string(),
            )]),
            ..Default::default()
        };
        // `mac` alone would score None from FakePlatform; the pin still wins.
        let picked = score_assets(
            &cfg,
            &FakePlatform,
            &release(&["tool-darwin.tar.gz", "tool-mac-universal.zip"]),
            &selector,
        );
        assert_eq!(picked.len(), 1);
        assert_eq!(picked[0].asset.name, "tool-mac-universal.zip");
    }

    #[test]
    fn refuses_to_delete_outside_the_store() {
        let cfg = config();
        let outside = std::env::temp_dir().join("ketch-not-the-store");
        std::fs::create_dir_all(&outside).unwrap();
        remove_store_dir(&cfg, &outside, &Report::silent());
        assert!(outside.is_dir(), "a path outside the store must survive");
        std::fs::remove_dir_all(&outside).ok();
    }

    #[test]
    fn inside_store_accepts_ascii_case_folded_prefix() {
        let root = tempfile::tempdir().unwrap();
        let cfg = Config::load(
            Some(root.path().join("ketch")),
            &crate::report::Report::silent(),
        )
        .unwrap();
        cfg.ensure_dirs().unwrap();
        let payload = cfg.store_dir.join("rg").join("1.0.0");
        std::fs::create_dir_all(&payload).unwrap();
        assert!(
            is_inside_store(&cfg.store_dir, &payload),
            "same-case payload must be inside"
        );
        // Build a prefix that differs only in ASCII case of one component.
        // On Windows this is a real uninstall/GC failure mode; on Unix the
        // helper's case arm is cfg'd out so we only assert the positive path
        // plus the shared strict-within helper.
        #[cfg(windows)]
        {
            let store = cfg.store_dir.clone();
            let alt = PathBuf::from(
                store
                    .to_string_lossy()
                    .chars()
                    .map(|c| {
                        if c.is_ascii_lowercase() {
                            c.to_ascii_uppercase()
                        } else if c.is_ascii_uppercase() {
                            c.to_ascii_lowercase()
                        } else {
                            c
                        }
                    })
                    .collect::<String>(),
            )
            .join("rg")
            .join("1.0.0");
            assert!(
                is_inside_store(&cfg.store_dir, &alt),
                "Windows must treat differently-cased store prefixes as inside: {} vs {}",
                cfg.store_dir.display(),
                alt.display()
            );
        }
    }

    #[test]
    fn refuses_dotdot_escape_out_of_the_store() {
        let root = tempfile::tempdir().unwrap();
        let cfg = Config::load(
            Some(root.path().join("ketch")),
            &crate::report::Report::silent(),
        )
        .unwrap();
        cfg.ensure_dirs().unwrap();
        let victim = root.path().join("victim");
        std::fs::create_dir_all(&victim).unwrap();
        std::fs::write(victim.join("keep"), b"safe").unwrap();

        // Lexical child of the store that resolves to `victim` via `..`.
        let escape = cfg
            .store_dir
            .join("pkg")
            .join("1.0.0")
            .join("..")
            .join("..")
            .join("..")
            .join("victim");
        assert!(
            escape.starts_with(&cfg.store_dir),
            "precondition: lexical starts_with alone would allow this"
        );
        remove_store_dir(&cfg, &escape, &Report::silent());
        assert!(
            victim.join("keep").is_file(),
            "`..` must not let uninstall delete outside the store"
        );
    }

    #[cfg(unix)]
    #[test]
    fn refuses_a_store_symlink_that_points_outside() {
        let root = tempfile::tempdir().unwrap();
        let cfg = Config::load(
            Some(root.path().join("ketch")),
            &crate::report::Report::silent(),
        )
        .unwrap();
        cfg.ensure_dirs().unwrap();
        let victim = root.path().join("victim");
        std::fs::create_dir_all(&victim).unwrap();
        std::fs::write(victim.join("keep"), b"safe").unwrap();

        let decoy = cfg.store_dir.join("decoy");
        std::os::unix::fs::symlink(&victim, &decoy).unwrap();
        remove_store_dir(&cfg, &decoy, &Report::silent());
        assert!(
            victim.join("keep").is_file(),
            "a symlink inside the store must not delete its outside target"
        );
        // The decoy symlink itself may remain; the point is the target survived.
        assert!(decoy.symlink_metadata().is_ok());
    }

    #[test]
    fn the_package_folder_goes_whole_with_stale_swap_siblings() {
        let root = tempfile::tempdir().unwrap();
        let cfg = Config::load(
            Some(root.path().join("ketch")),
            &crate::report::Report::silent(),
        )
        .unwrap();
        cfg.ensure_dirs().unwrap();
        let folder = cfg.store_dir.join("tool");
        std::fs::create_dir_all(folder.join("1.0.0.old")).unwrap();
        std::fs::write(folder.join("1.0.0.old").join("stale"), b"x").unwrap();

        assert_eq!(
            remove_package_dir(&cfg, "tool", &Report::silent()),
            Some(folder.clone())
        );
        assert!(!folder.exists());
        assert!(cfg.store_dir.is_dir(), "the store itself stays");
    }

    #[test]
    fn the_sweep_takes_swap_leftovers_and_keeps_every_version() {
        let root = tempfile::tempdir().unwrap();
        let cfg = Config::load(Some(root.path().join("ketch")), &Report::silent()).unwrap();
        cfg.ensure_dirs().unwrap();
        let folder = cfg.store_dir.join("tool");
        std::fs::create_dir_all(folder.join("1.0.0")).unwrap();
        std::fs::create_dir_all(folder.join("1.1.0.incoming")).unwrap();
        std::fs::write(folder.join("1.1.0.incoming").join("planted"), b"x").unwrap();
        std::fs::create_dir_all(folder.join("1.0.0.old")).unwrap();
        std::fs::write(folder.join("0.9.0.old"), b"a file, not a folder").unwrap();

        sweep_swap_leftovers(&cfg, "tool", None).unwrap();

        let mut left: Vec<_> = std::fs::read_dir(&folder)
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        left.sort();
        assert_eq!(left, vec!["1.0.0".to_string()]);
    }

    #[test]
    fn the_sweep_never_takes_a_prefix_the_package_records() {
        let root = tempfile::tempdir().unwrap();
        let cfg = Config::load(Some(root.path().join("ketch")), &Report::silent()).unwrap();
        cfg.ensure_dirs().unwrap();
        // A release really versioned like a leftover.
        let prefix = cfg.store_dir.join("tool").join("2.old");
        std::fs::create_dir_all(&prefix).unwrap();
        let pkg = installed("tool", "2.old", prefix.clone());

        sweep_swap_leftovers(&cfg, "tool", Some(&pkg)).unwrap();

        assert!(prefix.is_dir());
    }

    #[test]
    fn the_sweep_is_a_no_op_without_a_package_folder() {
        let root = tempfile::tempdir().unwrap();
        let cfg = Config::load(Some(root.path().join("ketch")), &Report::silent()).unwrap();
        cfg.ensure_dirs().unwrap();
        sweep_swap_leftovers(&cfg, "tool", None).unwrap();
        sweep_swap_leftovers(&cfg, "../outside", None).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn a_leftover_the_sweep_cannot_remove_stops_the_install_and_is_named() {
        use std::os::unix::fs::PermissionsExt;
        let root = tempfile::tempdir().unwrap();
        let cfg = Config::load(Some(root.path().join("ketch")), &Report::silent()).unwrap();
        cfg.ensure_dirs().unwrap();
        let folder = cfg.store_dir.join("tool");
        std::fs::create_dir_all(folder.join("1.0.0.old")).unwrap();
        std::fs::set_permissions(&folder, std::fs::Permissions::from_mode(0o555)).unwrap();

        let result = sweep_swap_leftovers(&cfg, "tool", None);
        std::fs::set_permissions(&folder, std::fs::Permissions::from_mode(0o755)).unwrap();

        let err = result.expect_err("a read-only package folder cannot be swept");
        assert!(err.to_string().contains("1.0.0.old"), "{err}");
    }

    #[test]
    fn a_package_name_that_is_not_one_component_is_never_removed() {
        let root = tempfile::tempdir().unwrap();
        let cfg = Config::load(
            Some(root.path().join("ketch")),
            &crate::report::Report::silent(),
        )
        .unwrap();
        cfg.ensure_dirs().unwrap();
        let nested = cfg.store_dir.join("a").join("b");
        std::fs::create_dir_all(&nested).unwrap();

        for name in ["a/b", "..", ".", "", "a/../a"] {
            assert_eq!(
                remove_package_dir(&cfg, name, &Report::silent()),
                None,
                "{name:?}"
            );
        }
        assert!(nested.is_dir());
        assert!(cfg.store_dir.is_dir());
    }

    #[test]
    fn a_folder_holding_a_recorded_prefix_is_not_a_leftover() {
        let root = tempfile::tempdir().unwrap();
        let cfg = Config::load(Some(root.path().join("ketch")), &Report::silent()).unwrap();
        cfg.ensure_dirs().unwrap();
        let prefix = cfg.package_dir("tool", "1.0.0");
        std::fs::create_dir_all(&prefix).unwrap();
        let orphan = cfg.store_dir.join("ghost");
        std::fs::create_dir_all(&orphan).unwrap();
        let mut state = State::default();
        // Recorded under another key, so `State::find("tool")` misses it.
        let mut pkg = installed("renamed", "1.0.0", prefix);
        pkg.name = "renamed".into();
        state.insert(pkg);

        assert_eq!(leftover_package_dir(&cfg, &state, "tool"), None);
        assert_eq!(leftover_package_dir(&cfg, &state, "ghost"), Some(orphan));
    }

    #[test]
    fn a_name_spelled_differently_on_disk_is_not_a_candidate() {
        let root = tempfile::tempdir().unwrap();
        let cfg = Config::load(Some(root.path().join("ketch")), &Report::silent()).unwrap();
        cfg.ensure_dirs().unwrap();
        let folder = cfg.store_dir.join("tool");
        std::fs::create_dir_all(&folder).unwrap();

        for name in ["TOOL", "Tool"] {
            assert_eq!(package_dir_candidate(&cfg, name), None, "{name}");
        }
        assert_eq!(package_dir_candidate(&cfg, "tool"), Some(folder));
    }

    #[test]
    fn a_missing_package_folder_is_not_a_candidate() {
        let root = tempfile::tempdir().unwrap();
        let cfg = Config::load(
            Some(root.path().join("ketch")),
            &crate::report::Report::silent(),
        )
        .unwrap();
        cfg.ensure_dirs().unwrap();
        assert_eq!(remove_package_dir(&cfg, "tool", &Report::silent()), None);
    }

    #[cfg(unix)]
    #[test]
    fn a_package_folder_linked_outside_the_store_is_left_alone() {
        let root = tempfile::tempdir().unwrap();
        let cfg = Config::load(
            Some(root.path().join("ketch")),
            &crate::report::Report::silent(),
        )
        .unwrap();
        cfg.ensure_dirs().unwrap();
        let victim = root.path().join("victim");
        std::fs::create_dir_all(&victim).unwrap();
        std::fs::write(victim.join("keep"), b"safe").unwrap();
        std::os::unix::fs::symlink(&victim, cfg.store_dir.join("tool")).unwrap();

        assert_eq!(remove_package_dir(&cfg, "tool", &Report::silent()), None);
        assert!(victim.join("keep").is_file());
    }

    #[test]
    fn a_root_written_with_dotdot_still_cleans_up_after_itself() {
        let tmp = tempfile::tempdir().unwrap();
        let work = tmp.path().join("work");
        std::fs::create_dir_all(&work).unwrap();
        // `KETCH_ROOT=../ketch` keeps the `..` in the root path, and so in
        // every store prefix below it. Those are the root's components, not the
        // state file's, and refusing them disables every cleanup there is.
        let cfg = Config::load(
            Some(work.join("../ketch")),
            &crate::report::Report::silent(),
        )
        .unwrap();
        cfg.ensure_dirs().unwrap();
        let prefix = cfg.store_dir.join("tool").join("1.0.0");
        std::fs::create_dir_all(&prefix).unwrap();
        std::fs::write(prefix.join("tool"), b"x").unwrap();

        remove_store_dir(&cfg, &prefix, &Report::silent());

        assert!(
            !prefix.exists(),
            "a `..` that came from the root must not stop the payload being removed"
        );
    }

    fn installed(name: &str, version: &str, prefix: PathBuf) -> InstalledPackage {
        InstalledPackage {
            name: name.into(),
            version: Version::parse(version),
            source: crate::model::PackageRef::github("o/r"),
            tag: format!("v{version}"),
            target: TargetSpec::host(),
            asset_name: "a.tar.gz".into(),
            sha256: "0".repeat(64),
            checksum_verified: true,
            installed_at: 0,
            prefix,
            links: Vec::new(),
            pinned: false,
            origin: crate::model::ManifestOrigin::Inferred,
            manifest: None,
            local_kind: None,
            local_path: None,
            trust: TrustResult::default(),
            retained: Vec::new(),
            provenance: None,
            bin_choice: None,
            channel: Default::default(),
            draft: false,
            published_at: None,
        }
    }

    #[test]
    fn an_unverified_release_replacing_a_verified_install_needs_consent() {
        let spec = || PackageSpec::parse("o/r");
        let verified = installed("r", "1.0.0", PathBuf::from("/nowhere"));
        let unverified = InstalledPackage {
            checksum_verified: false,
            ..installed("r", "1.0.0", PathBuf::from("/nowhere"))
        };
        let next = asset("r-2.0.0.tar.gz");
        let plain = InstallRequest::new(spec());

        let refused = refuse_checksum_downgrade(&plain, Some(&verified), false, &next);
        assert!(
            matches!(refused, Err(Error::ChecksumDropped { ref name, .. }) if name == "r"),
            "{refused:?}"
        );
        // A verified release, a fresh install and an install that was never
        // verified lose nothing, so they are not asked about.
        assert!(refuse_checksum_downgrade(&plain, Some(&verified), true, &next).is_ok());
        assert!(refuse_checksum_downgrade(&plain, None, false, &next).is_ok());
        assert!(refuse_checksum_downgrade(&plain, Some(&unverified), false, &next).is_ok());

        let consented = InstallRequest {
            allow_unverified: true,
            ..InstallRequest::new(spec())
        };
        assert!(refuse_checksum_downgrade(&consented, Some(&verified), false, &next).is_ok());
        let locked = InstallRequest {
            expected_sha256: Some("0".repeat(64)),
            ..InstallRequest::new(spec())
        };
        assert!(refuse_checksum_downgrade(&locked, Some(&verified), false, &next).is_ok());
    }

    #[test]
    fn retain_replaced_keeps_an_eligible_prefix_and_skips_the_same_one() {
        let root = tempfile::tempdir().unwrap();
        let cfg = Config::load(
            Some(root.path().join("ketch")),
            &crate::report::Report::silent(),
        )
        .unwrap();
        cfg.ensure_dirs().unwrap();
        let old_prefix = cfg.package_dir("tool", "1.0.0");
        std::fs::create_dir_all(&old_prefix).unwrap();
        let new_prefix = cfg.package_dir("tool", "2.0.0");
        let old = installed("tool", "1.0.0", old_prefix.clone());

        let mut retained = Vec::new();
        let v2 = Version::parse("2.0.0");
        retain_replaced(&cfg, &old, &new_prefix, &v2, &mut retained);
        assert_eq!(retained.len(), 1);
        assert_eq!(retained[0].version.to_string(), "1.0.0");
        assert_eq!(retained[0].prefix, old_prefix);
        assert!(old_prefix.is_dir(), "retention must not delete the prefix");

        retain_replaced(&cfg, &old, &old_prefix, &old.version, &mut retained);
        assert!(
            retained.is_empty(),
            "an in-place reinstall must not retain itself"
        );
    }

    #[test]
    fn upgrading_back_to_a_retained_version_drops_its_retained_entry() {
        let root = tempfile::tempdir().unwrap();
        let cfg = Config::load(Some(root.path().join("ketch")), &Report::silent()).unwrap();
        cfg.ensure_dirs().unwrap();
        let v1_prefix = cfg.package_dir("tool", "1.0.0");
        let v2_prefix = cfg.package_dir("tool", "2.0.0");
        std::fs::create_dir_all(&v1_prefix).unwrap();
        std::fs::create_dir_all(&v2_prefix).unwrap();
        // What a rollback from 2.0.0 to 1.0.0 leaves behind.
        let mut rolled_back = installed("tool", "1.0.0", v1_prefix.clone());
        let mut retained = vec![RetainedVersion::from_installed(&installed(
            "tool",
            "2.0.0",
            v2_prefix.clone(),
        ))];
        rolled_back.retained = retained.clone();

        retain_replaced(
            &cfg,
            &rolled_back,
            &v2_prefix,
            &Version::parse("2.0.0"),
            &mut retained,
        );

        assert_eq!(retained.len(), 1, "{retained:?}");
        assert_eq!(retained[0].prefix, v1_prefix);
    }

    #[test]
    fn prune_never_removes_the_live_prefix_a_stale_entry_names() {
        let root = tempfile::tempdir().unwrap();
        let cfg = Config::load(Some(root.path().join("ketch")), &Report::silent()).unwrap();
        cfg.ensure_dirs().unwrap();
        let report = Report::silent();
        let cx = Ctx::new(&cfg, &report);
        let live = cfg.package_dir("tool", "2.0.0");
        let old = cfg.package_dir("tool", "1.0.0");
        std::fs::create_dir_all(&live).unwrap();
        std::fs::create_dir_all(&old).unwrap();
        let mut pkg = installed("tool", "2.0.0", live.clone());
        pkg.retained = vec![
            RetainedVersion::from_installed(&installed("tool", "1.0.0", old.clone())),
            RetainedVersion::from_installed(&installed("tool", "2.0.0", live.clone())),
        ];
        let mut state = State::default();
        state.insert(pkg);

        prune(&cx, &mut state, "tool", 1).unwrap();

        assert!(live.is_dir(), "the live prefix must survive");
        assert!(old.is_dir(), "the one kept version stays");
        let kept = &state.get("tool").unwrap().retained;
        assert_eq!(kept.len(), 1);
        assert_eq!(kept[0].prefix, old);
    }

    #[test]
    fn select_retained_defaults_to_the_previous_and_names_a_miss() {
        let mut pkg = installed("tool", "2.0.0", PathBuf::from("/store/tool/2.0.0"));
        assert!(matches!(
            select_retained(&pkg, None),
            Err(Error::NoRetained(_))
        ));
        pkg.retained
            .push(RetainedVersion::from_installed(&installed(
                "tool",
                "1.0.0",
                PathBuf::from("/store/tool/1.0.0"),
            )));
        assert_eq!(select_retained(&pkg, None).unwrap(), 0);
        assert_eq!(select_retained(&pkg, Some("1.0.0")).unwrap(), 0);
        assert!(select_retained(&pkg, Some("0.9.0")).is_err());
    }

    /// A scratch ketch root and a local program to install from it.
    #[cfg(unix)]
    fn local_fixture(dir: &Path, name: &str) -> (Config, PathBuf) {
        use std::os::unix::fs::PermissionsExt;
        let cfg = Config::load(Some(dir.join("root")), &Report::silent()).unwrap();
        let program = dir.join(name);
        std::fs::write(&program, "#!/bin/sh\necho hi\n").unwrap();
        std::fs::set_permissions(&program, std::fs::Permissions::from_mode(0o755)).unwrap();
        (cfg, program)
    }

    #[cfg(unix)]
    fn local_request(program: &Path, cancel: &Cancel) -> InstallRequest {
        let mut req =
            InstallRequest::new(PackageSpec::parse(&format!("local:{}", program.display())));
        req.cancel = cancel.clone();
        req
    }

    #[test]
    #[cfg(unix)]
    fn an_install_cancelled_before_it_commits_leaves_no_store_folder_or_state_entry() {
        let dir = tempfile::tempdir().unwrap();
        let (cfg, program) = local_fixture(dir.path(), "canceltool");
        let report = Report::silent();
        let cx = Ctx::new(&cfg, &report);
        let sources = SourceRegistry::load(&cx);
        let mut state = State::default();
        let cancel = Cancel::new();
        let req = local_request(&program, &cancel);

        let prepared = prepare(&cx, &sources, &state, &req, &crate::report::SilentProgress)
            .expect("prepare succeeds");
        // The host pressed Stop after the download, before anything was placed.
        cancel.cancel();
        let err = commit(&cx, &mut state, prepared).expect_err("commit must stop");

        assert!(matches!(err, Error::Cancelled), "got {err}");
        assert!(state.packages.is_empty(), "no state entry");
        assert!(
            !cfg.store_dir.join("canceltool").exists(),
            "no store folder"
        );
        let staged = std::fs::read_dir(&cfg.cache_dir).map(|d| d.count());
        assert_eq!(staged.unwrap_or(0), 0, "temp dir removed");
        assert!(!cfg.bin_dir.join("canceltool").exists(), "no link");
    }

    #[test]
    #[cfg(unix)]
    fn a_cancelled_token_stops_a_batch_before_any_package_is_prepared() {
        let dir = tempfile::tempdir().unwrap();
        let (cfg, program) = local_fixture(dir.path(), "batchtool");
        let report = Report::silent();
        let cx = Ctx::new(&cfg, &report);
        let sources = SourceRegistry::load(&cx);
        let mut state = State::default();
        let cancel = Cancel::new();
        cancel.cancel();
        let reqs = vec![
            local_request(&program, &cancel),
            local_request(&program, &cancel),
        ];

        for jobs in [1, 2] {
            let outcomes = batch(&cx, &sources, &mut state, &reqs, jobs);
            assert_eq!(outcomes.len(), 2);
            assert!(outcomes.iter().all(|o| matches!(o, Err(Error::Cancelled))));
        }
        assert!(state.packages.is_empty());
        assert!(!cfg.store_dir.exists() || std::fs::read_dir(&cfg.store_dir).unwrap().count() == 0);
    }

    #[test]
    #[cfg(unix)]
    fn two_operations_in_one_process_run_one_after_the_other() {
        let dir = tempfile::tempdir().unwrap();
        let (cfg, first) = local_fixture(dir.path(), "firsttool");
        let (_, second) = local_fixture(dir.path(), "secondtool");
        let report = Report::silent();
        let cx = Ctx::new(&cfg, &report);
        let sources = SourceRegistry::load(&cx);
        let mut state = State::default();

        for program in [&first, &second] {
            // What a host does per operation: lock, run, release.
            let _lock = crate::state::Lock::acquire(&cx).unwrap();
            install(
                &cx,
                &sources,
                &mut state,
                &local_request(program, &Cancel::new()),
            )
            .unwrap();
        }
        assert_eq!(state.packages.len(), 2);
    }

    #[test]
    #[cfg(unix)]
    fn a_reinstall_whose_linking_fails_after_the_swap_puts_the_previous_store_back() {
        let dir = tempfile::tempdir().unwrap();
        let (cfg, program) = local_fixture(dir.path(), "swaptool");
        let report = Report::silent();
        let cx = Ctx::new(&cfg, &report);
        let sources = SourceRegistry::load(&cx);
        let mut state = State::default();
        let req = local_request(&program, &Cancel::new());
        install(&cx, &sources, &mut state, &req).unwrap();
        let before = state.get("swaptool").cloned().expect("installed");
        // Marks the tree state names, so a swapped-in copy cannot pass for it.
        std::fs::write(before.prefix.join("previous"), "kept").unwrap();
        // A local reinstall lands on the same version, so the swap goes into
        // the prefix state already records — the case the orphan guard skips.
        // A file where the bin dir was passes preflight (nothing occupies the
        // link) and fails only when linking creates the dir, after the swap.
        std::fs::remove_dir_all(&cfg.bin_dir).unwrap();
        std::fs::write(&cfg.bin_dir, "").unwrap();

        let reinstall = InstallRequest {
            force: true,
            ..req.clone()
        };
        let err = install(&cx, &sources, &mut state, &reinstall).expect_err("linking must fail");
        // Any earlier failure would leave the store untouched and prove nothing.
        assert!(
            err.to_string().contains(&cfg.bin_dir.display().to_string()),
            "{err}"
        );

        let after = state.get("swaptool").expect("state entry survives");
        assert_eq!(after.prefix, before.prefix);
        assert_eq!(
            std::fs::read_to_string(before.prefix.join("previous")).unwrap(),
            "kept",
            "the store state names is the one that was there before"
        );
        let mut retired = before.prefix.clone().into_os_string();
        retired.push(".old");
        assert!(!Path::new(&retired).exists(), "no retired copy left behind");
    }

    /// The event sequence a front end draws an install from: every stage in
    /// order, the download as one task that ends, and nothing printed.
    #[cfg(unix)]
    #[test]
    fn a_local_install_reports_each_stage_through_the_reporter() {
        use crate::report::{Event, Recorder, Task};
        use std::os::unix::fs::PermissionsExt;

        let tmp = tempfile::tempdir().unwrap();
        let recorder = std::sync::Arc::new(Recorder::default());
        let report = Report::shared(recorder.clone());
        let cfg = Config::load(Some(tmp.path().join("root")), &report).unwrap();
        let cx = Ctx::new(&cfg, &report);
        let tool = tmp.path().join("tool");
        std::fs::write(&tool, "#!/bin/sh\necho tool\n").unwrap();
        std::fs::set_permissions(&tool, std::fs::Permissions::from_mode(0o755)).unwrap();
        let sources = SourceRegistry::builtin_only(&cx);
        let mut state = State::default();
        let spec = PackageSpec::parse(&format!("local:{}", tool.display()));

        install(&cx, &sources, &mut state, &InstallRequest::new(spec)).unwrap();

        let events = recorder.events();
        let stages: Vec<Stage> = events
            .iter()
            .filter_map(|e| match e {
                Event::Step { stage, .. } => Some(*stage),
                _ => None,
            })
            .collect();
        assert_eq!(
            stages,
            [
                Stage::Resolving,
                Stage::Downloading,
                Stage::Verifying,
                Stage::Extracting,
                Stage::Trusting,
                Stage::Installing,
            ]
        );
        let download = events
            .iter()
            .find_map(|e| match e {
                Event::Began {
                    id,
                    task: Task::Download { batch: None, .. },
                } => Some(*id),
                _ => None,
            })
            .expect("the download is announced");
        assert!(
            events
                .iter()
                .any(|e| matches!(e, Event::Ended { id, .. } if *id == download)),
            "a download that arrived ends rather than being abandoned: {events:#?}"
        );
        assert!(
            events.iter().any(|e| matches!(
                e,
                Event::Status { verb, detail } if verb == "resolving" && detail.starts_with("tool ")
            )),
            "{events:#?}"
        );
        assert!(
            !events.iter().any(|e| matches!(e, Event::Warn { .. })),
            "a clean install warns about nothing: {events:#?}"
        );
    }

    /// Answers the binary question from a script and remembers what it was
    /// asked, so a test sees the question as the front end would.
    struct Scripted {
        pick: Option<usize>,
        asked: std::sync::Mutex<Vec<(String, Vec<String>)>>,
    }

    impl Scripted {
        fn answering(pick: Option<usize>) -> Self {
            Scripted {
                pick,
                asked: Default::default(),
            }
        }
    }

    impl crate::decide::Decider for Scripted {
        fn choose_binary(&self, package: &str, candidates: &[String]) -> Option<usize> {
            self.asked
                .lock()
                .unwrap()
                .push((package.to_string(), candidates.to_vec()));
            self.pick
        }

        fn stop_processes(&self, _occupants: &[crate::process::Occupant]) -> bool {
            false
        }

        fn confirm_remove_leftover(&self, _path: &Path) -> bool {
            false
        }
    }

    /// A payload with two executables that share the package name and none
    /// named exactly like it, so only a decision can pick between them.
    fn ambiguous_payload(dir: &Path) {
        for stem in ["rtok-cli", "rtok-hook"] {
            let file = dir.join(if cfg!(windows) {
                format!("{stem}.exe")
            } else {
                stem.to_string()
            });
            std::fs::write(&file, "#!/bin/sh\n").unwrap();
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                std::fs::set_permissions(&file, std::fs::Permissions::from_mode(0o755)).unwrap();
            }
        }
    }

    fn pick_with(decider: &dyn crate::decide::Decider) -> Result<Option<BinPick>> {
        let tmp = tempfile::tempdir().unwrap();
        let payload = tmp.path().join("payload");
        std::fs::create_dir(&payload).unwrap();
        ambiguous_payload(&payload);
        let report = Report::silent();
        let cfg = Config::load(Some(tmp.path().join("root")), &report).unwrap();
        let cx = Ctx::new(&cfg, &report).with_decider(decider);
        let platform = crate::platform::host().unwrap();
        pick_bin(
            &cx,
            platform.as_ref(),
            &payload,
            None,
            "rtok",
            bin_choice::Known {
                flag: None,
                remembered: &[],
            },
        )
    }

    #[test]
    fn the_decider_picks_the_binary_when_nothing_else_can() {
        let decider = Scripted::answering(Some(1));
        let pick = pick_with(&decider).unwrap().expect("a pick");
        let asked = decider.asked.lock().unwrap();
        assert_eq!(asked.len(), 1, "asked exactly once: {asked:?}");
        let (package, candidates) = &asked[0];
        assert_eq!(package, "rtok");
        assert_eq!(candidates.len(), 2, "{candidates:?}");
        assert_eq!(pick.file, candidates[1]);
        assert_eq!(pick.how, Picked::Asked);
    }

    /// Records every `Warn` event so a test can assert what the user would
    /// have seen on a quiet terminal.
    #[derive(Clone)]
    struct Warns(std::sync::Arc<std::sync::Mutex<Vec<String>>>);

    impl crate::report::Reporter for Warns {
        fn event(&self, event: crate::report::Event) {
            if let crate::report::Event::Warn { detail } = event {
                self.0.lock().unwrap().push(detail);
            }
        }
    }

    /// A source whose `checksums` either fails (the fetch-broke case) or
    /// answers cleanly empty (the publishes-none case); the rest of the
    /// trait is never reached by `verify_checksum`.
    struct StubSource {
        fail: bool,
    }

    impl crate::source::Source for StubSource {
        fn scheme(&self) -> &str {
            "stub"
        }
        fn list_releases(
            &self,
            _id: &str,
            _opts: &crate::source::ListOpts,
        ) -> Result<Vec<Release>> {
            Ok(Vec::new())
        }
        fn checksums(
            &self,
            _id: &str,
            _release: &Release,
            _wanted: &str,
        ) -> Result<BTreeMap<String, String>> {
            if self.fail {
                Err(Error::msg("fetch failed"))
            } else {
                Ok(BTreeMap::new())
            }
        }
        fn download(
            &self,
            _asset: &ReleaseAsset,
            _dest: &Path,
            _progress: &dyn crate::source::ProgressSink,
            _cancel: &crate::cancel::Cancel,
        ) -> Result<String> {
            Err(Error::msg("verify_checksum never downloads"))
        }
    }

    fn verify_with(source: &StubSource) -> (Result<bool>, Vec<String>) {
        let warns = Warns(Default::default());
        let report = Report::new(warns.clone());
        let rel = release(&["ketch-aarch64-apple-darwin.tar.gz"]);
        let asset = rel.assets[0].clone();
        let verified = verify_checksum(
            source,
            "stub/example",
            &rel,
            &asset,
            "deadbeef",
            false,
            &report,
        );
        let warned = warns.0.lock().unwrap().clone();
        (verified, warned)
    }

    /// B66. A checksum sidecar that cannot be fetched (network, rate limit,
    /// a proxy eating the request) is not the same as a release that
    /// publishes no checksum: the install still fails open to first-use,
    /// but with a user-visible warning instead of a debug line nobody sees.
    #[test]
    fn a_checksum_fetch_failure_warns_and_stays_first_use() {
        let (verified, warns) = verify_with(&StubSource { fail: true });
        assert!(!verified.expect("fail-open without --require-checksums"));
        assert_eq!(warns.len(), 1, "exactly one warning: {warns:?}");
        assert!(
            warns[0].contains("could not read published checksums"),
            "{warns:?}"
        );
    }

    /// The clean case stays quiet: `Ok(empty)` is the source saying "there is
    /// nothing published here", which needs no warning.
    #[test]
    fn a_clean_absence_of_checksums_stays_quiet() {
        let (verified, warns) = verify_with(&StubSource { fail: false });
        assert!(!verified.expect("first use either way"));
        assert!(warns.is_empty(), "{warns:?}");
    }

    #[test]
    fn a_declined_choice_is_the_ambiguity_error() {
        let decider = Scripted::answering(None);
        let err = pick_with(&decider).err().expect("an error").to_string();
        assert!(
            err.contains("ships several binaries sharing its name"),
            "{err}"
        );
        assert_eq!(decider.asked.lock().unwrap().len(), 1);
    }

    #[test]
    fn no_decider_declines_like_a_script() {
        let err = pick_with(&crate::decide::NoDecider)
            .err()
            .expect("an error")
            .to_string();
        assert!(
            err.contains("ships several binaries sharing its name"),
            "{err}"
        );
    }

    /// What `prepare` hands `commit` for `rtok` at `version` when its
    /// manifest, from `origin`'s tier, names no binary and the release holds
    /// two that share the name — the case only the prompt can settle (R3).
    /// Built by hand because no offline source can serve a user or registry
    /// manifest to `prepare`; the choice and its write-back live in `commit`.
    fn ambiguous_prepared(origin: crate::model::ManifestOrigin, version: &str) -> Prepared {
        let unpack = tempfile::tempdir().unwrap();
        let payload = unpack.path().to_path_buf();
        ambiguous_payload(&payload);
        let mut release = release(&["rtok.tar.gz"]);
        release.version = Version::parse(version);
        release.tag = format!("v{version}");
        Prepared {
            label: "rtok".into(),
            manifest: crate::model::Manifest::inferred(PackageRef::github("owner/rtok")),
            origin,
            release,
            asset_name: "rtok.tar.gz".into(),
            sha256: "0".repeat(64),
            checksum_verified: false,
            trust: TrustResult::default(),
            provenance: None,
            link: true,
            bin: None,
            locked_bin: None,
            payload,
            unpack,
            started: std::time::Instant::now(),
            local_kind: None,
            local_path: None,
            cancel: Cancel::new(),
            channel: Channel::Stable,
        }
    }

    /// The commands a package links, `.exe` folded away so every OS spells
    /// them alike.
    fn linked_commands(package: &InstalledPackage) -> Vec<String> {
        package
            .links
            .iter()
            .filter(|l| l.role == crate::model::LinkRole::Binary)
            .filter_map(|l| l.link.file_name())
            .map(|f| bin_choice::command_name(&f.to_string_lossy()).to_string())
            .collect()
    }

    #[test]
    fn a_choice_asked_for_a_user_manifest_is_written_into_the_file() {
        let tmp = tempfile::tempdir().unwrap();
        let report = Report::silent();
        let cfg = Config::load(Some(tmp.path().join("root")), &report).unwrap();
        let file = crate::manifest::user_manifest_path(&cfg, "rtok");
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        let original = "# mine\nname = \"rtok\"\nsource = \"github:owner/rtok\"\n";
        std::fs::write(&file, original).unwrap();
        let decider = Scripted::answering(Some(1));
        let cx = Ctx::new(&cfg, &report).with_decider(&decider);
        let mut state = State::default();

        let prepared =
            ambiguous_prepared(crate::model::ManifestOrigin::User(file.clone()), "1.0.0");
        let installed = commit(&cx, &mut state, prepared).unwrap();

        let asked = decider.asked.lock().unwrap();
        assert_eq!(asked.len(), 1, "asked exactly once: {asked:?}");
        let picked = asked[0].1[1].clone();
        let command = bin_choice::command_name(&picked);
        assert_eq!(
            std::fs::read_to_string(&file).unwrap(),
            format!("{original}bin = [{{ name = \"{command}\" }}]\n"),
            "the choice is appended and the rest of the file is left as written"
        );
        assert_eq!(linked_commands(&installed.package), [command]);
        assert_eq!(
            installed.package.bin_choice.as_deref(),
            Some(picked.as_str())
        );
    }

    #[test]
    fn a_choice_asked_for_a_registry_package_is_remembered_in_state_and_reused() {
        let tmp = tempfile::tempdir().unwrap();
        let report = Report::silent();
        let cfg = Config::load(Some(tmp.path().join("root")), &report).unwrap();
        let folder = tmp.path().join("registry").join("rtok");
        std::fs::create_dir_all(&folder).unwrap();
        let original = "name = \"rtok\"\nsource = \"github:owner/rtok\"\n";
        std::fs::write(folder.join("ketch.toml"), original).unwrap();
        let origin = crate::model::ManifestOrigin::Registry(folder.clone());
        let mut state = State::default();

        let first = Scripted::answering(Some(0));
        let cx = Ctx::new(&cfg, &report).with_decider(&first);
        let installed =
            commit(&cx, &mut state, ambiguous_prepared(origin.clone(), "1.0.0")).unwrap();
        let picked = first.asked.lock().unwrap()[0].1[0].clone();
        assert_eq!(
            installed.package.bin_choice.as_deref(),
            Some(picked.as_str())
        );
        assert_eq!(
            std::fs::read_to_string(folder.join("ketch.toml")).unwrap(),
            original,
            "the registry's copy keeps updating, so it is never written to"
        );

        // A decider that declines would fail the upgrade if it were asked.
        let second = Scripted::answering(None);
        let cx = Ctx::new(&cfg, &report).with_decider(&second);
        let upgraded = commit(&cx, &mut state, ambiguous_prepared(origin, "1.1.0")).unwrap();
        assert!(second.asked.lock().unwrap().is_empty(), "not asked again");
        assert_eq!(
            linked_commands(&upgraded.package),
            [bin_choice::command_name(&picked)]
        );
        assert_eq!(
            state.get("rtok").and_then(|p| p.bin_choice.as_deref()),
            Some(picked.as_str())
        );
    }

    #[test]
    fn a_package_on_the_pre_channel_looks_for_prereleases_without_pre() {
        let mut pkg = installed("tool", "1.0.0", PathBuf::from("/store/tool"));
        assert!(!installed_list_opts(&pkg, false).include_prerelease);
        pkg.channel = Channel::Pre;
        assert!(installed_list_opts(&pkg, false).include_prerelease);
    }

    #[test]
    fn an_upgrade_carries_the_channel_forward() {
        let root = tempfile::tempdir().unwrap();
        let cfg = Config::load(
            Some(root.path().join("ketch")),
            &crate::report::Report::silent(),
        )
        .unwrap();
        let mut pkg = installed("tool", "1.0.0", PathBuf::from("/store/tool"));
        let stable = upgrade_request(&cfg, &pkg, "v1.1.0", None, Cancel::new());
        assert!(!stable.prerelease);
        pkg.channel = Channel::Pre;
        let pre = upgrade_request(&cfg, &pkg, "v1.1.0", None, Cancel::new());
        assert!(
            pre.prerelease,
            "a stable tag must not take a pre package off pre"
        );
    }

    #[test]
    fn a_package_on_the_draft_channel_looks_for_drafts_and_prereleases() {
        let mut pkg = installed("tool", "1.0.0", PathBuf::from("/store/tool"));
        assert!(!installed_list_opts(&pkg, false).include_draft);
        pkg.channel = Channel::Draft;
        let opts = installed_list_opts(&pkg, false);
        assert!(opts.include_draft && opts.include_prerelease);
    }

    #[test]
    fn an_upgrade_keeps_a_draft_package_on_drafts() {
        let root = tempfile::tempdir().unwrap();
        let cfg = Config::load(
            Some(root.path().join("ketch")),
            &crate::report::Report::silent(),
        )
        .unwrap();
        let mut pkg = installed("tool", "1.0.0", PathBuf::from("/store/tool"));
        assert!(!upgrade_request(&cfg, &pkg, "v1.1.0", None, Cancel::new()).draft);
        pkg.channel = Channel::Draft;
        assert!(upgrade_request(&cfg, &pkg, "v1.1.0", None, Cancel::new()).draft);
    }

    fn tagged(tag: &str, draft: bool) -> Release {
        Release {
            version: Version::parse(tag),
            tag: tag.into(),
            draft,
            ..release(&[])
        }
    }

    fn upgrade_with(pkg: &InstalledPackage, latest: &Release) -> (bool, Vec<String>) {
        let warns = Warns(Default::default());
        let report = Report::new(warns.clone());
        let upgrade = is_upgrade(
            &report,
            &InstalledBuild::of(pkg),
            latest,
            &upgrade_opts(pkg, false, false),
        );
        let said = warns.0.lock().unwrap().clone();
        (upgrade, said)
    }

    #[test]
    fn only_a_higher_version_under_another_tag_is_an_upgrade() {
        let pkg = installed("tool", "1.0.0", PathBuf::from("/store/tool"));
        assert_eq!(upgrade_with(&pkg, &tagged("v1.1.0", false)), (true, vec![]));
        assert_eq!(
            upgrade_with(&pkg, &tagged("v1.0.0", false)),
            (false, vec![])
        );
        assert_eq!(
            upgrade_with(&pkg, &tagged("v0.9.0", false)),
            (false, vec![])
        );
    }

    #[test]
    fn a_published_draft_is_an_upgrade_under_the_same_tag() {
        let mut pkg = installed("tool", "2.0.0", PathBuf::from("/store/tool"));
        pkg.channel = Channel::Draft;
        pkg.draft = true;
        assert_eq!(upgrade_with(&pkg, &tagged("v2.0.0", true)), (false, vec![]));
        assert_eq!(upgrade_with(&pkg, &tagged("v2.0.0", false)), (true, vec![]));
        assert_eq!(upgrade_with(&pkg, &tagged("v2.1.0", true)), (true, vec![]));
    }

    #[test]
    fn a_deleted_draft_is_a_warning_and_not_an_upgrade() {
        let mut pkg = installed("tool", "2.0.0", PathBuf::from("/store/tool"));
        pkg.channel = Channel::Draft;
        pkg.draft = true;
        let (upgrade, said) = upgrade_with(&pkg, &tagged("v1.0.0", false));
        assert!(!upgrade);
        assert_eq!(said.len(), 1, "{said:?}");
        assert!(said[0].contains("draft release v2.0.0"), "{said:?}");
    }

    /// A `nightly` package as installed: its asset hashed to all zeros,
    /// published at `published`.
    fn nightly(published: Option<&str>) -> InstalledPackage {
        let mut pkg = installed("tool", "1.0.0", PathBuf::from("/store/tool"));
        pkg.version = Version::parse("nightly");
        pkg.tag = "nightly".into();
        pkg.channel = Channel::Dev;
        pkg.published_at = published.map(str::to_string);
        pkg
    }

    /// `nightly` as the source answers now: one asset named `name`, with
    /// `digest` when the source publishes one.
    fn rebuild(name: &str, digest: Option<&str>, published: Option<&str>) -> Release {
        let mut release = tagged("nightly", false);
        let mut file = asset(name);
        file.digest = digest.map(crate::model::Checksum::sha256);
        release.assets = vec![file];
        release.published_at = published.map(str::to_string);
        release
    }

    #[test]
    fn a_tag_that_is_not_a_version_lands_on_dev_whatever_was_opted_into() {
        let release = tagged("nightly", false);
        assert_eq!(Channel::of(&release, false, false), Channel::Dev);
        assert_eq!(Channel::of(&release, true, false), Channel::Dev);
        assert_eq!(Channel::of(&release, false, true), Channel::Draft);
        assert_eq!(
            Channel::of(&tagged("v1.0.0", false), false, false),
            Channel::Stable
        );
    }

    #[test]
    fn the_same_digest_under_the_tag_is_the_installed_build_whatever_its_date() {
        let pkg = nightly(Some("2026-10-01T00:00:00Z"));
        let same = rebuild(
            "a.tar.gz",
            Some(&"0".repeat(64)),
            Some("2026-10-09T00:00:00Z"),
        );
        assert!(!rebuilt(&InstalledBuild::of(&pkg), &same));
        assert_eq!(upgrade_with(&pkg, &same), (false, vec![]));
    }

    #[test]
    fn another_digest_under_the_tag_is_a_new_build_and_an_upgrade() {
        let pkg = nightly(Some("2026-10-01T00:00:00Z"));
        let moved = rebuild(
            "a.tar.gz",
            Some(&"1".repeat(64)),
            Some("2026-10-01T00:00:00Z"),
        );
        assert!(rebuilt(&InstalledBuild::of(&pkg), &moved));
        assert_eq!(upgrade_with(&pkg, &moved), (true, vec![]));
    }

    #[test]
    fn without_a_digest_the_publication_date_decides() {
        let pkg = nightly(Some("2026-10-01T00:00:00Z"));
        assert!(!rebuilt(
            &InstalledBuild::of(&pkg),
            &rebuild("a.tar.gz", None, Some("2026-10-01T00:00:00Z"))
        ));
        assert!(rebuilt(
            &InstalledBuild::of(&pkg),
            &rebuild("a.tar.gz", None, Some("2026-10-09T00:00:00Z"))
        ));
    }

    #[test]
    fn a_missing_date_on_either_side_is_no_evidence_of_a_new_build() {
        let undated = nightly(None);
        assert!(!rebuilt(
            &InstalledBuild::of(&undated),
            &rebuild("a.tar.gz", None, Some("2026-10-09T00:00:00Z"))
        ));
        let dated = nightly(Some("2026-10-01T00:00:00Z"));
        assert!(!rebuilt(
            &InstalledBuild::of(&dated),
            &rebuild("a.tar.gz", None, None)
        ));
    }

    #[test]
    fn an_asset_renamed_under_the_tag_is_a_new_build() {
        let pkg = nightly(Some("2026-10-01T00:00:00Z"));
        assert!(rebuilt(
            &InstalledBuild::of(&pkg),
            &rebuild("tool-20261009.tar.gz", None, Some("2026-10-01T00:00:00Z"))
        ));
    }

    #[test]
    fn a_stable_package_ignores_builds_under_its_own_tag() {
        let pkg = installed("tool", "1.0.0", PathBuf::from("/store/tool"));
        let mut retagged = tagged("v1.0.0", false);
        let mut file = asset("a.tar.gz");
        file.digest = Some(crate::model::Checksum::sha256("1".repeat(64)));
        retagged.assets = vec![file];
        assert_eq!(upgrade_with(&pkg, &retagged), (false, vec![]));
    }

    #[test]
    fn a_tag_the_lockfile_pinned_to_another_build_is_refused() {
        let mut req = InstallRequest::new(PackageSpec::parse("o/r@nightly"));
        let now = rebuild("a.tar.gz", None, Some("2026-10-09T00:00:00Z"));
        assert!(check_published(&req, "tool", &now).is_ok());
        req.expected_published = Some("2026-10-09T00:00:00Z".into());
        assert!(check_published(&req, "tool", &now).is_ok());
        req.expected_published = Some("2026-10-01T00:00:00Z".into());
        let err = check_published(&req, "tool", &now).unwrap_err().to_string();
        assert!(err.contains("ketch lock"), "{err}");
        let undated = rebuild("a.tar.gz", None, None);
        assert!(check_published(&req, "tool", &undated).is_ok());
    }
}
