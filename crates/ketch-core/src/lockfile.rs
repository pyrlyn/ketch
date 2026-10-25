// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `ketch.lock`: one machine's set of tools, pinned to exact releases.
//!
//! Not the lock in `state.rs` — that one is a mutex over the install tree, held
//! for the length of a command. This is a file the user commits next to their
//! dotfiles. `ketch lock` writes it from what is installed, `ketch sync` makes
//! another machine match, and `ketch lock --check` says whether the two have
//! drifted apart.
//!
//! What is reproducible here, and what is not:
//!
//! * The **tag** is. Every machine resolving the same tag gets the same
//!   release, which is the whole point of writing one down.
//! * The **asset and its hash** are reproducible on the target they were
//!   recorded for. The top-level `asset` and `sha256` are the machine that
//!   wrote the lock. `ketch lock --target` adds the same pair for other
//!   platforms under `targets`, and `sync` enforces whichever pair matches
//!   the machine it is running on. A platform with no pair is re-selected
//!   and checked against the source's own checksum.
//!
//! A lockfile is a file somebody else may have written — that is what sharing
//! a dotfiles repository means — so nothing in it is allowed to choose a
//! filesystem path. `sync` asks for a source at a tag and lets the usual
//! manifest resolution decide the install name, the binaries, and where they
//! land. The lock pins *which release*, never *where it goes*.

use crate::config::{sanitize_component, validate_repo};
use crate::error::{Error, Result};
use crate::model::{AskedTarget, InstalledPackage, Os, PackageRef, TargetSpec};
use crate::report::Ctx;
use crate::source::{Source, SourceRegistry};
use crate::state::State;
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

/// Bumped only when the on-disk shape changes incompatibly.
pub const LOCK_VERSION: u32 = 1;

/// The name looked for when `--file` is not given.
pub const LOCK_FILE: &str = "ketch.lock";

/// A whole lockfile.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[cfg_attr(test, derive(schemars::JsonSchema))]
#[serde(deny_unknown_fields)]
pub struct Lockfile {
    pub version: u32,
    /// Sorted by name, so the file is stable and its diffs are readable.
    /// Omitted entirely when empty: `package = []` is not what "nothing is
    /// installed" should look like in a file people read.
    #[serde(default, rename = "package", skip_serializing_if = "Vec::is_empty")]
    pub packages: Vec<LockedPackage>,
}

/// One package, pinned.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(test, derive(schemars::JsonSchema))]
#[serde(deny_unknown_fields)]
pub struct LockedPackage {
    /// The name it was installed under. Used to find the same manifest again,
    /// never to build a path.
    pub name: String,
    /// `scheme:id`, e.g. `github:BurntSushi/ripgrep`.
    #[cfg_attr(test, schemars(with = "String"))]
    pub source: PackageRef,
    /// Human-readable version. `tag` is what actually gets resolved.
    pub version: String,
    pub tag: String,
    /// The target this entry was captured on, as `<os>-<arch>`.
    pub target: String,
    pub asset: String,
    pub sha256: String,
    /// Asset and hash for platforms other than `target`. Empty when the lock
    /// only pinned the machine that wrote it. `ketch lock --target` fills it.
    /// Absent keys stay valid: a lock from before this field existed has none.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub targets: Vec<LockedTarget>,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub pinned: bool,
    /// Which of several binaries sharing the package's name was chosen, as a
    /// file name. Absent when there was nothing to choose; `ketch sync` feeds
    /// it to the choice so a machine without state or a terminal makes the
    /// same one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bin: Option<String>,
    /// When the build was published, for a package on a floating tag
    /// (`nightly`): the tag can be republished, and on another target, where
    /// `sha256` names a file it never downloads, this is what still tells
    /// the locked build from a new one. Absent for every other package.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub published: Option<String>,
}

/// One platform's asset and the hash of that file.
///
/// The same shape as the package's own `asset` and `sha256`, for a machine
/// other than the one that wrote the lock. `target` is `<os>-<arch>`, or
/// `linux-<arch>-musl` when the pin is a musl build.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(test, derive(schemars::JsonSchema))]
#[serde(deny_unknown_fields)]
pub struct LockedTarget {
    pub target: String,
    pub asset: String,
    pub sha256: String,
}

impl LockedPackage {
    fn from_installed(pkg: &InstalledPackage) -> LockedPackage {
        LockedPackage {
            name: pkg.name.clone(),
            source: pkg.source.clone(),
            version: pkg.version.to_string(),
            tag: pkg.tag.clone(),
            target: pkg.target.to_string(),
            asset: pkg.asset_name.clone(),
            sha256: pkg.sha256.clone(),
            targets: Vec::new(),
            pinned: pkg.pinned,
            bin: pkg.bin_choice.clone(),
            published: pkg.published_at.clone(),
        }
    }

    /// True when the recorded asset and hash describe a machine like this one.
    ///
    /// Only then is the hash something to hold a download to: on a different
    /// target the asset is a different file, and a mismatch would be correct
    /// rather than suspicious.
    pub fn matches_target(&self, target: &str) -> bool {
        self.target == target
    }

    /// Asset and hash to hold a download to on `host`, when this lock recorded one.
    ///
    /// The top-level pair applies to `target`. A `targets` entry applies to
    /// the platform it names, including one this machine is not. An explicit
    /// musl or gnu triple outranks a bare `<os>-<arch>` for the same spec, so
    /// a musl pin is not what a glibc machine enforces and the reverse.
    /// `musl_host` is this process's libc, which is the only place that fact
    /// lives: [`TargetSpec`] does not store it.
    pub fn enforced_pin(&self, host: TargetSpec, musl_host: bool) -> Option<(&str, &str)> {
        let mut best: Option<(u8, &str, &str)> = None;
        let top = [(
            self.target.as_str(),
            self.asset.as_str(),
            self.sha256.as_str(),
        )];
        let extras = self.targets.iter().map(|extra| {
            (
                extra.target.as_str(),
                extra.asset.as_str(),
                extra.sha256.as_str(),
            )
        });
        for (target, asset, sha) in top.into_iter().chain(extras) {
            let Some(rank) = pin_rank(target, host, musl_host) else {
                continue;
            };
            if best.as_ref().is_none_or(|(have, _, _)| rank > *have) {
                best = Some((rank, asset, sha));
            }
        }
        best.map(|(_, asset, sha)| (asset, sha))
    }

