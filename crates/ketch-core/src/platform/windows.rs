// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Windows.
//!
//! Assets are `.exe` and `.zip`. Placement copies into the bin dir rather than
//! linking: creating a symlink needs a privilege most users do not have. Every
//! copy is recorded so uninstall can prove identity before deleting.

use super::claim::Claims;
use super::common::{cli_targets, discover_executables, writable, BinNames, BundleDirs};
use super::sibling;
use super::{AssetScore, DoctorCheck, Placement, Platform};
use crate::config::Config;
use crate::error::{Error, Result};
use crate::extra::ExtraPlacement;
use crate::extract::archive::is_program_head;
use crate::extract::Extractor;
use crate::model::{CompletionShell, LinkKind, LinkRecord, LinkRole, PackageKind, TargetSpec};
use crate::source::local::copy_tree;
use fs_kit::tree::remove_any;
use std::collections::HashSet;
use std::path::{Path, PathBuf};

/// Native Windows CLI releases: copy executables, record every destination.
pub struct WindowsPlatform {
    target: TargetSpec,
}

impl Default for WindowsPlatform {
    fn default() -> Self {
        Self::new()
    }
}

impl WindowsPlatform {
    pub fn new() -> Self {
        WindowsPlatform {
            target: TargetSpec::host(),
        }
    }
}

/// Windows `ERROR_ACCESS_DENIED` (5) / `ERROR_SHARING_VIOLATION` (32), plus
/// `PermissionDenied`. A running `.exe` typically cannot be deleted or
/// overwritten, but it *can* be renamed aside so a fresh copy can take its path.
fn is_busy(err: &std::io::Error) -> bool {
    err.kind() == std::io::ErrorKind::PermissionDenied
        || matches!(fs_kit::raw_os_error(err), Some(5) | Some(32))
}

fn busy_aside(path: &Path) -> PathBuf {
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0);
    sibling(path, &format!(".old.{}-{stamp}", std::process::id()))
}

/// Free `path` so a replacement can be written there.
///
/// Prefer delete. If the file is mapped by a live process (the usual Windows
/// `Access is denied` on `ketch self upgrade` / `ketch upgrade`), rename it
/// aside instead — the running image keeps its handle, and the destination
/// name becomes available for `copy`.
fn clear_for_replace(path: &Path) -> std::io::Result<()> {
    match remove_any(path) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) if is_busy(&e) => {
            let aside = busy_aside(path);
            let _ = remove_any(&aside);
            std::fs::rename(path, &aside)?;
            // Best-effort: often still fails while the image is mapped.
            let _ = std::fs::remove_file(&aside);
            Ok(())
        }
        Err(e) => Err(e),
    }
}

/// Remove the asides `clear_for_replace` left beside `links` once the
/// executable they held has stopped.
///
/// A running tool upgraded in place leaves `tool.exe.old.<pid>-<ms>` behind,
/// and nothing else will ever remove it: without this each upgrade of a tool
/// that was running adds one more. Best effort — one still running stays for
/// the next sweep.
fn sweep_busy_asides(links: &[LinkRecord]) {
    for record in links {
        let (Some(dir), Some(name)) = (
            record.link.parent(),
            record.link.file_name().and_then(|n| n.to_str()),
        ) else {
            continue;
        };
        let Ok(entries) = std::fs::read_dir(dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let aside = entry
                .file_name()
                .to_str()
                .is_some_and(|candidate| super::is_busy_aside_of(name, candidate));
            if aside && entry.file_type().is_ok_and(|t| t.is_file()) {
                let _ = std::fs::remove_file(entry.path());
            }
        }
    }
}

fn copy_over(from: &Path, to: &Path) -> std::io::Result<()> {
    match std::fs::copy(from, to) {
        Ok(_) => Ok(()),
        Err(e) if is_busy(&e) => {
            clear_for_replace(to)?;
            std::fs::copy(from, to).map(|_| ())
        }
        Err(e) => Err(e),
    }
}

/// Copies with `copy_tree` when a rename cannot cross filesystems.
fn move_into_store(payload: &Path, store: &Path) -> Result<bool> {
    super::swap_into_store(payload, store, copy_tree)
}

