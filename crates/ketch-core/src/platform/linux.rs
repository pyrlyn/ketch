// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Linux.
//!
//! Asset scoring understands GNU/musl triples. Binaries are bin-dir symlinks.
//! An AppImage is not one of them: `.appimage` stays a rejected extension
//! until the manifest says `kind = "app"`, and then the file stays in the
//! store and is launched from a `.desktop` entry. This module never executes
//! the AppImage.

use super::claim::Claims;
use super::unix::{
    clear_destination, destination_available, discover_executables, ensure_executable,
    link_planned_extras, move_into_store, place_cli, preflight_extras, unplace, writable,
};
use super::{
    finalize_store_swap, restore_retired_store, AssetScore, DoctorCheck, Placement, Platform,
};
use crate::config::Config;
use crate::error::{Error, Result};
use crate::extract::archive::is_program_head;
use crate::extract::Extractor;
use crate::model::{LinkKind, LinkRecord, LinkRole, PackageKind, TargetSpec};
use std::collections::HashSet;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

/// Native Linux CLI releases: tar/zip assets, bin-dir symlinks, no macOS
/// trust or app-bundle behaviour.
pub struct LinuxPlatform {
    target: TargetSpec,
}

impl Default for LinuxPlatform {
    fn default() -> Self {
        Self::new()
    }
}

impl LinuxPlatform {
    pub fn new() -> Self {
        LinuxPlatform {
            target: TargetSpec::host(),
        }
    }
}

impl Platform for LinuxPlatform {
    fn id(&self) -> &str {
        "linux"
    }

    fn target(&self) -> TargetSpec {
        self.target
    }

    fn score_asset(&self, asset_name: &str, allow_emulation: bool) -> Option<AssetScore> {
        super::scoring::score_linux_asset(
            asset_name,
            self.target.arch,
            allow_emulation,
            cfg!(target_env = "musl"),
        )
    }

    fn score_release_asset(
        &self,
        asset_name: &str,
        allow_emulation: bool,
        kind: PackageKind,
    ) -> Option<AssetScore> {
        super::scoring::score_linux_asset_as(
            asset_name,
            self.target.arch,
            allow_emulation,
            cfg!(target_env = "musl"),
            kind == PackageKind::App,
        )
    }

    fn extractors(&self) -> Vec<Box<dyn Extractor>> {
        crate::extract::archive::portable_extractors()
    }

    fn place(&self, plan: &Placement<'_>) -> Result<Vec<LinkRecord>> {
        if plan.kind == PackageKind::App {
            place_app(plan)
        } else {
            place_cli(self, plan)
        }
    }

    fn bin_candidates(&self, payload: &Path, kind: PackageKind, package: &str) -> Vec<PathBuf> {
        if kind == PackageKind::App {
            return Vec::new();
        }
        discover_executables(self, payload, package)
    }

    fn unplace(&self, links: &[LinkRecord], report: &crate::report::Report) -> Result<()> {
        unplace(links, report)
    }

    fn is_executable(&self, path: &Path) -> bool {
        let Ok(meta) = std::fs::metadata(path) else {
            return false;
        };
        if !meta.is_file() || meta.permissions().mode() & 0o111 == 0 {
            return false;
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
                    format!("mkdir -p {} && chmod u+w {}", dir.display(), dir.display()),
                ),
            });
        }
        checks
    }
}

/// One AppImage in the payload, and the launcher names it will be given.
struct PlannedApp {
    relative: PathBuf,
    desktop_name: String,
    icon: Option<PlannedIcon>,
}

struct PlannedIcon {
    bytes: Vec<u8>,
    extension: &'static str,
    size_dir: &'static str,
    file_name: String,
}