    /// True when the installed package is the release this entry pinned.
    ///
    /// The tag is always compared. The hash is compared only when a pin
    /// covers this machine — the top-level pair, or a `targets` entry. Another
    /// machine with no pin has a different asset, and a hash mismatch there
    /// would be the expected one rather than drift.
    fn matches_installed(&self, installed: &InstalledPackage) -> bool {
        if installed.tag != self.tag {
            return false;
        }
        let musl_host = installed.target.os == Os::Linux
            && cfg!(target_os = "linux")
            && cfg!(target_env = "musl");
        if let Some((_, sha)) = self.enforced_pin(installed.target, musl_host) {
            return installed.sha256.eq_ignore_ascii_case(sha);
        }
        // A floating tag holds still only as long as its build does.
        match (&self.published, &installed.published_at) {
            (Some(locked), Some(have)) => locked == have,
            _ => true,
        }
    }
}

/// How specifically `target` names `host`. Higher wins. `None` is a different
/// platform, or a libc this host does not have.
fn pin_rank(target: &str, host: TargetSpec, musl_host: bool) -> Option<u8> {
    let asked = AskedTarget::parse(target).ok()?;
    if asked.spec != host {
        return None;
    }
    match asked.musl {
        Some(true) if musl_host && host.os == Os::Linux => Some(2),
        Some(false) if !musl_host => Some(2),
        Some(_) => None,
        None => Some(1),
    }
}

impl Lockfile {
    /// Capture what is installed right now.
    pub fn from_state(state: &State) -> Lockfile {
        let mut packages: Vec<LockedPackage> =
            state.iter().map(LockedPackage::from_installed).collect();
        packages.sort_by(|a, b| a.name.cmp(&b.name));
        Lockfile {
            version: LOCK_VERSION,
            packages,
        }
    }

    /// Read and check a lockfile.
    ///
    /// A missing one is not an empty one: `sync` with nothing to sync from is
    /// a mistake worth naming, not a no-op to report as success.
    pub fn load(path: &Path) -> Result<Lockfile> {
        let text = match std::fs::read_to_string(path) {
            Ok(text) => text,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                return Err(Error::msg(format!(
                    "no lockfile at {}. Run `ketch lock` to write one, or pass \
                     --file to point at another.",
                    path.display()
                )))
            }
            Err(e) => return Err(Error::io(path, e)),
        };
        let lock: Lockfile = crate::toml_file::parse(&text, path.display().to_string())?;
        lock.validate(path)?;
        Ok(lock)
    }

    /// Every check that has to pass before a single entry is acted on.
    ///
    /// One bad entry fails the file rather than being skipped: unlike the
    /// registry, where a partial answer beats none, a lockfile that installed
    /// most of itself would not be a lock at all.
    fn validate(&self, path: &Path) -> Result<()> {
        let where_ = path.display();
        if self.version > LOCK_VERSION {
            return Err(Error::msg(format!(
                "{where_} was written by a newer ketch (lock version {}); \
                 upgrade with `ketch self upgrade`",
                self.version
            )));
        }
        let mut seen = BTreeSet::new();
        for pkg in &self.packages {
            let named = format!("{where_}: package `{}`", pkg.name);
            // A target that is not one ketch recognises silently turns the
            // entry into a cross-target one: the recorded asset and hash stop
            // applying, and a hash that drifted under the tag reads as clean.
            if pkg.target.parse::<crate::model::TargetSpec>().is_err() {
                return Err(Error::msg(format!(
                    "{named} names `{}` as its target, which ketch does not know. \
                     Expected `<os>-<arch>`, e.g. `macos-aarch64`.",
                    pkg.target
                )));
            }
            if pkg.name.trim().is_empty() {
                return Err(Error::msg(format!("{where_}: a package has no name")));
            }
            // The name is matched against installed packages and shown to the
            // user. A name that would have to be rewritten to be usable is a
            // name that does not mean what it says.
            if sanitize_component(&pkg.name) != pkg.name {
                return Err(Error::msg(format!("{named} is not a usable package name")));
            }
            if !seen.insert(pkg.name.to_ascii_lowercase()) {
                return Err(Error::msg(format!("{named} is listed twice")));
            }
            if pkg.tag.trim().is_empty() {
                return Err(Error::msg(format!("{named} has no tag to resolve")));
            }
            if pkg.source.scheme == "github" {
                validate_repo("source", pkg.source.id.clone())?;
            } else if pkg.source.id.trim().is_empty() {
                return Err(Error::msg(format!("{named} has no source id")));
            }
            if !is_sha256(&pkg.sha256) {
                return Err(Error::msg(format!(
                    "{named} has `{}` where a sha256 belongs",
                    pkg.sha256
                )));
            }
            let mut pinned = BTreeSet::new();
            pinned.insert(pkg.target.clone());
            for extra in &pkg.targets {
                let where_target = format!("{named}, target `{}`", extra.target);
                let asked = AskedTarget::parse(&extra.target).map_err(|_| {
                    Error::msg(format!(
                        "{where_target} is not a target ketch knows. Expected `<os>-<arch>` \
                         (`linux-x86_64`) or `linux-<arch>-musl`."
                    ))
                })?;
                let key = asked.lock_key();
                if !pinned.insert(key.clone()) {
                    return Err(Error::msg(format!(
                        "{named} pins `{key}` twice; the top-level asset and sha256 already \
                         cover `target`, and each other platform belongs in `targets` once"
                    )));
                }
                if extra.asset.trim().is_empty() || sanitize_component(&extra.asset) != extra.asset
                {
                    return Err(Error::msg(format!(
                        "{where_target} has `{}` where an asset file name belongs",
                        extra.asset.escape_debug()
                    )));
                }
                if !is_sha256(&extra.sha256) {
                    return Err(Error::msg(format!(
                        "{where_target} has `{}` where a sha256 belongs",
                        extra.sha256
                    )));
                }
            }
            // A binary's file name: one path component, as written. It is only
            // ever matched against files the release ships, but a value that
            // would need rewriting to be a file name cannot name one.
            // Only ever compared, but also printed in the error that names a
            // moved tag, so it cannot carry anything a terminal would act on.
            if let Some(published) = &pkg.published {
                if published.trim().is_empty()
                    || !published.chars().all(|c| c == ' ' || c.is_ascii_graphic())
                {
                    return Err(Error::msg(format!(
                        "{named} has `{}` where a publication time belongs",
                        published.escape_debug()
                    )));
                }
            }
            if let Some(bin) = &pkg.bin {
                if bin.trim().is_empty() || sanitize_component(bin) != *bin {
                    return Err(Error::msg(format!(
                        "{named} has `{bin}` where a binary's file name belongs"
                    )));
                }
            }
        }
        Ok(())
    }

    /// The file as it will be written.
    pub fn to_toml(&self) -> Result<String> {
        let body = crate::toml_file::render(self, LOCK_FILE)?;
        Ok(format!(
            "# {LOCK_FILE} — generated by `ketch lock`. Do not edit it by hand:\n\
             # run `ketch lock` again instead. Commit it.\n\
             #\n\
             # `ketch sync` installs every package below at the tag named here.\n\
             # `asset` and `sha256` apply to `target`. A `[[package.targets]]` entry\n\
             # pins another platform the same way. With no entry for this machine,\n\
             # ketch picks the asset that fits and verifies it against the source.\n\
             {body}"
        ))
    }

    /// Write it out, atomically, so an interrupted write cannot leave a
    /// half-parsed lockfile where a whole one used to be.
    pub fn save(&self, path: &Path) -> Result<()> {
        crate::fsio::write_private(path, self.to_toml()?)
    }

    /// The entry for an installed package, matched on source rather than name.
    ///
    /// The source is the stable identity: a package can be renamed upstream, or
    /// installed under an alias, and still be the same thing to update.
    fn find(&self, pkg: &InstalledPackage) -> Option<&LockedPackage> {
        self.packages.iter().find(|l| l.source == pkg.source)
    }
}