/// `recorded` with targets under `store` pointed at its retired copy.
///
/// A copy is ours only while its bytes match its target. Reinstalling into the
/// same store path — a new build under a dev package's floating tag — has just
/// moved the bytes those copies were made from to `.old`, so comparing against
/// the new store would call every one of our own copies someone else's.
fn retired_targets(recorded: &[LinkRecord], store: &Path) -> Vec<LinkRecord> {
    let retired = sibling(store, ".old");
    if retired.symlink_metadata().is_err() {
        return recorded.to_vec();
    }
    recorded
        .iter()
        .map(|record| {
            let mut record = record.clone();
            if let Ok(rel) = record.target.strip_prefix(store) {
                record.target = retired.join(rel);
            }
            record
        })
        .collect()
}

fn still_placed(record: &LinkRecord) -> bool {
    // Windows copies bytes, so a file the user replaced is not ours to delete.
    super::common::still_placed(record, super::common::CopiedFileProof::SameBytes)
}

fn dest_key(path: &Path) -> String {
    path.to_string_lossy().to_ascii_lowercase()
}

fn is_ours(link: &Path, recorded: &[LinkRecord]) -> bool {
    recorded
        .iter()
        .any(|record| dest_key(&record.link) == dest_key(link) && still_placed(record))
}

fn destination_available(link: &Path, recorded: &[LinkRecord]) -> Result<()> {
    match std::fs::symlink_metadata(link) {
        Ok(_) if !is_ours(link, recorded) => Err(Error::msg(format!(
            "{} already exists and was not installed by ketch for this package; \
             move it aside first",
            link.display()
        ))),
        Ok(_) | Err(_) => Ok(()),
    }
}

/// Clear a destination, or explain who already has it. What was there moves
/// aside into `claims` — a rename, which also frees the name of a running
/// `.exe` — so a placement that fails later can put it back.
fn clear_destination(link: &Path, recorded: &[LinkRecord], claims: &mut Claims) -> Result<()> {
    destination_available(link, recorded)?;
    claims.take(link)
}

fn copy_binary(
    target: &Path,
    bin_dir: &Path,
    name: &str,
    recorded: &[LinkRecord],
    claims: &mut Claims,
) -> Result<LinkRecord> {
    std::fs::create_dir_all(bin_dir).map_err(|e| Error::io(bin_dir, e))?;
    let link = bin_dir.join(name);
    // Case-insensitive: Tool.exe and tool.exe are the same destination.
    if let Ok(entries) = std::fs::read_dir(bin_dir) {
        for entry in entries.flatten() {
            if dest_key(&entry.path()) == dest_key(&link) {
                clear_destination(&entry.path(), recorded, claims)?;
                break;
            }
        }
    }
    clear_destination(&link, recorded, claims)?;
    copy_over(target, &link).map_err(|e| Error::io(&link, e))?;
    Ok(LinkRecord {
        link,
        target: target.to_path_buf(),
        kind: LinkKind::CopiedFile,
        role: LinkRole::Binary,
    })
}

pub(crate) fn link_planned_extras(
    extras: &[ExtraPlacement],
    root: &Path,
    recorded: &[LinkRecord],
    claims: &mut Claims,
) -> Result<Vec<LinkRecord>> {
    super::common::link_planned_extras(extras, root, |extra, target| {
        clear_destination(&extra.dest, recorded, claims)?;
        std::fs::copy(target, &extra.dest).map_err(|e| Error::io(&extra.dest, e))?;
        Ok(LinkRecord {
            link: extra.dest.clone(),
            target: target.to_path_buf(),
            kind: LinkKind::CopiedFile,
            role: extra.role,
        })
    })
}

impl Platform for WindowsPlatform {
    fn id(&self) -> &str {
        "windows"
    }

    fn target(&self) -> TargetSpec {
        self.target
    }

    fn score_asset(&self, asset_name: &str, _allow_emulation: bool) -> Option<AssetScore> {
        super::scoring::score_windows_asset(asset_name, self.target.arch)
    }

    fn extractors(&self) -> Vec<Box<dyn Extractor>> {
        crate::extract::archive::portable_extractors()
    }