/// Place an app-declared AppImage: the file stays in the store, and a
/// `.desktop` entry (and an icon, when the payload has one) is written under
/// the user XDG directories.
///
/// An icon that cannot be read is not an error. The desktop entry is the
/// install; state records only the paths that were written, so uninstall
/// removes those and nothing else in the directory.
fn place_app(plan: &Placement<'_>) -> Result<Vec<LinkRecord>> {
    let package_dir = plan.store_dir.parent().unwrap_or(plan.store_dir);
    let apps = if plan.link {
        let apps = plan_apps(plan)?;
        preflight_apps(plan, &apps, package_dir)?;
        apps
    } else {
        Vec::new()
    };
    let swapped = move_into_store(plan.payload_dir, plan.store_dir)?;
    if !plan.link {
        if swapped {
            finalize_store_swap(plan.store_dir);
        }
        return Ok(Vec::new());
    }
    let mut links = Vec::new();
    let mut claims = Claims::default();
    let result = (|| -> Result<Vec<LinkRecord>> {
        for (index, app) in apps.iter().enumerate() {
            let target = plan.store_dir.join(&app.relative);
            if !target.is_file() {
                return Err(Error::msg(format!(
                    "AppImage {} is not in the package store",
                    target.display()
                )));
            }
            ensure_executable(&target)?;
            let icon_link = write_icon(plan, app, index, package_dir, &mut claims)?;
            let desktop = plan.desktop_dir.join(&app.desktop_name);
            let text = super::appimage::desktop_entry(
                plan.name,
                &target,
                icon_link.as_ref().map(|record| record.link.as_path()),
            )?;
            clear_destination(&desktop, package_dir, plan.replacing, &mut claims)?;
            write_public(&desktop, text.as_bytes())?;
            links.push(LinkRecord {
                link: desktop,
                target,
                kind: LinkKind::CopiedFile,
                role: LinkRole::Desktop,
            });
            if let Some(icon) = icon_link {
                links.push(icon);
            }
        }
        links.extend(link_planned_extras(
            plan.extras,
            plan.store_dir,
            package_dir,
            plan.replacing,
            &mut claims,
        )?);
        if links.is_empty() {
            return Err(Error::EmptyPayload(plan.store_dir.to_path_buf()));
        }
        Ok(std::mem::take(&mut links))
    })();
    match result {
        Ok(links) => {
            claims.discard();
            if swapped {
                finalize_store_swap(plan.store_dir);
            }
            Ok(links)
        }
        Err(e) => {
            let _ = unplace(&links, &crate::report::Report::silent());
            claims.restore();
            if swapped {
                restore_retired_store(plan.store_dir);
            }
            Err(e)
        }
    }
}

fn plan_apps(plan: &Placement<'_>) -> Result<Vec<PlannedApp>> {
    let images = find_appimages(plan.payload_dir);
    if images.is_empty() {
        return Err(Error::EmptyPayload(plan.payload_dir.to_path_buf()));
    }
    let count = images.len();
    let mut apps = Vec::with_capacity(count);
    for (index, image) in images.into_iter().enumerate() {
        let relative = image
            .strip_prefix(plan.payload_dir)
            .map_err(|_| Error::msg("AppImage path escaped the payload"))?
            .to_path_buf();
        if crate::extract::safe_member_path(&relative).is_err() {
            return Err(Error::msg(format!(
                "refusing to place AppImage `{}`",
                relative.display()
            )));
        }
        let icon = choose_icon(plan.payload_dir, &image, plan.name, index, count);
        let exec = plan.store_dir.join(&relative);
        let icon_dest = icon.as_ref().map(|icon| icon_path(plan.icon_dir, icon));
        // Refuse a path that would break the desktop file before anything moves.
        super::appimage::desktop_entry(plan.name, &exec, icon_dest.as_deref())?;
        apps.push(PlannedApp {
            relative,
            desktop_name: format!("{}.desktop", launcher_stem(plan.name, index, count)),
            icon,
        });
    }
    Ok(apps)
}

fn preflight_apps(plan: &Placement<'_>, apps: &[PlannedApp], owned: &Path) -> Result<()> {
    let mut destinations = HashSet::new();
    for app in apps {
        let desktop = plan.desktop_dir.join(&app.desktop_name);
        if !destinations.insert(desktop.clone()) {
            return Err(Error::msg(format!(
                "multiple payload entries want to create {}",
                desktop.display()
            )));
        }
        destination_available(&desktop, owned, plan.replacing)?;
        if let Some(icon) = &app.icon {
            let dest = icon_path(plan.icon_dir, icon);
            if !destinations.insert(dest.clone()) {
                return Err(Error::msg(format!(
                    "multiple payload entries want to create {}",
                    dest.display()
                )));
            }
            destination_available(&dest, owned, plan.replacing)?;
        }
    }
    preflight_extras(plan, owned)?;
    for extra in plan.extras {
        if !destinations.insert(extra.dest.clone()) {
            return Err(Error::msg(format!(
                "multiple payload entries want to create {}",
                extra.dest.display()
            )));
        }
    }
    Ok(())
}