/// What `sync` would do, and what `--check` reports.
#[derive(Debug, Default)]
pub struct Plan {
    /// Not installed at all.
    pub missing: Vec<LockedPackage>,
    /// Installed, at a different tag. Carries the tag actually present.
    pub changed: Vec<(LockedPackage, String)>,
    /// Installed, and the lockfile says nothing about it.
    pub extra: Vec<String>,
    /// Installed at exactly the locked tag.
    pub matched: usize,
}

impl Plan {
    /// True when the tree already is what the lockfile describes. `--prune`
    /// decides whether extras count against that, because a lockfile is a
    /// record of what you want, not necessarily of all you have.
    pub fn is_clean(&self, including_extras: bool) -> bool {
        self.missing.is_empty()
            && self.changed.is_empty()
            && (!including_extras || self.extra.is_empty())
    }
}

/// Compare a lockfile against what is installed.
pub fn plan(lock: &Lockfile, state: &State) -> Plan {
    let mut plan = Plan::default();
    for entry in &lock.packages {
        match state.iter().find(|p| p.source == entry.source) {
            None => plan.missing.push(entry.clone()),
            Some(installed) if entry.matches_installed(installed) => plan.matched += 1,
            Some(installed) => plan.changed.push((entry.clone(), installed.tag.clone())),
        }
    }
    for installed in state.iter() {
        if lock.find(installed).is_none() {
            plan.extra.push(installed.name.clone());
        }
    }
    plan
}

/// Where the lockfile lives: what was asked for, or `ketch.lock` here.
pub fn path(explicit: Option<&Path>) -> PathBuf {
    explicit
        .map(Path::to_path_buf)
        .unwrap_or_else(|| PathBuf::from(LOCK_FILE))
}

fn is_sha256(text: &str) -> bool {
    text.len() == 64 && text.bytes().all(|b| b.is_ascii_hexdigit())
}

/// Record `targets` on every package in `lock`.
///
/// Each string is `<os>-<arch>` or a Rust triple, the same forms as
/// `ketch why --target`. The platform this install already recorded is
/// skipped: its asset and hash are the top-level pair. Every other platform
/// is resolved at the locked tag and downloaded so the hash is of the file,
/// then the bytes are discarded. Nothing is installed.
pub fn add_targets(
    cx: &Ctx<'_>,
    sources: &SourceRegistry,
    state: &State,
    lock: &mut Lockfile,
    targets: &[String],
) -> Result<()> {
    if targets.is_empty() {
        return Ok(());
    }
    // A typo should fail before the first download.
    for text in targets {
        AskedTarget::parse(text)?;
    }
    for pkg in &mut lock.packages {
        let Some(installed) = state.iter().find(|p| p.source == pkg.source) else {
            continue;
        };
        for text in targets {
            cx.cancel.check()?;
            let asked = AskedTarget::parse(text)?;
            if is_host_pin(installed, asked) {
                cx.report.note(&format!(
                    "{}: {text} is the target already recorded for this install",
                    pkg.name
                ));
                continue;
            }
            if installed.source.scheme == "local" {
                return Err(Error::msg(format!(
                    "{}: a local package has one path, not an asset per target",
                    pkg.name
                )));
            }
            let source = sources.for_ref(&installed.source)?;
            let pin = capture_one(cx, source.as_ref(), installed, text)?;
            if pkg.targets.iter().any(|have| have.target == pin.target) {
                return Err(Error::msg(format!(
                    "{}: {text} repeats {}",
                    pkg.name, pin.target
                )));
            }
            cx.report.note(&format!(
                "{}: pinned {} for {}",
                pkg.name, pin.asset, pin.target
            ));
            pkg.targets.push(pin);
        }
        pkg.targets.sort_by(|a, b| a.target.cmp(&b.target));
    }
    Ok(())
}

