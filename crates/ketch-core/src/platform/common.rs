// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Placement steps that do not depend on the operating system.
//!
//! Bin resolution and executable discovery are the same walk on every host.
//! What differs is a naming rule — exact file names on Unix, case-insensitive
//! launcher suffixes on Windows — and whether `.app` and `.framework`
//! directories are bundles to skip. Placing, removing and linking extras share
//! the same shape too: the payload moves into the store, a failure puts it
//! back, and only the write itself is a symlink or a copy. Those differences
//! are arguments, so both rules compile and run on every host instead of only
//! on the OS that used to own the copy.

#[cfg(any(unix, windows))]
use super::claim::Claims;
use super::scoring::looks_like_build_artifact;
use super::{Placement, Platform};
use crate::error::{Error, Result};
use crate::extra::ExtraPlacement;
use crate::model::{glob_match_path, glob_preferred, BinSpec, LinkKind, LinkRecord, PackageKind};
use fs_kit::tree::remove_any;
use std::io::Read;
use std::path::{Path, PathBuf};

/// Directories inside a payload that never hold the program itself.
pub(crate) const NOISE_DIRS: &[&str] = &[
    "share",
    "doc",
    "docs",
    "man",
    "completions",
    "complete",
    "etc",
    "lib",
    "include",
    "licenses",
    "_internal",
    "resources",
    "plugins",
];

/// How a manifest binary name is matched to a payload file, and how the link
/// on `PATH` is spelled.
///
/// The two rules used to be copies, each compiled only into its own backend,
/// so a fix to one never reached the other.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BinNames {
    /// The file name equals the spec, and the link keeps that spelling.
    ///
    /// Only the Unix backends construct this. It stays compiled everywhere so
    /// the rule is tested on Windows too.
    #[cfg_attr(windows, allow(dead_code))]
    Exact,
    /// Case-insensitive, and a bare name also matches `.exe`, `.cmd` and
    /// `.bat`. The link gains `.exe` unless it already has a launcher suffix.
    ///
    /// Only the Windows backend constructs this. It stays compiled everywhere
    /// so the rule is tested on Unix too.
    #[cfg_attr(not(windows), allow(dead_code))]
    Windows,
}

/// Whether discovery treats Apple bundle directories as noise.
///
/// macOS and Linux skip them: a `.app` carries its own executable and must
/// not also be linked onto `PATH`. Windows payloads have no such directories.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BundleDirs {
    /// Skip `.app` and `.framework` as well as [`NOISE_DIRS`].
    ///
    /// macOS and Linux construct this. Windows does not, but the variant stays
    /// so discovery is tested on every host.
    #[cfg_attr(windows, allow(dead_code))]
    Skip,
    /// Skip only [`NOISE_DIRS`].
    ///
    /// Windows constructs this. Unix backends do not.
    #[cfg_attr(not(windows), allow(dead_code))]
    Keep,
}

/// Keep a Windows executable suffix, or add `.exe` so PATH lookup finds it.
pub(crate) fn windows_bin_name(name: &str) -> String {
    let lower = name.to_ascii_lowercase();
    if [".exe", ".cmd", ".bat", ".com", ".ps1"]
        .iter()
        .any(|s| lower.ends_with(s))
    {
        name.to_string()
    } else {
        format!("{name}.exe")
    }
}

/// True when the immediate parent directory is named `bin` (any ASCII case).
///
/// Windows archives and some toolchain layouts ship `Bin/`; treating only the
/// lowercase spelling as special left those packages falling back to noisy
/// top-level discovery.
pub(crate) fn parent_dir_is_bin(path: &Path) -> bool {
    path.parent()
        .and_then(|d| d.file_name())
        .is_some_and(|n| n.eq_ignore_ascii_case("bin"))
}

/// Keep a payload symlink if its target is a regular file inside the tree.
fn payload_executable_entry(entry: &walkdir::DirEntry, root: &Path) -> Option<PathBuf> {
    let path = entry.path();
    if entry.file_type().is_file() {
        return Some(path.to_path_buf());
    }
    if !entry.file_type().is_symlink() {
        return None;
    }
    // One hop is not enough: `tool -> abs` reads as inside while `abs`, or
    // any link further along, leads out of the payload.
    if !crate::extract::resolves_inside(path, root) {
        return None;
    }
    std::fs::metadata(path)
        .ok()
        .filter(|meta| meta.is_file())
        .map(|_| path.to_path_buf())
}