fn write_icon(
    plan: &Placement<'_>,
    app: &PlannedApp,
    index: usize,
    owned: &Path,
    claims: &mut Claims,
) -> Result<Option<LinkRecord>> {
    let Some(icon) = &app.icon else {
        return Ok(None);
    };
    let dest = icon_path(plan.icon_dir, icon);
    // A copy inside the store is what uninstall compares against, so a file
    // the user put at the icon path afterwards is not deleted.
    let sidecar = plan
        .store_dir
        .join(format!(".ketch-icon-{index}.{}", icon.extension));
    clear_destination(&dest, owned, plan.replacing, claims)?;
    write_public(&sidecar, &icon.bytes)?;
    write_public(&dest, &icon.bytes)?;
    Ok(Some(LinkRecord {
        link: dest,
        target: sidecar,
        kind: LinkKind::CopiedFile,
        role: LinkRole::Icon,
    }))
}

fn choose_icon(
    root: &Path,
    appimage: &Path,
    package: &str,
    index: usize,
    count: usize,
) -> Option<PlannedIcon> {
    let bytes = super::appimage::sibling_icon(root, appimage)
        .map(|(_, bytes)| bytes)
        .or_else(|| super::appimage::embedded_icon(appimage))?;
    let (extension, size_dir) = super::appimage::icon_image(&bytes)?;
    Some(PlannedIcon {
        bytes,
        extension,
        size_dir,
        file_name: format!("{}.{extension}", launcher_stem(package, index, count)),
    })
}

fn icon_path(icon_dir: &Path, icon: &PlannedIcon) -> PathBuf {
    icon_dir
        .join("hicolor")
        .join(icon.size_dir)
        .join("apps")
        .join(&icon.file_name)
}

fn launcher_stem(package: &str, index: usize, count: usize) -> String {
    let base = crate::config::sanitize_component(package);
    if count == 1 {
        base
    } else {
        format!("{base}-{}", index + 1)
    }
}

fn find_appimages(root: &Path) -> Vec<PathBuf> {
    let mut found: Vec<PathBuf> = walkdir::WalkDir::new(root)
        .max_depth(3)
        .follow_links(false)
        .into_iter()
        .filter_map(|entry| entry.ok())
        .filter(|entry| entry.file_type().is_file())
        .map(|entry| entry.into_path())
        .filter(|path| {
            path.extension()
                .is_some_and(|ext| ext.eq_ignore_ascii_case("appimage"))
                && path.strip_prefix(root).is_ok_and(|rel| {
                    crate::extract::safe_member_path(rel).is_ok()
                        && !rel.components().any(|part| {
                            part.as_os_str()
                                .to_string_lossy()
                                .chars()
                                .any(|c| c.is_control())
                        })
                })
        })
        .collect();
    found.sort();
    found
}