/// The installed package was already scored for this platform, so another
/// download would only repeat the top-level asset and hash.
fn is_host_pin(installed: &InstalledPackage, asked: AskedTarget) -> bool {
    if asked.spec != installed.target {
        return false;
    }
    let host_musl =
        installed.target.os == Os::Linux && cfg!(target_os = "linux") && cfg!(target_env = "musl");
    match asked.musl {
        Some(true) => host_musl,
        Some(false) => !host_musl,
        None => true,
    }
}

fn capture_one(
    cx: &Ctx<'_>,
    source: &dyn Source,
    installed: &InstalledPackage,
    target: &str,
) -> Result<LockedTarget> {
    if installed.source.scheme == "local" {
        return Err(Error::msg(format!(
            "{}: a local package has one path, not an asset per target",
            installed.name
        )));
    }
    let asked = AskedTarget::parse(target)?;
    let manifest = installed
        .manifest
        .clone()
        .unwrap_or_else(|| crate::model::Manifest::inferred(installed.source.clone()));
    let opts = crate::source::ListOpts {
        include_prerelease: true,
        include_draft: installed.draft,
        ..Default::default()
    };
    let release = cx
        .report
        .activity(&format!("resolving {} for {target}", installed.name))
        .run(|| {
            source.resolve(
                &installed.source.id,
                &crate::model::VersionSpec::Exact(installed.tag.clone()),
                &opts,
            )
        })?;
    let scored = crate::resolve::assets_for(cx.cfg, &release, &manifest.asset, Some(target))?;
    let Some(chosen) = scored.first() else {
        return Err(Error::NoCompatibleAsset {
            id: installed.source.to_string(),
            tag: release.tag.clone(),
            target: asked.lock_key(),
        });
    };
    let asset = release
        .assets
        .iter()
        .find(|asset| asset.name == chosen.asset.name)
        .ok_or_else(|| {
            Error::msg(format!(
                "{}: {} lists no asset named {}",
                installed.name, release.tag, chosen.asset.name
            ))
        })?;
    // Staging only: the hash is what the lock keeps, and the bytes are not
    // installed. A directory under the system temp is removed with this
    // function, including when the download fails part way.
    let staging = tempfile::tempdir().map_err(Error::PlainIo)?;
    let dest = staging.path().join(sanitize_component(&asset.name));
    let progress = cx.report.download(&asset.name);
    let sha256 = source.download(asset, &dest, &progress, &cx.cancel)?;
    if !is_sha256(&sha256) {
        return Err(Error::msg(format!(
            "{}: the download of {} did not produce a sha256",
            installed.name, asset.name
        )));
    }
    Ok(LockedTarget {
        target: asked.lock_key(),
        asset: asset.name.clone(),
        sha256,
    })
}

