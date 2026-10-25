// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Unix symlink, mode and ownership helpers shared by the macOS and Linux
//! backends. macOS-only app placement stays in `macos.rs`.
#![cfg(unix)]

use super::claim::Claims;
use super::common::{cli_targets, BinNames, BundleDirs};
use super::Platform;
use crate::error::{Error, Result};
use crate::extra::ExtraPlacement;
use crate::model::{LinkKind, LinkRecord, LinkRole};
use std::os::unix::fs::PermissionsExt;
use std::path::Path;

#[cfg(all(target_os = "linux", test))]
use super::sibling;
#[cfg(target_os = "linux")]
use super::Placement;
#[cfg(all(target_os = "linux", test))]
use super::{finalize_store_swap, restore_retired_store};
#[cfg(target_os = "linux")]
use crate::source::local::copy_tree;
#[cfg(target_os = "linux")]
use std::collections::HashSet;

/// Add the bits a program needs to run, leaving every other bit alone.
///
/// Owner read comes with the execute bits: an archive can name a script member
/// `0o200`, and the kernel wants to read a script before it runs it — an
/// execute-only payload installs, records and then fails with "Permission
/// denied" the first time it is used.
pub(crate) fn ensure_executable(path: &Path) -> Result<()> {
    let meta = std::fs::metadata(path).map_err(|e| Error::io(path, e))?;
    let mode = meta.permissions().mode();
    if mode & 0o500 == 0o500 {
        return Ok(());
    }
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode | 0o500))
        .map_err(|e| Error::io(path, e))
}

pub(crate) fn symlink(target: &Path, link: &Path) -> Result<()> {
    std::os::unix::fs::symlink(target, link).map_err(|e| Error::io(link, e))
}

/// Whether what is at a recorded path is still what ketch put there.
///
/// A symlink carries its own evidence: it has to point where the record says.
/// A copied `.app` leaves no mark on disk at all, which is why the record
/// exists — but only a directory can be one, and a matching record must never
/// authorize deleting a file the user put in its place.
pub(crate) fn still_placed(record: &LinkRecord) -> bool {
    super::common::still_placed(record, super::common::CopiedFileProof::IsFile)
}

/// Whether an occupied destination is this package's own to replace.
///
/// Two kinds of evidence. A symlink pointing into `owned` — the package's
/// directory in the store, covering every version of it — was made by ketch for
/// this package. A copied `.app` leaves no mark on disk at all, so the only
/// evidence there is the record written when it was placed.
///
/// Either way the disk has the last word: a record whose path now holds
/// something else is stale — the user replaced it, or another package took the
/// name over — and replacing that would destroy work ketch never made.
///
/// Anything else is somebody else's: another package that claims the same
/// binary name, or an application the user installed themselves. Taking one
/// over silently means uninstalling this package later deletes it.
pub(crate) fn is_ours(link: &Path, owned: &Path, recorded: &[LinkRecord]) -> bool {
    if recorded
        .iter()
        .any(|record| record.link == link && still_placed(record))
    {
        return true;
    }
    // Lexical `starts_with` alone would treat `owned/../../elsewhere` as ours.
    let Ok(target) = std::fs::read_link(link) else {
        return false;
    };
    if target
        .components()
        .any(|c| matches!(c, std::path::Component::ParentDir))
    {
        return false;
    }
    target.starts_with(owned)
}

/// Clear a destination, or explain who already has it. What was there moves
/// aside into `claims`, so a placement that fails later can put it back.
pub(crate) fn clear_destination(
    link: &Path,
    owned: &Path,
    recorded: &[LinkRecord],
    claims: &mut Claims,
) -> Result<()> {
    destination_available(link, owned, recorded)?;
    claims.take(link)
}

pub(crate) fn destination_available(
    link: &Path,
    owned: &Path,
    recorded: &[LinkRecord],
) -> Result<()> {
    match std::fs::symlink_metadata(link) {
        Ok(_) if !is_ours(link, owned, recorded) => Err(Error::msg(format!(
            "{} already exists and was not installed by ketch for this package; \
             move it aside first",
            link.display()
        ))),
        Ok(_) => Ok(()),
        Err(_) => Ok(()),
    }
}