/// `name` is the directory's file name already folded to ASCII lowercase.
///
/// [`NOISE_DIRS`] is stored that way, and a bundle named `Foo.APP` is still a
/// bundle. Folding once, before either check, is what both copies did.
fn is_skipped_dir(name: &str, bundles: BundleDirs) -> bool {
    let bundle = matches!(bundles, BundleDirs::Skip)
        && (name.ends_with(".app") || name.ends_with(".framework"));
    bundle || NOISE_DIRS.contains(&name)
}

/// Every executable file in the payload that is a plausible entry point.
///
/// The package-named binary is first. A plain sort is not that order once
/// the names carry `.exe`. `bundles` is the only platform difference: Apple
/// backends skip bundle directories, Windows does not.
pub(crate) fn discover_executables(
    platform: &dyn Platform,
    root: &Path,
    package: &str,
    bundles: BundleDirs,
) -> Vec<PathBuf> {
    let mut found: Vec<PathBuf> = walkdir::WalkDir::new(root)
        .max_depth(4)
        .follow_links(false)
        .into_iter()
        .filter_entry(|e| {
            let name = e.file_name().to_string_lossy().to_ascii_lowercase();
            e.path() == root || !is_skipped_dir(&name, bundles)
        })
        .filter_map(|e| e.ok())
        .filter_map(|e| payload_executable_entry(&e, root))
        .filter(|p| platform.is_executable(p))
        .collect();

    let in_bin: Vec<PathBuf> = found
        .iter()
        .filter(|p| parent_dir_is_bin(p))
        .cloned()
        .collect();
    if !in_bin.is_empty() {
        found = in_bin;
    }
    super::order_discovered_executables(&mut found, package);
    found
}

fn file_name_matches(name: &std::ffi::OsStr, want: &str, names: BinNames) -> bool {
    match names {
        BinNames::Exact => name == want,
        BinNames::Windows => {
            let shown = name.to_string_lossy();
            name == want
                || shown.eq_ignore_ascii_case(want)
                // `bin = [{ name = "rtok" }]` must find `rtok.exe`.
                || shown.eq_ignore_ascii_case(&format!("{want}.exe"))
                || shown.eq_ignore_ascii_case(&format!("{want}.cmd"))
                || shown.eq_ignore_ascii_case(&format!("{want}.bat"))
        }
    }
}

fn spell_link_name(name: &str, names: BinNames) -> String {
    match names {
        BinNames::Exact => name.to_string(),
        BinNames::Windows => windows_bin_name(name),
    }
}

/// Resolve the manifest's explicit binary list against the extracted payload.
///
/// `names` is the match and the link spelling. The glob walk itself is the
/// same on every host.
pub(crate) fn resolve_bin_specs(
    root: &Path,
    specs: &[BinSpec],
    names: BinNames,
) -> Result<Vec<(PathBuf, String)>> {
    let candidates: Vec<PathBuf> = walkdir::WalkDir::new(root)
        .max_depth(6)
        .follow_links(false)
        .into_iter()
        .filter_map(|e| e.ok())
        .filter(|e| e.file_type().is_file())
        .map(|e| e.into_path())
        .collect();

    let mut out = Vec::new();
    for spec in specs {
        let matched = match &spec.path {
            Some(pattern) => {
                let matched: Vec<&Path> = candidates
                    .iter()
                    .filter(|p| {
                        p.strip_prefix(root)
                            .ok()
                            .is_some_and(|rel| glob_match_path(pattern, &rel.to_string_lossy()))
                    })
                    .map(|p| p.as_path())
                    .collect();
                glob_preferred(root, pattern, &matched, spec.name.as_deref())?
            }
            None => {
                let want = spec.name.as_deref().unwrap_or_default();
                candidates
                    .iter()
                    .find(|p| {
                        p.file_name()
                            .is_some_and(|n| file_name_matches(n, want, names))
                    })
                    .map(|p| p.as_path())
            }
        };
        let path = matched.ok_or_else(|| {
            Error::msg(format!(
                "manifest expects `{}` but the release payload does not contain it",
                spec.path
                    .clone()
                    .or_else(|| spec.name.clone())
                    .unwrap_or_else(|| "<unnamed>".into())
            ))
        })?;
        let name = spec.name.clone().unwrap_or_else(|| {
            path.file_name()
                .unwrap_or_default()
                .to_string_lossy()
                .into_owned()
        });
        out.push((path.to_path_buf(), spell_link_name(&name, names)));
    }
    Ok(out)
}