    fn bin_candidates(&self, payload: &Path, kind: PackageKind, package: &str) -> Vec<PathBuf> {
        if kind == PackageKind::App {
            return Vec::new();
        }
        discover_executables(self, payload, package, BundleDirs::Keep)
    }

    fn place(&self, plan: &Placement<'_>) -> Result<Vec<LinkRecord>> {
        // Install, upgrade, relink and rollback all come through here.
        sweep_busy_asides(plan.replacing);
        if plan.link {
            let found = cli_targets(
                self,
                plan.payload_dir,
                plan,
                BinNames::Windows,
                BundleDirs::Keep,
            )?;
            let mut destinations = HashSet::new();
            for (_, name) in &found {
                let link = plan.bin_dir.join(name);
                if !destinations.insert(dest_key(&link)) {
                    return Err(Error::msg(format!(
                        "multiple payload entries want to create {}",
                        link.display()
                    )));
                }
                destination_available(&link, plan.replacing)?;
            }
            for extra in plan.extras {
                crate::extra::resolve_under(plan.payload_dir, &extra.rel_path)?;
                if !destinations.insert(dest_key(&extra.dest)) {
                    return Err(Error::msg(format!(
                        "multiple payload entries want to create {}",
                        extra.dest.display()
                    )));
                }
                destination_available(&extra.dest, plan.replacing)?;
            }
            if destinations.is_empty() {
                return Err(Error::EmptyPayload(plan.payload_dir.to_path_buf()));
            }
        }
        let Some(swapped) = super::common::swap_payload(plan, move_into_store)? else {
            return Ok(Vec::new());
        };
        let replacing = if swapped {
            retired_targets(plan.replacing, plan.store_dir)
        } else {
            plan.replacing.to_vec()
        };
        // Named like `clear_for_replace`'s asides: one a running exe keeps
        // undeletable is then the same kind of leftover, swept the same way.
        super::common::commit_placement(
            plan.store_dir,
            swapped,
            Claims::new(busy_aside),
            |links, claims| {
                for (target, name) in cli_targets(
                    self,
                    plan.store_dir,
                    plan,
                    BinNames::Windows,
                    BundleDirs::Keep,
                )? {
                    links.push(copy_binary(
                        &target,
                        plan.bin_dir,
                        &name,
                        &replacing,
                        claims,
                    )?);
                }
                links.extend(link_planned_extras(
                    plan.extras,
                    plan.store_dir,
                    &replacing,
                    claims,
                )?);
                Ok(())
            },
            |made| self.unplace(made, &crate::report::Report::silent()),
        )
    }

    fn completion_dir(&self, shell: CompletionShell) -> PathBuf {
        match shell {
            CompletionShell::Powershell => dirs::document_dir()
                .unwrap_or_else(super::data_home)
                .join("PowerShell")
                .join("Completions"),
            other => super::completion_dir_for(other),
        }
    }

    fn unplace(&self, links: &[LinkRecord], report: &crate::report::Report) -> Result<()> {
        sweep_busy_asides(links);
        super::common::unplace(links, report, still_placed)
    }