fn write_public(path: &Path, bytes: &[u8]) -> Result<()> {
    let options = fs_kit::write::Options::new()
        .create_parent(true)
        .follow_symlinks(false)
        .mode(0o644);
    options
        .write(path, bytes)
        .map_err(|e| Error::fs_kit(path, e))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{LinkKind, LinkRole, PackageKind};
    use crate::platform::TrustVerdict;
    use std::os::unix::fs::PermissionsExt;
    use std::path::PathBuf;

    fn program(dir: &Path, name: &str, body: &[u8]) -> PathBuf {
        std::fs::create_dir_all(dir).unwrap();
        let path = dir.join(name);
        std::fs::write(&path, body).unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();
        path
    }

    fn placement<'a>(
        payload: &'a Path,
        store: &'a Path,
        bin: &'a Path,
        apps: &'a Path,
    ) -> Placement<'a> {
        Placement {
            name: "rg",
            payload_dir: payload,
            store_dir: store,
            bin_dir: bin,
            apps_dir: apps,
            desktop_dir: apps,
            icon_dir: apps,
            kind: PackageKind::Binary,
            bin_specs: &[],
            replacing: &[],
            link_apps: false,
            link: true,
            extras: &[],
        }
    }

    #[test]
    fn id_and_target_are_linux() {
        let p = LinuxPlatform::new();
        assert_eq!(p.id(), "linux");
        assert_eq!(p.target().os, crate::model::Os::Linux);
    }

    #[test]
    fn place_symlinks_a_discovered_binary() {
        let tmp = tempfile::tempdir().unwrap();
        let payload = tmp.path().join("payload");
        program(&payload.join("bin"), "rg", b"#!/bin/sh\necho rg\n");
        let store = tmp.path().join("store/rg/1.0");
        let bin = tmp.path().join("bin");
        std::fs::create_dir_all(&bin).unwrap();

        let links = LinuxPlatform::new()
            .place(&placement(&payload, &store, &bin, tmp.path()))
            .unwrap();
        assert_eq!(links.len(), 1);
        assert_eq!(links[0].kind, LinkKind::Symlink);
        assert_eq!(links[0].link, bin.join("rg"));
        assert!(bin.join("rg").is_symlink());
    }

    #[test]
    fn place_prefers_capital_bin_over_noisy_siblings() {
        let tmp = tempfile::tempdir().unwrap();
        let payload = tmp.path().join("payload");
        program(&payload.join("Bin"), "rg", b"#!/bin/sh\necho rg\n");
        program(&payload, "noise", b"#!/bin/sh\necho noise\n");
        let store = tmp.path().join("store/rg/1.0");
        let bin = tmp.path().join("bin");
        std::fs::create_dir_all(&bin).unwrap();

        let links = LinuxPlatform::new()
            .place(&placement(&payload, &store, &bin, tmp.path()))
            .unwrap();
        assert_eq!(links.len(), 1, "{links:?}");
        assert_eq!(links[0].link, bin.join("rg"));
        assert!(!bin.join("noise").exists());
    }

    #[test]
    fn unplace_leaves_a_file_the_user_put_where_a_link_was() {
        let tmp = tempfile::tempdir().unwrap();
        let store = tmp.path().join("store/pkg/1.0");
        let target = program(&store, "tool", b"#!/bin/sh\n");
        let bin = tmp.path().join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        let link = bin.join("tool");
        std::os::unix::fs::symlink(&target, &link).unwrap();
        let record = LinkRecord {
            link: link.clone(),
            target,
            kind: LinkKind::Symlink,
            role: LinkRole::Binary,
        };
        let p = LinuxPlatform::new();
        p.unplace(
            std::slice::from_ref(&record),
            &crate::report::Report::silent(),
        )
        .unwrap();
        assert!(std::fs::symlink_metadata(&link).is_err());

        std::fs::write(&link, b"mine").unwrap();
        p.unplace(&[record], &crate::report::Report::silent())
            .unwrap();
        assert_eq!(std::fs::read(&link).unwrap(), b"mine");
    }

    #[test]
    fn occupied_destination_that_is_not_ours_is_left_alone() {
        let tmp = tempfile::tempdir().unwrap();
        let payload = tmp.path().join("payload");
        program(&payload.join("bin"), "tool", b"#!/bin/sh\necho new\n");
        let store = tmp.path().join("store/pkg/1.0");
        let bin = tmp.path().join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        std::fs::write(bin.join("tool"), b"mine").unwrap();

        let err = LinuxPlatform::new()
            .place(&placement(&payload, &store, &bin, tmp.path()))
            .unwrap_err();
        assert!(err.to_string().contains("already exists"));
        assert_eq!(std::fs::read(bin.join("tool")).unwrap(), b"mine");
    }

    #[test]
    fn a_failed_relink_keeps_the_live_prefix_over_a_stale_old_copy() {
        let tmp = tempfile::tempdir().unwrap();
        let store = tmp.path().join("store/tool/1.0");
        program(&store, "tool", b"#!/bin/sh\necho live\n");
        std::fs::write(store.join("README"), b"live").unwrap();
        // Left by an earlier swap that was interrupted before its cleanup.
        // Relink places a payload that is already the store, so this copy
        // must not be put back when linking fails.
        let stale = tmp.path().join("store/tool/1.0.old");
        std::fs::create_dir_all(&stale).unwrap();
        std::fs::write(stale.join("tool"), b"stale").unwrap();
        let blocker = tmp.path().join("blocker");
        std::fs::write(&blocker, b"not a folder").unwrap();
        let extras = [crate::extra::ExtraPlacement {
            rel_path: "README".into(),
            dest: blocker.join("README"),
            role: LinkRole::Man,
        }];
        let bin = tmp.path().join("bin");
        std::fs::create_dir_all(&bin).unwrap();

        let placed = LinuxPlatform::new().place(&Placement {
            name: "tool",
            payload_dir: &store,
            store_dir: &store,
            bin_dir: &bin,
            apps_dir: tmp.path(),
            desktop_dir: tmp.path(),
            icon_dir: tmp.path(),
            kind: PackageKind::Binary,
            bin_specs: &[],
            replacing: &[],
            link_apps: false,
            link: true,
            extras: &extras,
        });

        assert!(placed.is_err(), "{placed:?}");
        assert_eq!(
            std::fs::read(store.join("tool")).unwrap(),
            b"#!/bin/sh\necho live\n"
        );
        assert_eq!(std::fs::read(stale.join("tool")).unwrap(), b"stale");
    }

    #[test]
    fn verify_trust_is_not_applicable() {
        let tmp = tempfile::tempdir().unwrap();
        assert_eq!(
            LinuxPlatform::new().verify_trust(tmp.path()).unwrap(),
            TrustVerdict::NotApplicable
        );
    }

    const PNG: &[u8] = include_bytes!("../../../../tests/fixtures/appimage-diricon.png");

    fn app_plan<'a>(
        payload: &'a Path,
        store: &'a Path,
        bin: &'a Path,
        desktop: &'a Path,
        icons: &'a Path,
    ) -> Placement<'a> {
        Placement {
            name: "demo",
            payload_dir: payload,
            store_dir: store,
            bin_dir: bin,
            apps_dir: desktop,
            desktop_dir: desktop,
            icon_dir: icons,
            kind: PackageKind::App,
            bin_specs: &[],
            replacing: &[],
            link_apps: false,
            link: true,
            extras: &[],
        }
    }

    #[test]
    fn an_appimage_declared_as_an_app_gets_a_desktop_entry_and_icon() {
        let tmp = tempfile::tempdir().unwrap();
        let payload = tmp.path().join("payload");
        program(&payload, "Demo.AppImage", b"#!/bin/sh\necho demo\n");
        std::fs::write(payload.join(".DirIcon"), PNG).unwrap();
        let store = tmp.path().join("store/demo/1.0");
        let desktop = tmp.path().join("applications");
        let icons = tmp.path().join("icons");
        let links = LinuxPlatform::new()
            .place(&app_plan(&payload, &store, tmp.path(), &desktop, &icons))
            .unwrap();
        let desktop_file = desktop.join("demo.desktop");
        let text = std::fs::read_to_string(&desktop_file).unwrap();
        assert!(text.contains("Type=Application"), "{text}");
        assert!(text.contains("X-Ketch-Package=demo"), "{text}");
        assert!(text.contains("Demo.AppImage"), "{text}");
        assert!(!text.contains('\r'), "{text}");
        let icon = icons.join("hicolor/256x256/apps/demo.png");
        assert_eq!(std::fs::read(&icon).unwrap(), PNG);
        assert!(store.join("Demo.AppImage").is_file());
        assert!(links.iter().any(|link| link.role == LinkRole::Desktop));
        assert!(links.iter().any(|link| link.role == LinkRole::Icon));
        assert!(
            !links.iter().any(|link| link.role == LinkRole::Binary),
            "an app is not linked onto PATH: {links:?}"
        );
    }

    #[test]
    fn an_app_without_an_icon_still_installs() {
        let tmp = tempfile::tempdir().unwrap();
        let payload = tmp.path().join("payload");
        program(&payload, "Demo.AppImage", b"#!/bin/sh\necho demo\n");
        let store = tmp.path().join("store/demo/1.0");
        let desktop = tmp.path().join("applications");
        let icons = tmp.path().join("icons");
        let links = LinuxPlatform::new()
            .place(&app_plan(&payload, &store, tmp.path(), &desktop, &icons))
            .unwrap();
        assert!(desktop.join("demo.desktop").is_file());
        assert!(!icons.exists() || icons.read_dir().unwrap().next().is_none());
        assert!(links.iter().all(|link| link.role != LinkRole::Icon));
    }

    #[test]
    fn a_binary_package_does_not_link_an_appimage() {
        let tmp = tempfile::tempdir().unwrap();
        let payload = tmp.path().join("payload");
        program(&payload.join("bin"), "tool", b"#!/bin/sh\necho tool\n");
        program(&payload, "Demo.AppImage", b"#!/bin/sh\necho app\n");
        let store = tmp.path().join("store/tool/1.0");
        let bin = tmp.path().join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        let links = LinuxPlatform::new()
            .place(&placement(&payload, &store, &bin, tmp.path()))
            .unwrap();
        assert_eq!(links.len(), 1, "{links:?}");
        assert_eq!(links[0].link, bin.join("tool"));
        assert!(!bin.join("Demo.AppImage").exists());
        assert!(!tmp.path().join("demo.desktop").exists());
    }

    #[test]
    fn an_appimage_is_not_executed_while_it_is_placed() {
        let tmp = tempfile::tempdir().unwrap();
        let payload = tmp.path().join("payload");
        let marker = tmp.path().join("ran");
        let script = format!("#!/bin/sh\ntouch {}\n", marker.display());
        program(&payload, "Demo.AppImage", script.as_bytes());
        let store = tmp.path().join("store/demo/1.0");
        LinuxPlatform::new()
            .place(&app_plan(
                &payload,
                &store,
                tmp.path(),
                &tmp.path().join("applications"),
                &tmp.path().join("icons"),
            ))
            .unwrap();
        assert!(!marker.exists(), "placing an AppImage must not run it");
    }

    #[test]
    fn uninstall_removes_the_desktop_entry_and_icon_and_leaves_neighbours() {
        let tmp = tempfile::tempdir().unwrap();
        let payload = tmp.path().join("payload");
        program(&payload, "Demo.AppImage", b"#!/bin/sh\necho demo\n");
        std::fs::write(payload.join(".DirIcon"), PNG).unwrap();
        let store = tmp.path().join("store/demo/1.0");
        let desktop_dir = tmp.path().join("applications");
        let icons = tmp.path().join("icons");
        let links = LinuxPlatform::new()
            .place(&app_plan(
                &payload,
                &store,
                tmp.path(),
                &desktop_dir,
                &icons,
            ))
            .unwrap();
        let neighbour = desktop_dir.join("other.desktop");
        std::fs::write(
            &neighbour,
            "[Desktop Entry]\nType=Application\nName=Other\n",
        )
        .unwrap();
        let icon = icons.join("hicolor/256x256/apps/demo.png");
        std::fs::write(icon.with_file_name("other.png"), PNG).unwrap();

        LinuxPlatform::new()
            .unplace(&links, &crate::report::Report::silent())
            .unwrap();
        assert!(!desktop_dir.join("demo.desktop").exists());
        assert!(!icon.exists());
        assert_eq!(
            std::fs::read_to_string(&neighbour).unwrap(),
            "[Desktop Entry]\nType=Application\nName=Other\n"
        );
        assert!(icon.with_file_name("other.png").is_file());
    }

    #[test]
    fn a_desktop_file_the_user_replaced_is_left_in_place() {
        let tmp = tempfile::tempdir().unwrap();
        let payload = tmp.path().join("payload");
        program(&payload, "Demo.AppImage", b"#!/bin/sh\necho demo\n");
        let store = tmp.path().join("store/demo/1.0");
        let desktop_dir = tmp.path().join("applications");
        let links = LinuxPlatform::new()
            .place(&app_plan(
                &payload,
                &store,
                tmp.path(),
                &desktop_dir,
                &tmp.path().join("icons"),
            ))
            .unwrap();
        let desktop = desktop_dir.join("demo.desktop");
        std::fs::write(&desktop, "mine\n").unwrap();
        LinuxPlatform::new()
            .unplace(&links, &crate::report::Report::silent())
            .unwrap();
        assert_eq!(std::fs::read_to_string(&desktop).unwrap(), "mine\n");
    }
}