/// The payload files a CLI package links into the bin dir, and the name each gets.
///
/// An app bundle carries its own executables, so [`PackageKind::App`] links
/// none. An empty manifest list is discovery; anything else is the manifest.
pub(crate) fn cli_targets(
    platform: &dyn Platform,
    root: &Path,
    plan: &Placement<'_>,
    names: BinNames,
    bundles: BundleDirs,
) -> Result<Vec<(PathBuf, String)>> {
    if plan.kind == PackageKind::App {
        return Ok(Vec::new());
    }
    if plan.bin_specs.is_empty() {
        let found = discover_executables(platform, root, plan.name, bundles);
        let sole = found.len() == 1;
        Ok(found
            .into_iter()
            .map(|path| {
                let file_name = path
                    .file_name()
                    .unwrap_or_default()
                    .to_string_lossy()
                    .into_owned();
                let stem = if sole && looks_like_build_artifact(&file_name) {
                    plan.name.to_string()
                } else {
                    file_name
                };
                (path, spell_link_name(&stem, names))
            })
            .collect())
    } else {
        resolve_bin_specs(root, plan.bin_specs, names)
    }
}

/// How a copied file proves it is still the one ketch placed.
///
/// A symlink names its target. A copy does not, so removal has to decide from
/// the file itself. Unix identifies a `CopiedFile` by a file still sitting at
/// the path. Windows copies every binary, and a user who replaced the bytes
/// keeps that file.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum CopiedFileProof {
    /// A file at the recorded path is enough.
    #[cfg_attr(windows, allow(dead_code))]
    IsFile,
    /// The bytes must still match the store copy.
    #[cfg_attr(not(windows), allow(dead_code))]
    SameBytes,
}

fn same_contents(a: &Path, b: &Path) -> bool {
    let Ok(mut fa) = std::fs::File::open(a) else {
        return false;
    };
    let Ok(mut fb) = std::fs::File::open(b) else {
        return false;
    };
    let mut ba = [0u8; 8192];
    let mut bb = [0u8; 8192];
    loop {
        match (fa.read(&mut ba), fb.read(&mut bb)) {
            (Ok(0), Ok(0)) => return true,
            (Ok(na), Ok(nb)) if na == nb && ba[..na] == bb[..nb] => {}
            _ => return false,
        }
    }
}

/// Whether what is at a recorded path is still what ketch put there.
///
/// `proof` is the only platform difference, and only for [`LinkKind::CopiedFile`].
/// A copied `.app` is a directory whose store tree still exists: a matching
/// record must never authorize deleting a file the user put in its place.
pub(crate) fn still_placed(record: &LinkRecord, proof: CopiedFileProof) -> bool {
    match record.kind {
        LinkKind::Symlink | LinkKind::LinkedApp => {
            std::fs::read_link(&record.link).is_ok_and(|target| target == record.target)
        }
        LinkKind::CopiedFile => {
            let is_file = std::fs::symlink_metadata(&record.link).is_ok_and(|meta| meta.is_file());
            is_file
                && match proof {
                    CopiedFileProof::IsFile => true,
                    CopiedFileProof::SameBytes => same_contents(&record.link, &record.target),
                }
        }
        LinkKind::CopiedApp => {
            std::fs::symlink_metadata(&record.link).is_ok_and(|meta| meta.is_dir())
                && record.target.exists()
        }
    }
}

/// Whether `dir` can take a new file. A missing directory is its own answer:
/// install creates it, and the caller reports that separately from a permission
/// error.
pub(crate) fn writable(dir: &Path) -> std::result::Result<(), String> {
    if !dir.exists() {
        return Err("does not exist".to_string());
    }
    tempfile::Builder::new()
        .prefix(".ketch-probe")
        .tempfile_in(dir)
        .map(|_| ())
        .map_err(|e| e.to_string())
}