/// Linux copies with the local-source tree copy when a rename cannot cross filesystems.
#[cfg(target_os = "linux")]
pub(crate) fn move_into_store(payload: &Path, store: &Path) -> Result<bool> {
    super::swap_into_store(payload, store, copy_tree)
}

pub(crate) fn link_binary(
    target: &Path,
    bin_dir: &Path,
    name: &str,
    owned: &Path,
    recorded: &[LinkRecord],
    claims: &mut Claims,
) -> Result<LinkRecord> {
    std::fs::create_dir_all(bin_dir).map_err(|e| Error::io(bin_dir, e))?;
    let link = bin_dir.join(name);
    clear_destination(&link, owned, recorded, claims)?;
    ensure_executable(target)?;
    symlink(target, &link)?;
    Ok(LinkRecord {
        link,
        target: target.to_path_buf(),
        kind: LinkKind::Symlink,
        role: LinkRole::Binary,
    })
}

#[cfg(target_os = "linux")]
fn preflight_cli(platform: &dyn Platform, plan: &Placement<'_>, owned: &Path) -> Result<()> {
    let names: Vec<String> = cli_targets(
        platform,
        plan.payload_dir,
        plan,
        BinNames::Exact,
        BundleDirs::Skip,
    )?
    .into_iter()
    .map(|(_, name)| name)
    .collect();
    let mut destinations = HashSet::new();
    for name in names {
        let link = plan.bin_dir.join(name);
        if !destinations.insert(link.clone()) {
            return Err(Error::msg(format!(
                "multiple payload entries want to create {}",
                link.display()
            )));
        }
        destination_available(&link, owned, plan.replacing)?;
    }
    if destinations.is_empty() {
        return Err(Error::EmptyPayload(plan.payload_dir.to_path_buf()));
    }
    Ok(())
}

/// Place CLI binaries only: bin-dir symlinks, never an `.app`.
#[cfg(target_os = "linux")]
pub(crate) fn place_cli(platform: &dyn Platform, plan: &Placement<'_>) -> Result<Vec<LinkRecord>> {
    let package_dir = plan.store_dir.parent().unwrap_or(plan.store_dir);
    if plan.link {
        preflight_cli(platform, plan, package_dir)?;
        preflight_extras(plan, package_dir)?;
    }
    let Some(swapped) = super::common::swap_payload(plan, move_into_store)? else {
        return Ok(Vec::new());
    };
    super::common::commit_placement(
        plan.store_dir,
        swapped,
        Claims::default(),
        |links, claims| {
            for (target, name) in cli_targets(
                platform,
                plan.store_dir,
                plan,
                BinNames::Exact,
                BundleDirs::Skip,
            )? {
                links.push(link_binary(
                    &target,
                    plan.bin_dir,
                    &name,
                    package_dir,
                    plan.replacing,
                    claims,
                )?);
            }
            links.extend(link_planned_extras(
                plan.extras,
                plan.store_dir,
                package_dir,
                plan.replacing,
                claims,
            )?);
            Ok(())
        },
        |made| unplace(made, &crate::report::Report::silent()),
    )
}

/// Undo `place`. Must tolerate links that are already gone.
pub(crate) fn unplace(links: &[LinkRecord], report: &crate::report::Report) -> Result<()> {
    super::common::unplace(links, report, still_placed)
}

/// Confirm extra destinations are ours to take, and that the payload files exist.
pub(crate) fn preflight_extras(plan: &super::Placement<'_>, owned: &Path) -> Result<()> {
    let mut dests = std::collections::HashSet::new();
    for extra in plan.extras {
        crate::extra::resolve_under(plan.payload_dir, &extra.rel_path)?;
        if !dests.insert(extra.dest.clone()) {
            return Err(Error::msg(format!(
                "multiple extra_paths want to create {}",
                extra.dest.display()
            )));
        }
        destination_available(&extra.dest, owned, plan.replacing)?;
    }
    Ok(())
}