    fn is_executable(&self, path: &Path) -> bool {
        let Ok(meta) = std::fs::metadata(path) else {
            return false;
        };
        if !meta.is_file() {
            return false;
        }
        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().to_ascii_lowercase())
            .unwrap_or_default();
        if [".exe", ".cmd", ".bat", ".com", ".ps1"]
            .iter()
            .any(|s| name.ends_with(s))
        {
            return true;
        }
        crate::extract::read_head(path)
            .map(|head| is_program_head(&head))
            .unwrap_or(false)
    }

    fn doctor(&self, cfg: &Config) -> Vec<DoctorCheck> {
        let mut checks = Vec::new();
        for (label, dir) in [
            ("root", &cfg.root),
            ("store", &cfg.store_dir),
            ("bin", &cfg.bin_dir),
        ] {
            checks.push(match writable(dir) {
                Ok(()) => DoctorCheck::ok(label, format!("{} is writable", dir.display())),
                Err(e) => DoctorCheck::fail(
                    label,
                    format!("{}: {e}", dir.display()),
                    format!("mkdir {}", dir.display()),
                ),
            });
        }
        checks
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::PackageKind;

    fn cmd_body(says: &str) -> Vec<u8> {
        format!("@echo off\r\necho {says}\r\n").into_bytes()
    }

    fn placement<'a>(
        payload: &'a Path,
        store: &'a Path,
        bin: &'a Path,
        apps: &'a Path,
    ) -> Placement<'a> {
        Placement {
            name: "tool",
            payload_dir: payload,
            store_dir: store,
            bin_dir: bin,
            apps_dir: apps,
            kind: PackageKind::Binary,
            bin_specs: &[],
            replacing: &[],
            link_apps: false,
            link: true,
            extras: &[],
        }
    }

    #[test]
    fn place_copies_a_cmd_script_and_records_it() {
        let tmp = tempfile::tempdir().unwrap();
        let payload = tmp.path().join("payload");
        std::fs::create_dir_all(payload.join("bin")).unwrap();
        std::fs::write(payload.join("bin/tool.cmd"), cmd_body("v1")).unwrap();
        let store = tmp.path().join("store/tool/1.0");
        let bin = tmp.path().join("bin");
        std::fs::create_dir_all(&bin).unwrap();

        let links = WindowsPlatform::new()
            .place(&placement(&payload, &store, &bin, tmp.path()))
            .unwrap();
        assert_eq!(links.len(), 1);
        assert_eq!(links[0].kind, LinkKind::CopiedFile);
        assert_eq!(links[0].link, bin.join("tool.cmd"));
        assert!(bin.join("tool.cmd").is_file());
        assert!(!bin.join("tool.cmd").is_symlink());
    }

    #[test]
    fn placing_a_new_build_into_the_same_store_path_replaces_our_copy() {
        let tmp = tempfile::tempdir().unwrap();
        let store = tmp.path().join("store/tool/nightly");
        let bin = tmp.path().join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        let platform = WindowsPlatform::new();

        let first = tmp.path().join("first");
        std::fs::create_dir_all(first.join("bin")).unwrap();
        std::fs::write(first.join("bin/tool.cmd"), cmd_body("build 1")).unwrap();
        let placed = platform
            .place(&placement(&first, &store, &bin, tmp.path()))
            .unwrap();

        let second = tmp.path().join("second");
        std::fs::create_dir_all(second.join("bin")).unwrap();
        std::fs::write(second.join("bin/tool.cmd"), cmd_body("build 2")).unwrap();
        let mut plan = placement(&second, &store, &bin, tmp.path());
        plan.replacing = placed.as_slice();
        platform.place(&plan).unwrap();

        assert_eq!(
            std::fs::read(bin.join("tool.cmd")).unwrap(),
            cmd_body("build 2")
        );
    }

    #[test]
    fn discover_prefers_capital_bin_over_noisy_siblings() {
        let tmp = tempfile::tempdir().unwrap();
        let payload = tmp.path().join("payload");
        std::fs::create_dir_all(payload.join("Bin")).unwrap();
        std::fs::write(payload.join("Bin/tool.cmd"), cmd_body("keep")).unwrap();
        std::fs::write(payload.join("noise.cmd"), cmd_body("noise")).unwrap();
        let store = tmp.path().join("store/tool/1.0");
        let bin = tmp.path().join("bin");
        std::fs::create_dir_all(&bin).unwrap();

        let links = WindowsPlatform::new()
            .place(&placement(&payload, &store, &bin, tmp.path()))
            .unwrap();
        assert_eq!(links.len(), 1, "{links:?}");
        assert_eq!(links[0].link, bin.join("tool.cmd"));
        assert!(!bin.join("noise.cmd").exists());
    }

    #[test]
    fn case_insensitive_collision_is_the_same_destination() {
        let tmp = tempfile::tempdir().unwrap();
        let payload = tmp.path().join("payload");
        std::fs::create_dir_all(payload.join("bin")).unwrap();
        std::fs::write(payload.join("bin/Tool.cmd"), cmd_body("new")).unwrap();
        let store = tmp.path().join("store/tool/1.0");
        let bin = tmp.path().join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        std::fs::write(bin.join("tool.cmd"), cmd_body("user")).unwrap();

        let err = WindowsPlatform::new()
            .place(&placement(&payload, &store, &bin, tmp.path()))
            .unwrap_err();
        assert!(err.to_string().contains("already exists"));
        let body = std::fs::read(bin.join("tool.cmd")).unwrap();
        assert!(
            body.windows(4).any(|w| w == b"user"),
            "user-owned file must be left alone"
        );
    }

    #[test]
    fn the_sweep_removes_only_stamped_asides_of_recorded_links() {
        let tmp = tempfile::tempdir().unwrap();
        let bin = tmp.path().join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        let link = bin.join("tool.exe");
        std::fs::write(&link, b"current").unwrap();
        for name in [
            "tool.exe.old.4242-1700000000000",
            "tool.exe.old.backup",
            "other.exe.old.1-2",
        ] {
            std::fs::write(bin.join(name), b"x").unwrap();
        }
        let record = LinkRecord {
            link: link.clone(),
            target: tmp.path().join("store/tool/1.0/tool.exe"),
            kind: LinkKind::CopiedFile,
            role: LinkRole::Binary,
        };

        sweep_busy_asides(&[record]);

        let mut left: Vec<String> = std::fs::read_dir(&bin)
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        left.sort();
        assert_eq!(
            left,
            ["other.exe.old.1-2", "tool.exe", "tool.exe.old.backup"]
        );
    }

    #[test]
    fn unplace_refuses_to_delete_a_replaced_copy() {
        let tmp = tempfile::tempdir().unwrap();
        let store = tmp.path().join("store/pkg/1.0");
        std::fs::create_dir_all(&store).unwrap();
        let target = store.join("tool.cmd");
        std::fs::write(&target, cmd_body("ours")).unwrap();
        let bin = tmp.path().join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        let link = bin.join("tool.cmd");
        std::fs::copy(&target, &link).unwrap();
        let record = LinkRecord {
            link: link.clone(),
            target,
            kind: LinkKind::CopiedFile,
            role: LinkRole::Binary,
        };
        let p = WindowsPlatform::new();
        p.unplace(
            std::slice::from_ref(&record),
            &crate::report::Report::silent(),
        )
        .unwrap();
        assert!(std::fs::symlink_metadata(&link).is_err());

        std::fs::write(&link, cmd_body("mine")).unwrap();
        p.unplace(&[record], &crate::report::Report::silent())
            .unwrap();
        assert!(link.is_file(), "the user's file must survive");
    }

    #[test]
    fn unplace_refuses_to_delete_a_replaced_copied_app_without_a_store_target() {
        let tmp = tempfile::tempdir().unwrap();
        let store = tmp.path().join("store/pkg/1.0");
        let bundle = store.join("Thing.app");
        std::fs::create_dir_all(bundle.join("Contents")).unwrap();
        std::fs::write(bundle.join("Contents/Info.plist"), b"x").unwrap();
        let apps = tmp.path().join("Applications");
        std::fs::create_dir_all(&apps).unwrap();
        let link = apps.join("Thing.app");
        std::fs::create_dir_all(link.join("Contents")).unwrap();
        std::fs::write(link.join("Contents/mine.txt"), b"the user's own copy").unwrap();
        std::fs::remove_dir_all(&store).unwrap();
        let record = LinkRecord {
            link: link.clone(),
            target: bundle,
            kind: LinkKind::CopiedApp,
            role: LinkRole::Binary,
        };
        let p = WindowsPlatform::new();
        p.unplace(
            std::slice::from_ref(&record),
            &crate::report::Report::silent(),
        )
        .unwrap();
        assert!(
            link.join("Contents/mine.txt").is_file(),
            "a stale record must not authorize deleting the user's bundle"
        );
    }

    #[test]
    fn clear_for_replace_deletes_an_unlocked_file() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("tool.exe");
        std::fs::write(&path, b"v1").unwrap();
        clear_for_replace(&path).unwrap();
        assert!(std::fs::symlink_metadata(&path).is_err());
    }

    #[test]
    fn copy_binary_replaces_our_previous_copy() {
        let tmp = tempfile::tempdir().unwrap();
        let payload = tmp.path().join("payload");
        std::fs::create_dir_all(&payload).unwrap();
        std::fs::write(payload.join("tool.exe"), b"v2").unwrap();
        let store = tmp.path().join("store/tool/1.0");
        std::fs::create_dir_all(&store).unwrap();
        let store_target = store.join("tool.exe");
        // still_placed(CopiedFile) requires link bytes == recorded target bytes.
        std::fs::write(&store_target, b"v1").unwrap();
        let bin = tmp.path().join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        let link = bin.join("tool.exe");
        std::fs::write(&link, b"v1").unwrap();
        let recorded = [LinkRecord {
            link: link.clone(),
            target: store_target,
            kind: LinkKind::CopiedFile,
            role: LinkRole::Binary,
        }];
        let got = copy_binary(
            payload.join("tool.exe").as_path(),
            &bin,
            "tool.exe",
            &recorded,
            &mut Claims::default(),
        )
        .unwrap();
        assert_eq!(got.link, link);
        assert_eq!(std::fs::read(&link).unwrap(), b"v2");
    }

    #[test]
    fn is_busy_recognizes_access_denied_codes() {
        let denied = std::io::Error::from_raw_os_error(5);
        let sharing = std::io::Error::from_raw_os_error(32);
        assert!(is_busy(&denied));
        assert!(is_busy(&sharing));
        // `remove_any` comes from fs-kit, which wraps the OS error with its path.
        let wrapped = std::io::Error::other(fs_kit::PathError {
            path: PathBuf::from("ketch.exe"),
            source: std::io::Error::from_raw_os_error(32),
        });
        assert!(is_busy(&wrapped));
        assert!(!is_busy(&std::io::Error::from(
            std::io::ErrorKind::NotFound
        )));
    }

    /// A live Windows image can be renamed but not deleted/overwritten.
    /// `FILE_SHARE_NONE` is the wrong simulation (it blocks rename too); spawn
    /// a real process from a copied `cmd.exe` instead.
    #[cfg(windows)]
    fn spawn_running_cmd_copy(path: &Path) -> std::process::Child {
        use std::process::{Command, Stdio};

        let cmd = Path::new(r"C:\Windows\System32\cmd.exe");
        std::fs::copy(cmd, path).unwrap();
        Command::new(path)
            .arg("/K")
            .arg("echo holding")
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .expect("spawn copied cmd.exe")
    }

    #[cfg(windows)]
    #[test]
    fn clear_for_replace_renames_aside_a_running_exe() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("ketch.exe");
        let mut child = spawn_running_cmd_copy(&path);
        std::thread::sleep(std::time::Duration::from_millis(100));

        clear_for_replace(&path).unwrap();
        assert!(
            std::fs::symlink_metadata(&path).is_err(),
            "destination name must be free for the replacement copy"
        );
        let asides: Vec<_> = std::fs::read_dir(tmp.path())
            .unwrap()
            .filter_map(|e| e.ok())
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .filter(|n| n.starts_with("ketch.exe.old."))
            .collect();
        assert_eq!(asides.len(), 1, "expected one aside, got {asides:?}");

        std::fs::write(&path, b"new").unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), b"new");
        let _ = child.kill();
        let _ = child.wait();
    }

    #[cfg(windows)]
    #[test]
    fn copy_binary_replaces_a_running_recorded_exe() {
        let tmp = tempfile::tempdir().unwrap();
        let payload = tmp.path().join("payload");
        std::fs::create_dir_all(&payload).unwrap();
        std::fs::write(payload.join("tool.exe"), b"v2").unwrap();
        let store = tmp.path().join("store/tool/1.0");
        std::fs::create_dir_all(&store).unwrap();
        let store_target = store.join("tool.exe");
        let bin = tmp.path().join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        let link = bin.join("tool.exe");
        let mut child = spawn_running_cmd_copy(&link);
        std::fs::copy(&link, &store_target).unwrap();
        let recorded = [LinkRecord {
            link: link.clone(),
            target: store_target,
            kind: LinkKind::CopiedFile,
            role: LinkRole::Binary,
        }];
        std::thread::sleep(std::time::Duration::from_millis(100));

        copy_binary(
            payload.join("tool.exe").as_path(),
            &bin,
            "tool.exe",
            &recorded,
            &mut Claims::default(),
        )
        .unwrap();
        assert_eq!(std::fs::read(&link).unwrap(), b"v2");
        let _ = child.kill();
        let _ = child.wait();
    }
}