/// Undo placement of `links`. Must tolerate links that are already gone.
///
/// `still` is the platform's proof that the path is still ketch's. A path that
/// fails it is left alone: the user, or another package, has taken the name.
pub(crate) fn unplace(
    links: &[LinkRecord],
    report: &crate::report::Report,
    still: impl Fn(&LinkRecord) -> bool,
) -> Result<()> {
    for record in links {
        if std::fs::symlink_metadata(&record.link).is_err() {
            continue;
        }
        if !still(record) {
            report.debug(&format!(
                "leaving {}: it is no longer what ketch placed there",
                record.link.display()
            ));
            continue;
        }
        remove_any(&record.link).map_err(|e| Error::fs_kit(&record.link, e))?;
    }
    Ok(())
}

/// Create each extra's parent directory and hand the payload file to `place`.
///
/// `place` symlinks on Unix and copies on Windows. The directory and the
/// record list are the same either way.
pub(crate) fn link_planned_extras(
    extras: &[ExtraPlacement],
    root: &Path,
    mut place: impl FnMut(&ExtraPlacement, &Path) -> Result<LinkRecord>,
) -> Result<Vec<LinkRecord>> {
    let mut links = Vec::new();
    for extra in extras {
        let target = crate::extra::resolve_under(root, &extra.rel_path)?;
        if let Some(parent) = extra.dest.parent() {
            std::fs::create_dir_all(parent).map_err(|e| Error::io(parent, e))?;
        }
        links.push(place(extra, &target)?);
    }
    Ok(links)
}

/// Move the payload into the store.
///
/// `None` means the caller asked not to link. A store that was swapped aside
/// is already dropped, because nothing was placed that a failure would roll
/// back.
pub(crate) fn swap_payload(
    plan: &Placement<'_>,
    move_payload: impl FnOnce(&Path, &Path) -> Result<bool>,
) -> Result<Option<bool>> {
    let swapped = move_payload(plan.payload_dir, plan.store_dir)?;
    if !plan.link {
        if swapped {
            super::finalize_store_swap(plan.store_dir);
        }
        return Ok(None);
    }
    Ok(Some(swapped))
}