/// Symlink classified extras from `root` into their user destinations.
pub(crate) fn link_planned_extras(
    extras: &[ExtraPlacement],
    root: &Path,
    owned: &Path,
    recorded: &[LinkRecord],
    claims: &mut Claims,
) -> Result<Vec<LinkRecord>> {
    super::common::link_planned_extras(extras, root, |extra, target| {
        clear_destination(&extra.dest, owned, recorded, claims)?;
        symlink(target, &extra.dest)?;
        Ok(LinkRecord {
            link: extra.dest.clone(),
            target: target.to_path_buf(),
            kind: LinkKind::Symlink,
            role: extra.role,
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{LinkKind, LinkRecord, LinkRole};
    use std::path::PathBuf;

    #[test]
    fn making_a_binary_executable_leaves_every_other_bit_alone() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("tool");
        std::fs::write(&path, b"#!/bin/sh\n").unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).unwrap();

        ensure_executable(&path).unwrap();

        let mode = std::fs::metadata(path).unwrap().permissions().mode() & 0o7777;
        // Owner read and execute, and nothing for anyone else.
        assert_eq!(mode, 0o700);
    }

    #[test]
    fn making_a_binary_executable_adds_the_read_a_script_needs() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("tool");
        std::fs::write(&path, b"#!/bin/sh\n").unwrap();
        // What a tar member with a write-only header mode unpacks as.
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o200)).unwrap();

        ensure_executable(&path).unwrap();

        let mode = std::fs::metadata(path).unwrap().permissions().mode() & 0o7777;
        assert_eq!(mode, 0o700, "an execute-only script cannot run");
    }

    #[test]
    fn empty_destination_is_available() {
        let tmp = tempfile::tempdir().unwrap();
        let owned = tmp.path().join("store/pkg");
        std::fs::create_dir_all(&owned).unwrap();
        let link = tmp.path().join("bin/tool");
        assert!(destination_available(&link, &owned, &[]).is_ok());
    }

    #[test]
    fn a_symlink_into_owned_is_ours() {
        let tmp = tempfile::tempdir().unwrap();
        let owned = tmp.path().join("store/pkg");
        std::fs::create_dir_all(owned.join("1.0")).unwrap();
        let target = owned.join("1.0/tool");
        std::fs::write(&target, b"#!/bin/sh\n").unwrap();
        let link = tmp.path().join("bin/tool");
        std::fs::create_dir_all(link.parent().unwrap()).unwrap();
        std::os::unix::fs::symlink(&target, &link).unwrap();
        assert!(is_ours(&link, &owned, &[]));
        assert!(destination_available(&link, &owned, &[]).is_ok());
    }

    #[test]
    fn occupied_destination_that_is_not_ours_returns_an_error() {
        let tmp = tempfile::tempdir().unwrap();
        let owned = tmp.path().join("store/pkg");
        std::fs::create_dir_all(&owned).unwrap();
        let elsewhere = tmp.path().join("elsewhere");
        std::fs::write(&elsewhere, b"#!/bin/sh\n").unwrap();
        let link = tmp.path().join("bin/tool");
        std::fs::create_dir_all(link.parent().unwrap()).unwrap();
        std::os::unix::fs::symlink(&elsewhere, &link).unwrap();
        assert!(!is_ours(&link, &owned, &[]));
        assert!(destination_available(&link, &owned, &[]).is_err());
    }

    #[test]
    fn a_symlink_target_with_dotdot_is_not_treated_as_ours() {
        let tmp = tempfile::tempdir().unwrap();
        let owned = tmp.path().join("store/pkg");
        std::fs::create_dir_all(&owned).unwrap();
        let elsewhere = tmp.path().join("elsewhere");
        std::fs::write(&elsewhere, b"x").unwrap();
        let link = tmp.path().join("bin/tool");
        std::fs::create_dir_all(link.parent().unwrap()).unwrap();
        // Lexically under `owned`, but resolves outside via `..`.
        let sneaky = owned
            .join("1.0")
            .join("..")
            .join("..")
            .join("..")
            .join("elsewhere");
        assert!(
            sneaky.starts_with(&owned),
            "precondition: lexical starts_with alone would allow this"
        );
        std::os::unix::fs::symlink(&sneaky, &link).unwrap();
        assert!(
            !is_ours(&link, &owned, &[]),
            "`..` in a symlink target must not count as owned"
        );
        assert!(destination_available(&link, &owned, &[]).is_err());
    }

    /// Occupant at the destination path for table-driven ownership cases.
    enum Occupant {
        /// Plain file with no symlink mark — like a copied binary.
        RegularFile,
        /// Directory tree — like a copied `.app` bundle.
        Directory,
    }

    fn prepare_destination(tmp: &tempfile::TempDir, occupant: Occupant) -> (PathBuf, PathBuf) {
        let owned = tmp.path().join("store/pkg");
        std::fs::create_dir_all(&owned).unwrap();
        let link = tmp.path().join("dest");
        std::fs::create_dir_all(link.parent().unwrap()).unwrap();
        match occupant {
            Occupant::RegularFile => std::fs::write(&link, b"x").unwrap(),
            Occupant::Directory => {
                std::fs::create_dir_all(link.join("Contents")).unwrap();
                std::fs::write(link.join("Contents/Info.plist"), b"x").unwrap();
            }
        }
        (owned, link)
    }

    #[test]
    fn a_recorded_link_is_ours_only_while_the_disk_still_agrees() {
        // A symlink record whose link still points at the recorded target.
        let tmp = tempfile::tempdir().unwrap();
        let owned = tmp.path().join("store/pkg");
        std::fs::create_dir_all(owned.join("1.0")).unwrap();
        let target = owned.join("1.0/tool");
        std::fs::write(&target, b"#!/bin/sh\n").unwrap();
        let link = tmp.path().join("bin/tool");
        std::fs::create_dir_all(link.parent().unwrap()).unwrap();
        std::os::unix::fs::symlink(&target, &link).unwrap();
        let recorded = [LinkRecord {
            link: link.clone(),
            target,
            kind: LinkKind::Symlink,
            role: LinkRole::Binary,
        }];
        assert!(is_ours(&link, &owned, &recorded));
        assert!(destination_available(&link, &owned, &recorded).is_ok());

        // A copied bundle leaves no symlink behind, so the record is the only
        // evidence there can be — and only while the store copy is still there.
        let tmp = tempfile::tempdir().unwrap();
        let (owned, link) = prepare_destination(&tmp, Occupant::Directory);
        let target = owned.join("1.0/App.app");
        std::fs::create_dir_all(&target).unwrap();
        let recorded = [LinkRecord {
            link: link.clone(),
            target: target.clone(),
            kind: LinkKind::CopiedApp,
            role: LinkRole::Binary,
        }];
        assert!(is_ours(&link, &owned, &recorded));
        assert!(destination_available(&link, &owned, &recorded).is_ok());

        std::fs::remove_dir_all(&target).unwrap();
        assert!(
            !is_ours(&link, &owned, &recorded),
            "a CopiedApp record must not outlive the store copy it names"
        );
    }

    #[test]
    fn a_recorded_link_whose_place_now_holds_a_file_is_not_ours() {
        // The user replaced ketch's link with a copy of their own. A stale
        // record must not authorize deleting it.
        let tmp = tempfile::tempdir().unwrap();
        let (owned, link) = prepare_destination(&tmp, Occupant::RegularFile);
        let recorded = [LinkRecord {
            link: link.clone(),
            target: owned.join("1.0/tool"),
            kind: LinkKind::Symlink,
            role: LinkRole::Binary,
        }];

        assert!(!is_ours(&link, &owned, &recorded));
        assert!(destination_available(&link, &owned, &recorded).is_err());
        assert!(clear_destination(&link, &owned, &recorded, &mut Claims::default()).is_err());
        assert!(link.is_file(), "the user's file must survive");
    }

    #[test]
    fn a_recorded_symlink_that_points_somewhere_else_is_not_ours() {
        let tmp = tempfile::tempdir().unwrap();
        let owned = tmp.path().join("store/pkg");
        std::fs::create_dir_all(owned.join("1.0")).unwrap();
        let elsewhere = tmp.path().join("elsewhere");
        std::fs::write(&elsewhere, b"x").unwrap();
        let link = tmp.path().join("bin/tool");
        std::fs::create_dir_all(link.parent().unwrap()).unwrap();
        std::os::unix::fs::symlink(&elsewhere, &link).unwrap();
        let recorded = [LinkRecord {
            link: link.clone(),
            target: owned.join("1.0/tool"),
            kind: LinkKind::Symlink,
            role: LinkRole::Binary,
        }];

        assert!(!is_ours(&link, &owned, &recorded));
        assert!(clear_destination(&link, &owned, &recorded, &mut Claims::default()).is_err());
    }

    #[test]
    fn a_regular_file_without_a_matching_record_is_not_ours() {
        let tmp = tempfile::tempdir().unwrap();
        let (owned, link) = prepare_destination(&tmp, Occupant::RegularFile);
        assert!(!is_ours(&link, &owned, &[]));
        assert!(destination_available(&link, &owned, &[]).is_err());
    }

    #[test]
    fn a_directory_without_a_matching_record_is_not_ours() {
        let tmp = tempfile::tempdir().unwrap();
        let (owned, link) = prepare_destination(&tmp, Occupant::Directory);
        assert!(!is_ours(&link, &owned, &[]));
        assert!(destination_available(&link, &owned, &[]).is_err());
    }

    #[test]
    fn clear_destination_removes_an_owned_symlink() {
        let tmp = tempfile::tempdir().unwrap();
        let owned = tmp.path().join("store/pkg");
        std::fs::create_dir_all(owned.join("1.0")).unwrap();
        let target = owned.join("1.0/tool");
        std::fs::write(&target, b"#!/bin/sh\n").unwrap();
        let link = tmp.path().join("bin/tool");
        std::fs::create_dir_all(link.parent().unwrap()).unwrap();
        std::os::unix::fs::symlink(&target, &link).unwrap();

        clear_destination(&link, &owned, &[], &mut Claims::default()).unwrap();
        assert!(
            std::fs::symlink_metadata(&link).is_err(),
            "owned symlink should be gone"
        );
    }

    #[test]
    fn clear_destination_refuses_a_not_ours_occupant_and_leaves_it_in_place() {
        let tmp = tempfile::tempdir().unwrap();
        let (owned, link) = prepare_destination(&tmp, Occupant::RegularFile);

        assert!(clear_destination(&link, &owned, &[], &mut Claims::default()).is_err());
        assert!(link.is_file(), "must not be deleted");
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn a_failed_place_after_the_swap_restores_the_previous_store() {
        let tmp = tempfile::tempdir().unwrap();
        let store = tmp.path().join("store/tool/1.0");
        std::fs::create_dir_all(&store).unwrap();
        std::fs::write(store.join("tool"), b"working").unwrap();
        let payload = tmp.path().join("payload");
        std::fs::create_dir_all(&payload).unwrap();
        std::fs::write(payload.join("tool"), b"new").unwrap();
        move_into_store(&payload, &store).unwrap();
        assert_eq!(std::fs::read(store.join("tool")).unwrap(), b"new");
        assert!(sibling(&store, ".old").exists());
        restore_retired_store(&store);
        assert_eq!(std::fs::read(store.join("tool")).unwrap(), b"working");
        finalize_store_swap(&store);
        assert!(!sibling(&store, ".old").exists());
    }
}