/// What `Lockfile::load` does after reading the file, for the `lockfile` fuzz
/// target (`src/lib.rs`).
#[cfg(fuzzing)]
pub fn fuzz_parse(text: &str) -> Result<Lockfile> {
    let lock: Lockfile = crate::toml_file::parse(text, "fuzz")?;
    lock.validate(Path::new("ketch.lock"))?;
    Ok(lock)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{Arch, ManifestOrigin, Os, RetainedVersion, TargetSpec, Version};

    #[test]
    fn committed_lock_schema_matches_lockfile() {
        crate::toml_file::assert_schema_current::<Lockfile>("docs/lock.schema.json");
    }

    fn installed(name: &str, repo: &str, tag: &str) -> InstalledPackage {
        InstalledPackage {
            name: name.to_string(),
            version: Version::parse(tag.trim_start_matches('v')),
            source: PackageRef::github(repo),
            tag: tag.to_string(),
            target: TargetSpec::host(),
            asset_name: format!("{name}.tar.gz"),
            sha256: "a".repeat(64),
            checksum_verified: true,
            installed_at: 0,
            prefix: PathBuf::from("/store").join(name),
            links: Vec::new(),
            pinned: false,
            origin: ManifestOrigin::Inferred,
            manifest: None,
            local_kind: None,
            local_path: None,
            trust: Default::default(),
            retained: Vec::new(),
            provenance: None,
            bin_choice: None,
            channel: Default::default(),
            draft: false,
            published_at: None,
        }
    }

    #[test]
    fn previous_retained_is_the_first_kept_version() {
        let mut pkg = installed("rg", "BurntSushi/ripgrep", "v14.0.0");
        assert!(pkg.previous_retained().is_none());
        pkg.retained.push(RetainedVersion::from_installed(&pkg));
        assert_eq!(
            pkg.previous_retained().map(|r| r.tag.as_str()),
            Some("v14.0.0")
        );
    }

    fn state_with(packages: Vec<InstalledPackage>) -> State {
        let mut state = State::default();
        for pkg in packages {
            state.insert(pkg);
        }
        state
    }

    fn lock_of(state: &State) -> Lockfile {
        Lockfile::from_state(state)
    }

    #[test]
    fn a_lockfile_round_trips_through_toml() {
        let state = state_with(vec![
            installed("ripgrep", "BurntSushi/ripgrep", "14.1.1"),
            installed("fd", "sharkdp/fd", "v10.2.0"),
        ]);
        let text = lock_of(&state).to_toml().expect("render");
        let parsed: Lockfile = crate::toml_file::parse(&text, "test").expect("parse");
        assert_eq!(parsed.packages, lock_of(&state).packages);
    }

    #[test]
    fn a_lockfile_is_written_byte_for_byte_as_documented() {
        // Pinned so that moving the render call between modules, or bumping
        // the toml crate, cannot change a file people commit.
        let mut pkg = installed("fd", "sharkdp/fd", "v10.2.0");
        pkg.target = "macos-aarch64".parse().expect("target");
        pkg.bin_choice = Some("fd".to_string());
        let text = lock_of(&state_with(vec![pkg])).to_toml().expect("render");
        assert_eq!(
            text,
            "\
# ketch.lock — generated by `ketch lock`. Do not edit it by hand:
# run `ketch lock` again instead. Commit it.
#
# `ketch sync` installs every package below at the tag named here.
# `asset` and `sha256` apply to `target`. A `[[package.targets]]` entry
# pins another platform the same way. With no entry for this machine,
# ketch picks the asset that fits and verifies it against the source.
version = 1

[[package]]
name = \"fd\"
source = \"github:sharkdp/fd\"
version = \"10.2.0\"
tag = \"v10.2.0\"
target = \"macos-aarch64\"
asset = \"fd.tar.gz\"
sha256 = \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"
bin = \"fd\"
"
        );
    }

    #[test]
    fn a_written_lockfile_says_it_is_generated_on_its_first_line() {
        let text = lock_of(&state_with(vec![])).to_toml().expect("render");
        let first = text.lines().next().unwrap_or_default();
        assert!(first.contains("generated by `ketch lock`"), "{first}");
        assert!(first.contains("Do not edit"), "{first}");
    }

    #[test]
    fn packages_are_written_in_a_stable_order() {
        let state = state_with(vec![
            installed("ripgrep", "BurntSushi/ripgrep", "14.1.1"),
            installed("fd", "sharkdp/fd", "v10.2.0"),
            installed("jq", "jqlang/jq", "jq-1.7"),
        ]);
        let lock = lock_of(&state);
        let names: Vec<&str> = lock.packages.iter().map(|p| p.name.as_str()).collect();
        assert_eq!(names, ["fd", "jq", "ripgrep"]);
    }

    #[test]
    fn a_tree_that_matches_its_lockfile_is_clean() {
        let state = state_with(vec![installed("fd", "sharkdp/fd", "v10.2.0")]);
        let plan = plan(&lock_of(&state), &state);
        assert!(plan.is_clean(true));
        assert_eq!(plan.matched, 1);
    }

    #[test]
    fn a_hash_that_drifted_under_the_same_tag_is_not_a_match() {
        let state = state_with(vec![installed("fd", "sharkdp/fd", "v10.2.0")]);
        let lock = lock_of(&state);
        let mut drifted = installed("fd", "sharkdp/fd", "v10.2.0");
        drifted.sha256 = "b".repeat(64);
        let plan = plan(&lock, &state_with(vec![drifted]));
        assert!(!plan.is_clean(false));
        assert_eq!(plan.matched, 0);
        assert_eq!(plan.changed.len(), 1);
    }

    #[test]
    fn a_hash_on_another_target_is_not_this_machines_pin() {
        let state = state_with(vec![installed("fd", "sharkdp/fd", "v10.2.0")]);
        let lock = lock_of(&state);
        let mut elsewhere = installed("fd", "sharkdp/fd", "v10.2.0");
        elsewhere.sha256 = "b".repeat(64);
        elsewhere.target.arch = match elsewhere.target.arch {
            Arch::Aarch64 => Arch::X86_64,
            Arch::X86_64 | Arch::Universal => Arch::Aarch64,
        };
        let plan = plan(&lock, &state_with(vec![elsewhere]));
        assert!(plan.is_clean(false));
        assert_eq!(plan.matched, 1);
    }

    #[test]
    fn hash_comparison_is_case_insensitive() {
        let mut pkg = installed("fd", "sharkdp/fd", "v10.2.0");
        pkg.sha256 = "ab".repeat(32);
        let lock = lock_of(&state_with(vec![pkg.clone()]));
        pkg.sha256 = "AB".repeat(32);
        let plan = plan(&lock, &state_with(vec![pkg]));
        assert!(plan.is_clean(false));
        assert_eq!(plan.matched, 1);
    }

    #[test]
    fn a_different_tag_is_drift_not_a_missing_package() {
        let state = state_with(vec![installed("fd", "sharkdp/fd", "v10.2.0")]);
        let lock = lock_of(&state);
        let moved = state_with(vec![installed("fd", "sharkdp/fd", "v10.3.0")]);
        let plan = plan(&lock, &moved);
        assert!(plan.missing.is_empty());
        assert_eq!(plan.changed.len(), 1);
        assert_eq!(plan.changed[0].1, "v10.3.0");
    }

    #[test]
    fn a_package_renamed_upstream_is_still_the_same_package() {
        let state = state_with(vec![installed("fd", "sharkdp/fd", "v10.2.0")]);
        let lock = lock_of(&state);
        // Same source, installed under a different name.
        let renamed = state_with(vec![installed("fd-find", "sharkdp/fd", "v10.2.0")]);
        let plan = plan(&lock, &renamed);
        assert_eq!(plan.matched, 1);
        assert!(plan.extra.is_empty(), "{:?}", plan.extra);
    }

    #[test]
    fn something_installed_that_the_lockfile_omits_is_extra() {
        let lock = lock_of(&state_with(vec![installed("fd", "sharkdp/fd", "v10.2.0")]));
        let more = state_with(vec![
            installed("fd", "sharkdp/fd", "v10.2.0"),
            installed("jq", "jqlang/jq", "jq-1.7"),
        ]);
        let plan = plan(&lock, &more);
        assert_eq!(plan.extra, ["jq"]);
        assert!(plan.is_clean(false), "extras alone are not drift");
        assert!(!plan.is_clean(true), "with --prune they are");
    }

    #[test]
    fn the_pinned_flag_survives_a_round_trip() {
        let mut pkg = installed("fd", "sharkdp/fd", "v10.2.0");
        pkg.pinned = true;
        let text = lock_of(&state_with(vec![pkg])).to_toml().expect("render");
        let parsed: Lockfile = crate::toml_file::parse(&text, "test").expect("parse");
        assert!(parsed.packages[0].pinned);
    }

    fn parse_checked(text: &str) -> Result<Lockfile> {
        let lock: Lockfile = crate::toml_file::parse(text, "test")?;
        lock.validate(Path::new("ketch.lock"))?;
        Ok(lock)
    }

    fn entry(overrides: &str) -> String {
        format!(
            "version = 1\n\n[[package]]\nname = \"fd\"\nsource = \"github:sharkdp/fd\"\n\
             version = \"10.2.0\"\ntag = \"v10.2.0\"\ntarget = \"macos-aarch64\"\n\
             asset = \"fd.tar.gz\"\nsha256 = \"{}\"\n{overrides}",
            "a".repeat(64)
        )
    }

    #[test]
    fn a_well_formed_lockfile_passes() {
        assert!(parse_checked(&entry("")).is_ok());
    }

    #[test]
    fn a_name_that_would_escape_the_store_is_refused() {
        let text = entry("").replace("name = \"fd\"", "name = \"../../.zshrc\"");
        assert!(parse_checked(&text).is_err());
    }

    #[test]
    fn a_source_that_is_not_a_repo_is_refused() {
        let text = entry("").replace("github:sharkdp/fd", "github:../../etc");
        assert!(parse_checked(&text).is_err());
    }

    #[test]
    fn a_hash_that_is_not_a_hash_is_refused() {
        let text = entry("").replace(&"a".repeat(64), "not-a-hash");
        assert!(parse_checked(&text).is_err());
    }

    #[test]
    fn a_package_listed_twice_is_refused() {
        let mut text = entry("");
        let second = text.clone();
        text.push_str(
            second
                .split_once("\n\n")
                .map(|(_, rest)| rest)
                .unwrap_or(""),
        );
        assert!(parse_checked(&text).is_err());
    }

    #[test]
    fn a_chosen_binary_round_trips_and_stays_out_of_entries_without_one() {
        let mut chosen = installed("rtok", "me/rtok", "v1.0.0");
        chosen.bin_choice = Some("rtok-cli".to_string());
        let state = state_with(vec![chosen, installed("fd", "sharkdp/fd", "v10.2.0")]);
        let text = lock_of(&state).to_toml().expect("render");
        assert_eq!(text.matches("bin = ").count(), 1, "{text}");
        let parsed = parse_checked(&text).expect("parse");
        assert_eq!(parsed.packages, lock_of(&state).packages);
        let rtok = parsed.packages.iter().find(|p| p.name == "rtok");
        assert_eq!(rtok.and_then(|p| p.bin.as_deref()), Some("rtok-cli"));
    }

    #[test]
    fn a_lockfile_written_before_bin_existed_still_loads() {
        let lock = parse_checked(&entry("")).expect("parse");
        assert!(lock.packages[0].bin.is_none());
    }

    #[test]
    fn a_bin_that_is_not_one_file_name_is_refused() {
        for bad in ["../../bin/sh", "sub/rtok", "..", "", "rtok\u{202e}"] {
            let text = entry(&format!(
                "bin = {}\n",
                crate::toml_file::string_literal(bad)
            ));
            assert!(parse_checked(&text).is_err(), "{bad:?} was accepted");
        }
        assert!(parse_checked(&entry("bin = \"rtok-cli.exe\"\n")).is_ok());
    }

    fn nightly(published: &str) -> InstalledPackage {
        let mut pkg = installed("tool", "acme/tool", "nightly");
        pkg.published_at = Some(published.to_string());
        pkg
    }

    #[test]
    fn a_floating_tag_records_its_build_and_nothing_else_does() {
        let state = state_with(vec![
            nightly("2026-10-01T00:00:00Z"),
            installed("fd", "sharkdp/fd", "v10.2.0"),
        ]);
        let text = lock_of(&state).to_toml().expect("render");
        assert_eq!(text.matches("published = ").count(), 1, "{text}");
        let parsed = parse_checked(&text).expect("parse");
        assert_eq!(parsed.packages, lock_of(&state).packages);
    }

    #[test]
    fn a_floating_tag_republished_on_another_target_is_drift() {
        let lock = lock_of(&state_with(vec![nightly("2026-10-01T00:00:00Z")]));
        let elsewhere = |published: &str| {
            let mut pkg = nightly(published);
            pkg.sha256 = "b".repeat(64);
            pkg.target.arch = match pkg.target.arch {
                Arch::Aarch64 => Arch::X86_64,
                Arch::X86_64 | Arch::Universal => Arch::Aarch64,
            };
            pkg
        };
        let same = plan(&lock, &state_with(vec![elsewhere("2026-10-01T00:00:00Z")]));
        assert!(same.is_clean(false));
        let moved = plan(&lock, &state_with(vec![elsewhere("2026-10-09T00:00:00Z")]));
        assert_eq!(moved.changed.len(), 1);
    }

    #[test]
    fn a_publication_time_a_terminal_could_act_on_is_refused() {
        for bad in ["", " ", "\u{1b}[2J", "2026\u{202e}", "2026\n"] {
            let text = entry(&format!(
                "published = {}\n",
                crate::toml_file::string_literal(bad)
            ));
            assert!(parse_checked(&text).is_err(), "{bad:?} was accepted");
        }
        assert!(parse_checked(&entry("published = \"2026-10-01T00:00:00Z\"\n")).is_ok());
    }

    #[test]
    fn an_unknown_key_is_refused_rather_than_ignored() {
        assert!(parse_checked(&entry("surprise = true\n")).is_err());
    }

    #[test]
    fn a_lockfile_from_a_newer_ketch_is_refused() {
        let text = entry("").replace("version = 1", "version = 99");
        assert!(parse_checked(&text).is_err());
    }

    #[test]
    fn an_empty_lockfile_is_valid_and_means_nothing_installed() {
        let lock = parse_checked("version = 1\n").expect("parse");
        assert!(lock.packages.is_empty());
    }

    #[test]
    fn the_recorded_hash_only_applies_to_the_machine_that_wrote_it() {
        let lock = parse_checked(&entry("")).expect("parse");
        assert!(lock.packages[0].matches_target("macos-aarch64"));
        assert!(!lock.packages[0].matches_target("macos-x86_64"));
    }

    #[test]
    fn another_platforms_asset_and_hash_are_enforced_on_that_platform() {
        let sha_host = "a".repeat(64);
        let sha_linux = "b".repeat(64);
        let text = entry(&format!(
            "\n[[package.targets]]\ntarget = \"linux-x86_64\"\nasset = \"fd-linux.tar.gz\"\n\
             sha256 = \"{sha_linux}\"\n"
        ));
        let lock = parse_checked(&text).expect("parse");
        let linux = TargetSpec {
            os: Os::Linux,
            arch: Arch::X86_64,
        };
        let mac = TargetSpec {
            os: Os::MacOs,
            arch: Arch::Aarch64,
        };
        assert_eq!(
            lock.packages[0].enforced_pin(linux, false),
            Some(("fd-linux.tar.gz", sha_linux.as_str()))
        );
        assert_eq!(
            lock.packages[0].enforced_pin(mac, false),
            Some(("fd.tar.gz", sha_host.as_str()))
        );

        // The host pair is glibc `linux-x86_64`. A musl entry outranks it only
        // on a musl host; a glibc host keeps the top-level pair.
        let both = format!(
            "version = 1\n\n[[package]]\nname = \"fd\"\nsource = \"github:sharkdp/fd\"\n\
             version = \"10.2.0\"\ntag = \"v10.2.0\"\ntarget = \"linux-x86_64\"\n\
             asset = \"fd-gnu.tar.gz\"\nsha256 = \"{sha_host}\"\n\
             \n[[package.targets]]\ntarget = \"linux-x86_64-musl\"\nasset = \"fd-musl.tar.gz\"\n\
             sha256 = \"{sha_linux}\"\n"
        );
        let both = parse_checked(&both).expect("musl pin");
        assert_eq!(
            both.packages[0].enforced_pin(linux, false),
            Some(("fd-gnu.tar.gz", sha_host.as_str()))
        );
        assert_eq!(
            both.packages[0].enforced_pin(linux, true),
            Some(("fd-musl.tar.gz", sha_linux.as_str()))
        );
    }

    #[test]
    fn a_target_pin_with_a_bad_hash_a_path_or_a_repeated_platform_is_refused() {
        let sha = "b".repeat(64);
        assert!(parse_checked(&entry(
            "\n[[package.targets]]\ntarget = \"linux-x86_64\"\nasset = \"fd-linux.tar.gz\"\n\
             sha256 = \"nope\"\n"
        ))
        .is_err());
        assert!(parse_checked(&entry(&format!(
            "\n[[package.targets]]\ntarget = \"macos-aarch64\"\nasset = \"other.tar.gz\"\n\
             sha256 = \"{sha}\"\n"
        )))
        .is_err());
        assert!(parse_checked(&entry(&format!(
            "\n[[package.targets]]\ntarget = \"beos-m68k\"\nasset = \"fd.tar.gz\"\n\
             sha256 = \"{sha}\"\n"
        )))
        .is_err());
        assert!(parse_checked(&entry(&format!(
            "\n[[package.targets]]\ntarget = \"linux-x86_64\"\nasset = \"../fd\"\n\
             sha256 = \"{sha}\"\n"
        )))
        .is_err());
        // A gnu triple is the same platform as `linux-x86_64`, which the
        // top-level pair already pins.
        let same_platform = format!(
            "version = 1\n\n[[package]]\nname = \"fd\"\nsource = \"github:sharkdp/fd\"\n\
             version = \"10.2.0\"\ntag = \"v10.2.0\"\ntarget = \"linux-x86_64\"\n\
             asset = \"fd-gnu.tar.gz\"\nsha256 = \"{sha}\"\n\
             \n[[package.targets]]\ntarget = \"x86_64-unknown-linux-gnu\"\n\
             asset = \"fd-linux.tar.gz\"\nsha256 = \"{sha}\"\n"
        );
        assert!(parse_checked(&same_platform).is_err());
    }

    #[test]
    fn capturing_a_target_records_the_asset_that_platform_would_get() {
        use crate::model::{Release, ReleaseAsset};
        use std::collections::BTreeMap;

        struct Fixed;
        impl crate::source::Source for Fixed {
            fn scheme(&self) -> &str {
                "github"
            }
            fn list_releases(
                &self,
                _id: &str,
                _opts: &crate::source::ListOpts,
            ) -> Result<Vec<Release>> {
                let asset = |name: &str| ReleaseAsset {
                    name: name.to_string(),
                    url: format!("https://example.invalid/{name}"),
                    size: 1,
                    content_type: None,
                    digest: None,
                    headers: BTreeMap::new(),
                };
                Ok(vec![Release {
                    version: Version::parse("1.0.0"),
                    tag: "v1.0.0".into(),
                    prerelease: false,
                    draft: false,
                    published_at: None,
                    notes: None,
                    assets: vec![
                        asset("tool-x86_64-unknown-linux-gnu.tar.gz"),
                        asset("tool-aarch64-apple-darwin.tar.gz"),
                        asset("tool-x86_64-pc-windows-msvc.zip"),
                    ],
                }])
            }
            fn download(
                &self,
                _asset: &ReleaseAsset,
                _dest: &std::path::Path,
                _progress: &dyn crate::report::ProgressSink,
                _cancel: &crate::cancel::Cancel,
            ) -> Result<String> {
                Ok("b".repeat(64))
            }
        }

        let root = tempfile::tempdir().expect("temp");
        let report = crate::report::Report::silent();
        let cfg =
            crate::config::Config::load(Some(root.path().to_path_buf()), &report).expect("config");
        let cx = crate::report::Ctx::new(&cfg, &report);
        let mut pkg = installed("tool", "acme/tool", "v1.0.0");
        pkg.tag = "v1.0.0".to_string();
        // An explicit gnu triple, so a musl host does not inherit its libc and
        // refuse the gnu asset. The musl asset is absent: that request fails
        // instead of recording the gnu file under a musl key.
        let cases = [
            (
                "x86_64-unknown-linux-gnu",
                "tool-x86_64-unknown-linux-gnu.tar.gz",
                "linux-x86_64",
            ),
            (
                "aarch64-apple-darwin",
                "tool-aarch64-apple-darwin.tar.gz",
                "macos-aarch64",
            ),
            (
                "x86_64-pc-windows-msvc",
                "tool-x86_64-pc-windows-msvc.zip",
                "windows-x86_64",
            ),
        ];
        for (target, asset, key) in cases {
            let pin = super::capture_one(&cx, &Fixed, &pkg, target).expect(target);
            assert_eq!(pin.target, key, "{target}");
            assert_eq!(pin.asset, asset, "{target}");
            assert_eq!(pin.sha256.len(), 64, "{target}");
        }
        assert!(super::capture_one(&cx, &Fixed, &pkg, "x86_64-unknown-linux-musl").is_err());
    }

    /// Fixed inputs, so the rendering is identical on every host: a reviewed
    /// snapshot of the file `ketch lock` writes.
    #[test]
    fn a_two_package_lockfile_renders_a_stable_file() {
        let lock = Lockfile {
            version: LOCK_VERSION,
            packages: vec![
                LockedPackage {
                    name: "fd".to_string(),
                    source: PackageRef::github("sharkdp/fd"),
                    version: "10.2.0".to_string(),
                    tag: "v10.2.0".to_string(),
                    target: "macos-aarch64".to_string(),
                    asset: "fd.tar.gz".to_string(),
                    sha256: "a".repeat(64),
                    targets: Vec::new(),
                    pinned: false,
                    bin: None,
                    published: None,
                },
                LockedPackage {
                    name: "ripgrep".to_string(),
                    source: PackageRef::github("BurntSushi/ripgrep"),
                    version: "14.1.1".to_string(),
                    tag: "14.1.1".to_string(),
                    target: "macos-aarch64".to_string(),
                    asset: "ripgrep.tar.gz".to_string(),
                    sha256: "b".repeat(64),
                    targets: Vec::new(),
                    pinned: true,
                    bin: None,
                    published: None,
                },
            ],
        };
        let text = lock.to_toml().expect("render");
        insta::assert_snapshot!(text);
    }

    // Whatever the entries are, rendering and re-reading must agree.
    proptest::proptest! {
        #[test]
        fn a_rendered_lockfile_reads_back_what_was_written(
            entries in proptest::collection::vec(
                (
                    "[a-z]{1,8}",
                    proptest::sample::select(vec![
                        "BurntSushi/ripgrep",
                        "sharkdp/fd",
                        "jqlang/jq",
                    ]),
                    (0u32..50, 0u32..50, 0u32..50),
                    proptest::sample::select(vec![
                        "macos-aarch64",
                        "linux-x86_64",
                        "windows-x86_64",
                    ]),
                    "[0-9a-f]{64}",
                    proptest::bool::ANY,
                ),
                0..4,
            )
        ) {
            // Names compare case-insensitively at validation, so the first
            // spelling wins and the file holds each package once.
            let mut by_name = std::collections::BTreeMap::new();
            for (name, repo, (major, minor, patch), target, sha256, pinned) in entries {
                by_name.entry(name.clone()).or_insert_with(|| LockedPackage {
                    name,
                    source: PackageRef::github(repo),
                    version: format!("{major}.{minor}.{patch}"),
                    tag: format!("v{major}.{minor}.{patch}"),
                    target: target.to_string(),
                    asset: "tool.tar.gz".to_string(),
                    sha256,
                    targets: Vec::new(),
                    pinned,
                    bin: None,
                    published: None,
                });
            }
            let mut packages: Vec<LockedPackage> = by_name.into_values().collect();
            packages.sort_by(|a, b| a.name.cmp(&b.name));
            let lock = Lockfile {
                version: LOCK_VERSION,
                packages,
            };
            let text = lock.to_toml().expect("render");
            let parsed: Lockfile = crate::toml_file::parse(&text, "test").expect("parse");
            proptest::prop_assert!(parsed.validate(Path::new("proptest")).is_ok());
            proptest::prop_assert_eq!(parsed.packages, lock.packages);
        }
    }
}