/// Link after the payload is in the store, and undo both if linking fails.
///
/// `link` pushes into the vector as it goes, so a failure halfway still names
/// every destination this attempt created. `undo` removes those. Windows
/// passes an unplace that also sweeps asides a running executable left behind.
#[cfg(any(unix, windows))]
pub(crate) fn commit_placement(
    store: &Path,
    swapped: bool,
    mut claims: Claims,
    link: impl FnOnce(&mut Vec<LinkRecord>, &mut Claims) -> Result<()>,
    undo: impl FnOnce(&[LinkRecord]) -> Result<()>,
) -> Result<Vec<LinkRecord>> {
    let mut links = Vec::new();
    let result = (|| -> Result<Vec<LinkRecord>> {
        link(&mut links, &mut claims)?;
        if links.is_empty() {
            return Err(Error::EmptyPayload(store.to_path_buf()));
        }
        Ok(std::mem::take(&mut links))
    })();
    match result {
        Ok(linked) => {
            claims.discard();
            if swapped {
                super::finalize_store_swap(store);
            }
            Ok(linked)
        }
        Err(e) => {
            let _ = undo(&links);
            claims.restore();
            if swapped {
                super::restore_retired_store(store);
            }
            Err(e)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exact_names_prefer_the_link_named_binary_over_a_helper_beside_it() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::write(tmp.path().join("rtok-hook"), b"hook").unwrap();
        std::fs::write(tmp.path().join("rtok"), b"main").unwrap();
        let specs = [BinSpec {
            path: Some("rtok*".into()),
            name: Some("rtok".into()),
        }];
        let got = resolve_bin_specs(tmp.path(), &specs, BinNames::Exact).unwrap();
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].0.file_name().unwrap(), "rtok");
        assert_eq!(got[0].1, "rtok");
    }

    #[test]
    fn windows_names_prefer_the_link_named_binary_over_a_helper_beside_it() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::write(tmp.path().join("rtok-hook.exe"), b"hook").unwrap();
        std::fs::write(tmp.path().join("rtok.exe"), b"main").unwrap();
        let specs = [BinSpec {
            path: Some("rtok*".into()),
            name: Some("rtok".into()),
        }];
        let got = resolve_bin_specs(tmp.path(), &specs, BinNames::Windows).unwrap();
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].0.file_name().unwrap(), "rtok.exe");
        assert_eq!(got[0].1, "rtok.exe");
    }

    #[test]
    fn exact_bin_names_match_only_the_same_spelling() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::write(tmp.path().join("Rtok"), b"other").unwrap();
        std::fs::write(tmp.path().join("rtok.exe"), b"exe").unwrap();
        std::fs::write(tmp.path().join("rtok"), b"main").unwrap();
        let specs = [BinSpec {
            path: None,
            name: Some("rtok".into()),
        }];
        let got = resolve_bin_specs(tmp.path(), &specs, BinNames::Exact).unwrap();
        assert_eq!(got[0].0.file_name().unwrap(), "rtok");
        assert_eq!(got[0].1, "rtok");
    }

    #[test]
    fn exact_bin_names_do_not_treat_exe_as_the_bare_name() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::write(tmp.path().join("rtok.exe"), b"main").unwrap();
        let specs = [BinSpec {
            path: None,
            name: Some("rtok".into()),
        }];
        assert!(resolve_bin_specs(tmp.path(), &specs, BinNames::Exact).is_err());
    }

    #[test]
    fn windows_bin_names_match_a_file_whose_case_differs() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::write(tmp.path().join("Rtok"), b"main").unwrap();
        let specs = [BinSpec {
            path: None,
            name: Some("rtok".into()),
        }];
        let got = resolve_bin_specs(tmp.path(), &specs, BinNames::Windows).unwrap();
        assert_eq!(got[0].0.file_name().unwrap(), "Rtok");
        assert_eq!(got[0].1, "rtok.exe");
    }

    #[test]
    fn windows_bin_names_find_a_launcher_suffix_and_spell_the_link_with_exe() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::write(tmp.path().join("Rtok.cmd"), b"main").unwrap();
        let specs = [BinSpec {
            path: None,
            name: Some("rtok".into()),
        }];
        let got = resolve_bin_specs(tmp.path(), &specs, BinNames::Windows).unwrap();
        assert_eq!(got[0].0.file_name().unwrap(), "Rtok.cmd");
        assert_eq!(got[0].1, "rtok.exe");
    }

    #[test]
    fn windows_bin_name_adds_exe_unless_already_suffixed() {
        assert_eq!(windows_bin_name("rg"), "rg.exe");
        assert_eq!(windows_bin_name("rg.exe"), "rg.exe");
        assert_eq!(windows_bin_name("tool.cmd"), "tool.cmd");
    }

    #[test]
    fn parent_dir_is_bin_ignores_ascii_case() {
        assert!(parent_dir_is_bin(Path::new("/store/pkg/bin/tool")));
        assert!(parent_dir_is_bin(Path::new("/store/pkg/Bin/tool")));
        assert!(parent_dir_is_bin(Path::new("/store/pkg/BIN/tool")));
        assert!(!parent_dir_is_bin(Path::new("/store/pkg/lib/tool")));
        assert!(!parent_dir_is_bin(Path::new("/store/pkg/tool")));
    }

    #[test]
    fn apple_bundle_directories_are_skipped_only_when_discovery_asks() {
        assert!(is_skipped_dir("foo.app", BundleDirs::Skip));
        assert!(is_skipped_dir("foo.framework", BundleDirs::Skip));
        assert!(!is_skipped_dir("foo.app", BundleDirs::Keep));
        assert!(!is_skipped_dir("foo.framework", BundleDirs::Keep));
        assert!(is_skipped_dir("share", BundleDirs::Keep));
        assert!(is_skipped_dir("share", BundleDirs::Skip));
        assert!(!is_skipped_dir("bin", BundleDirs::Skip));
    }

    #[test]
    fn an_app_links_no_cli_targets_under_either_naming_rule() {
        let tmp = tempfile::tempdir().unwrap();
        let platform = super::super::host().unwrap();
        let plan = Placement {
            name: "tool",
            payload_dir: tmp.path(),
            store_dir: tmp.path(),
            bin_dir: tmp.path(),
            apps_dir: tmp.path(),
            kind: PackageKind::App,
            bin_specs: &[],
            replacing: &[],
            link_apps: false,
            link: true,
            extras: &[],
        };
        for (names, bundles) in [
            (BinNames::Exact, BundleDirs::Skip),
            (BinNames::Windows, BundleDirs::Keep),
        ] {
            let got = cli_targets(platform.as_ref(), tmp.path(), &plan, names, bundles).unwrap();
            assert!(got.is_empty(), "{names:?}");
        }
    }

    #[cfg(unix)]
    #[test]
    fn a_payload_link_routed_through_another_link_out_of_the_tree_is_not_a_program() {
        let outer = tempfile::tempdir().unwrap();
        let root = outer.path().join("payload");
        std::fs::create_dir_all(&root).unwrap();
        std::fs::write(outer.path().join("outside"), b"#!/bin/sh\n").unwrap();
        std::fs::write(root.join("inside"), b"#!/bin/sh\n").unwrap();
        std::os::unix::fs::symlink(".", root.join("L")).unwrap();
        // Planted by a payload no extractor checked (a DMG or PKG copy): one
        // hop reads `abs`, which is inside, and stops there.
        std::os::unix::fs::symlink(outer.path().join("outside"), root.join("abs")).unwrap();
        std::os::unix::fs::symlink("abs", root.join("escape")).unwrap();
        std::os::unix::fs::symlink("L/inside", root.join("alias")).unwrap();

        let entry_for = |name: &str| {
            walkdir::WalkDir::new(&root)
                .into_iter()
                .filter_map(|e| e.ok())
                .find(|e| e.file_name() == name)
                .unwrap()
        };
        assert_eq!(payload_executable_entry(&entry_for("escape"), &root), None);
        assert_eq!(
            payload_executable_entry(&entry_for("alias"), &root),
            Some(root.join("alias"))
        );
    }
}

#[cfg(test)]
mod placement_tests {
    use super::*;
    use crate::model::{LinkKind, LinkRecord, LinkRole};

    fn copied(link: &Path, target: &Path) -> LinkRecord {
        LinkRecord {
            link: link.to_path_buf(),
            target: target.to_path_buf(),
            kind: LinkKind::CopiedFile,
            role: LinkRole::Binary,
        }
    }

    #[test]
    fn a_copied_file_matches_by_existence_or_by_bytes() {
        let tmp = tempfile::tempdir().unwrap();
        let link = tmp.path().join("tool");
        let target = tmp.path().join("store");
        std::fs::write(&link, b"v1").unwrap();
        std::fs::write(&target, b"v1").unwrap();
        let record = copied(&link, &target);
        assert!(still_placed(&record, CopiedFileProof::IsFile));
        assert!(still_placed(&record, CopiedFileProof::SameBytes));

        std::fs::write(&link, b"user").unwrap();
        assert!(
            still_placed(&record, CopiedFileProof::IsFile),
            "unix treats any file at the path as the copy it made"
        );
        assert!(
            !still_placed(&record, CopiedFileProof::SameBytes),
            "windows leaves a file whose bytes no longer match the store"
        );
    }

    #[test]
    fn unplace_removes_only_what_the_proof_still_accepts() {
        let tmp = tempfile::tempdir().unwrap();
        let keep = tmp.path().join("keep");
        let gone = tmp.path().join("gone");
        std::fs::write(&keep, b"user").unwrap();
        std::fs::write(&gone, b"ours").unwrap();
        let records = [
            copied(&keep, &tmp.path().join("a")),
            copied(&gone, &tmp.path().join("b")),
        ];
        unplace(&records, &crate::report::Report::silent(), |record| {
            record.link == gone
        })
        .unwrap();
        assert!(keep.is_file(), "a path the proof rejects must stay");
        assert!(std::fs::symlink_metadata(&gone).is_err());
    }

    #[test]
    fn writable_rejects_a_directory_that_is_not_there() {
        let tmp = tempfile::tempdir().unwrap();
        let missing = tmp.path().join("nope");
        assert_eq!(writable(&missing).unwrap_err(), "does not exist");
        assert!(writable(tmp.path()).is_ok());
    }
}
