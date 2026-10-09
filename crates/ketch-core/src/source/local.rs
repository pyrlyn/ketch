// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Packages that already live on the local filesystem.
//!
//! A `local:<path>` reference points at an archive, a bare binary, a symlink to
//! either, or a macOS `.app` bundle. There is no remote release stream: the
//! source synthesises a single release so the rest of the install pipeline can
//! stay source-agnostic. Paths are resolved to absolute form when recorded so
//! `ketch list` and `ketch info` keep working after the working directory moves.

use super::{ListOpts, Source};
use crate::cancel::Cancel;
use crate::error::{Error, Result};
use crate::extract::{is_bundle_name, read_head};
use crate::http;
use crate::model::{
    LocalKind, PackageRef, Release, ReleaseAsset, SourceInfo, Version, VersionSpec,
};
use crate::report::ProgressSink;
use std::fs;
use std::path::{Path, PathBuf};

/// Tag / version of the synthetic release every local install records.
pub const LOCAL_TAG: &str = "local";
pub const LOCAL_VERSION: &str = "0.0.0-local";

/// The built-in `local` source.
pub struct LocalSource;

impl LocalSource {
    pub fn new() -> Self {
        LocalSource
    }
}

impl Default for LocalSource {
    fn default() -> Self {
        Self::new()
    }
}

impl Source for LocalSource {
    fn scheme(&self) -> &str {
        "local"
    }

    fn describe(&self, id: &str) -> Result<Option<SourceInfo>> {
        let path = resolve_path(id)?;
        let kind = classify(&path)?;
        Ok(Some(SourceInfo {
            id: path_id(&path),
            name: file_label(&path),
            description: Some(format!("local {} at {}", kind.as_str(), path.display())),
            homepage: file_url(&path),
            stars: None,
            license: None,
            archived: false,
        }))
    }

    fn list_releases(&self, id: &str, _opts: &ListOpts) -> Result<Vec<Release>> {
        Ok(vec![synthetic_release(id)?])
    }

    fn resolve(&self, id: &str, want: &VersionSpec, _opts: &ListOpts) -> Result<Release> {
        // One synthetic release: exact requests for anything else fail the same
        // way a missing GitHub tag would. There is never a prerelease listing
        // to widen, so `_opts` is unused on purpose.
        let release = synthetic_release(id)?;
        match want {
            VersionSpec::Latest => Ok(release),
            VersionSpec::Exact(tag)
                if tag.eq_ignore_ascii_case(LOCAL_TAG)
                    || tag.eq_ignore_ascii_case(LOCAL_VERSION)
                    || release.version.matches_request(tag) =>
            {
                Ok(release)
            }
            VersionSpec::Exact(tag) => Err(Error::NoRelease(format!("{id}@{tag}"))),
        }
    }

    fn download(
        &self,
        asset: &ReleaseAsset,
        dest: &Path,
        progress: &dyn ProgressSink,
        cancel: &Cancel,
    ) -> Result<String> {
        cancel.check()?;
        let src = Path::new(&asset.url);
        if !src.exists() {
            return Err(Error::msg(format!(
                "local path does not exist: {}",
                src.display()
            )));
        }
        // Directories are handled in `install::prepare` (`.app` bundles). A
        // bare directory reaching download is a programming error upstream.
        if src.is_dir() {
            return Err(Error::msg(format!(
                "local path is a directory, not an installable file: {}",
                src.display()
            )));
        }
        ensure_local_payload(src)?;
        let size = fs::metadata(src).map(|m| m.len()).unwrap_or(0);
        progress.start(Some(size), &asset.name);
        if let Some(parent) = dest.parent() {
            fs::create_dir_all(parent).map_err(|e| Error::io(parent, e))?;
        }
        // `fs::copy` follows symlinks for content, which is what we want: the
        // payload is the target's bytes; the kind recorded on the package is
        // what remembered that the origin was a link.
        fs::copy(src, dest).map_err(|e| Error::io(dest, e))?;
        progress.advance(size);
        progress.finish("copied");
        http::sha256_file(dest)
    }

    fn web_url(&self, id: &str) -> Option<String> {
        resolve_path(id).ok().and_then(|p| file_url(&p))
    }
}

fn synthetic_release(id: &str) -> Result<Release> {
    let path = resolve_path(id)?;
    let kind = classify(&path)?;
    if matches!(kind, LocalKind::App) || path.is_dir() {
        // `.app` still needs a release so resolve/info work; download itself
        // refuses directories and `prepare` copies the bundle instead.
        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_else(|| "app".into());
        return Ok(Release {
            version: Version::parse(LOCAL_VERSION),
            tag: LOCAL_TAG.into(),
            prerelease: false,
            draft: false,
            published_at: None,
            notes: Some(format!("local {} at {}", kind.as_str(), path.display())),
            assets: vec![ReleaseAsset {
                name,
                url: path_id(&path),
                size: 0,
                content_type: None,
                digest: None,
                headers: Default::default(),
            }],
        });
    }
    let meta = fs::symlink_metadata(&path).map_err(|e| Error::io(&path, e))?;
    // For a symlink, report the link's name but size of the target when we can.
    let size = if meta.file_type().is_symlink() {
        fs::metadata(&path).map(|m| m.len()).unwrap_or(0)
    } else {
        meta.len()
    };
    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_else(|| "payload".into());
    Ok(Release {
        version: Version::parse(LOCAL_VERSION),
        tag: LOCAL_TAG.into(),
        prerelease: false,
        draft: false,
        published_at: None,
        notes: Some(format!("local {} at {}", kind.as_str(), path.display())),
        assets: vec![ReleaseAsset {
            name,
            url: path_id(&path),
            size,
            content_type: None,
            digest: None,
            headers: Default::default(),
        }],
    })
}

/// Turn a `local:` id into an absolute path, without requiring it to exist yet
/// when only normalising a user argument. Prefer [`resolve_path`] at use sites
/// that need the file to be there.
pub fn absolute_path(id: &str) -> Result<PathBuf> {
    let raw = id.trim();
    if raw.is_empty() {
        return Err(Error::msg("local path is empty"));
    }
    let path = PathBuf::from(raw);
    if path.is_absolute() {
        return Ok(path);
    }
    std::env::current_dir()
        .map(|cwd| cwd.join(path))
        .map_err(|e| Error::io(Path::new("."), e))
}

/// Absolute path that must exist (as a file, symlink, or `.app` directory).
pub fn resolve_path(id: &str) -> Result<PathBuf> {
    let path = absolute_path(id)?;
    let meta = match fs::symlink_metadata(&path) {
        Ok(m) => m,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            return Err(Error::msg(format!(
                "local path does not exist: {}",
                path.display()
            )));
        }
        Err(e) => return Err(Error::io(&path, e)),
    };
    // `exists` follows the link; a dangling symlink still has metadata, and
    // following it at download time would only produce a worse error.
    if meta.file_type().is_symlink() && !path.exists() {
        return Err(Error::msg(format!(
            "local symlink is dangling: {}",
            path.display()
        )));
    }
    Ok(path)
}

/// Build a `PackageRef` whose id is the absolute form of `path`.
pub fn package_ref(path: &Path) -> PackageRef {
    PackageRef::new("local", path_id(path))
}

/// Classify what a local path is, for display and for the install branch.
pub fn classify(path: &Path) -> Result<LocalKind> {
    let meta = fs::symlink_metadata(path).map_err(|e| Error::io(path, e))?;
    if meta.is_dir() {
        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();
        if is_bundle_name(&name) {
            return Ok(LocalKind::App);
        }
        return Err(Error::msg(format!(
            "refusing to install local directory `{}`; only a file, archive, or `.app` bundle is supported",
            path.display()
        )));
    }
    if meta.file_type().is_symlink() {
        let target = fs::metadata(path).map_err(|e| Error::io(path, e))?;
        if target.is_dir() {
            return Ok(LocalKind::Symlink);
        }
        ensure_is_regular_file(path, &target)?;
        return Ok(LocalKind::Symlink);
    }
    ensure_is_regular_file(path, &meta)?;
    let head = read_head(path)?;
    if looks_like_archive(path, &head) {
        Ok(LocalKind::Archive)
    } else {
        Ok(LocalKind::Binary)
    }
}

fn looks_like_archive(_path: &Path, head: &[u8]) -> bool {
    // Magic-byte detection mirrors `extract::archive`, without accepting the
    // catch-all raw-binary extractor.
    const GZIP: &[u8] = &[0x1f, 0x8b];
    const XZ: &[u8] = &[0xfd, b'7', b'z', b'X', b'Z', 0x00];
    const BZ2: &[u8] = b"BZh";
    const ZIP: &[u8] = &[b'P', b'K', 0x03, 0x04];
    if head.starts_with(GZIP)
        || head.starts_with(XZ)
        || head.starts_with(BZ2)
        || head.starts_with(ZIP)
    {
        return true;
    }
    if head.len() >= 262 && &head[257..262] == b"ustar" {
        return true;
    }
    false
}

fn path_id(path: &Path) -> String {
    path.to_string_lossy().into_owned()
}

fn file_label(path: &Path) -> String {
    path.file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_else(|| path_id(path))
}

fn file_url(path: &Path) -> Option<String> {
    Some(format!("file://{}", path.display()))
}

/// Refuse FIFOs, sockets and devices before `open` or `copy` can block on them.
///
/// Directories are left to `classify` / `prepare`; a symlink is followed only
/// far enough to see whether the payload is a regular file.
fn ensure_local_payload(path: &Path) -> Result<()> {
    let meta = fs::symlink_metadata(path).map_err(|e| Error::io(path, e))?;
    if meta.is_dir() {
        return Ok(());
    }
    if meta.file_type().is_symlink() {
        let target = fs::metadata(path).map_err(|e| Error::io(path, e))?;
        if target.is_dir() {
            return Ok(());
        }
        return ensure_is_regular_file(path, &target);
    }
    ensure_is_regular_file(path, &meta)
}

fn ensure_is_regular_file(path: &Path, meta: &fs::Metadata) -> Result<()> {
    if meta.is_file() {
        return Ok(());
    }
    Err(Error::msg(format!(
        "local path is not a regular file: {}",
        path.display()
    )))
}

/// Copy a symlink's target into `link` when the host cannot recreate links.
///
/// Used on Windows (and other non-Unix hosts) so `place` can stage a payload
/// that still contains real symlinks after archive extract. Mirrors
/// `extract::archive`'s materialise-as-copy policy.
#[cfg_attr(unix, allow(dead_code))] // Windows place path; unit-tested on Unix CI
fn materialize_local_symlink(symlink: &Path, link: &Path) -> Result<()> {
    let target = fs::read_link(symlink).map_err(|e| Error::io(symlink, e))?;
    let resolved = symlink
        .parent()
        .unwrap_or_else(|| Path::new("."))
        .join(&target);
    if resolved.is_file() {
        if let Some(parent) = link.parent() {
            fs::create_dir_all(parent).map_err(|e| Error::io(parent, e))?;
        }
        fs::copy(&resolved, link).map_err(|e| Error::io(link, e))?;
        return Ok(());
    }
    if resolved.is_dir() {
        copy_tree(&resolved, link)?;
        return Ok(());
    }
    // Last resort: `fs::copy` follows the link for file content.
    if symlink.is_file() {
        if let Some(parent) = link.parent() {
            fs::create_dir_all(parent).map_err(|e| Error::io(parent, e))?;
        }
        fs::copy(symlink, link).map_err(|e| Error::io(link, e))?;
        return Ok(());
    }
    Err(Error::msg(format!(
        "cannot materialise local symlink {} → {} (target missing)",
        symlink.display(),
        target.display()
    )))
}

/// Copy a directory tree into `dest`, preserving relative structure. Used for
/// local `.app` bundles so they reach `place` without a fake archive round-trip.
pub fn copy_tree(src: &Path, dest: &Path) -> Result<()> {
    fs::create_dir_all(dest).map_err(|e| Error::io(dest, e))?;
    for entry in walkdir::WalkDir::new(src) {
        let entry = entry.map_err(|e| Error::msg(format!("walking {}: {e}", src.display())))?;
        let rel = pathdiff::diff_paths(entry.path(), src)
            .filter(|r| {
                !r.components()
                    .any(|c| matches!(c, std::path::Component::ParentDir))
            })
            .ok_or_else(|| {
                Error::msg(format!(
                    "path {} escaped {}",
                    entry.path().display(),
                    src.display()
                ))
            })?;
        if rel.as_os_str().is_empty() {
            continue;
        }
        let target = dest.join(rel);
        let ft = entry.file_type();
        if ft.is_dir() {
            fs::create_dir_all(&target).map_err(|e| Error::io(&target, e))?;
        } else if ft.is_symlink() {
            #[cfg(unix)]
            {
                let link = fs::read_link(entry.path()).map_err(|e| Error::io(entry.path(), e))?;
                crate::platform::unix::symlink(&link, &target)?;
            }
            #[cfg(not(unix))]
            {
                // Archive extract may have created real Windows symlinks (Developer
                // Mode). `place` then stages the payload with this copy_tree — refusing
                // links here aborted installs that extract had already accepted.
                // Materialise as a file/dir copy, same policy as extract/archive.rs.
                materialize_local_symlink(entry.path(), &target)?;
            }
        } else {
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent).map_err(|e| Error::io(parent, e))?;
            }
            fs::copy(entry.path(), &target).map_err(|e| Error::io(&target, e))?;
        }
    }
    Ok(())
}

/// Stable digest of a directory's contents, for the sha256 field of a local `.app`.
///
/// Each file and each symlink is one framed record: kind, path, permission
/// bits, and either the file bytes or the symlink's own target. Lengths sit
/// in the frame so `a` = `X` plus `b` = `Y` cannot hash like a single `a`
/// whose bytes are `Xb\0Y`. The mode is in the record so dropping `+x`
/// changes the digest, and a symlink is not skipped: retargeting one inside
/// a bundle is a different tree. A lock written before this framing does not
/// match; `ketch lock` records the new digest.
pub fn sha256_tree(root: &Path) -> Result<String> {
    use sha2::{Digest, Sha256};
    let mut hasher = Sha256::new();
    let mut paths = Vec::new();
    for entry in walkdir::WalkDir::new(root) {
        let entry = entry.map_err(|e| Error::io(root, std::io::Error::other(e.to_string())))?;
        let kind = entry.file_type();
        // A fifo or device is not part of a bundle, and reading it can block.
        if kind.is_file() || kind.is_symlink() {
            paths.push(entry.into_path());
        }
    }
    paths.sort();
    for path in paths {
        let rel = path.strip_prefix(root).unwrap_or(&path);
        let meta = fs::symlink_metadata(&path).map_err(|e| Error::io(&path, e))?;
        let (kind, payload) = if meta.file_type().is_symlink() {
            let target = fs::read_link(&path).map_err(|e| Error::io(&path, e))?;
            (b'l', path_bytes(&target))
        } else {
            let bytes = fs::read(&path).map_err(|e| Error::io(&path, e))?;
            (b'f', bytes)
        };
        hash_record(
            &mut hasher,
            kind,
            &path_bytes(rel),
            permission_bits(&meta),
            &payload,
        );
    }
    Ok(hex::encode(hasher.finalize()))
}

/// `kind || len(path) || path || mode || len(payload) || payload`.
///
/// Fixed-width lengths keep a path or a payload from running into the next
/// field. `mode` is the permission bits only: owner and timestamps are not
/// part of what a lockfile is pinning.
fn hash_record(hasher: &mut sha2::Sha256, kind: u8, path: &[u8], mode: u32, payload: &[u8]) {
    use sha2::Digest;
    hasher.update([kind]);
    hasher.update((path.len() as u64).to_be_bytes());
    hasher.update(path);
    hasher.update(mode.to_be_bytes());
    hasher.update((payload.len() as u64).to_be_bytes());
    hasher.update(payload);
}

fn path_bytes(path: &Path) -> Vec<u8> {
    #[cfg(unix)]
    {
        use std::os::unix::ffi::OsStrExt;
        path.as_os_str().as_bytes().to_vec()
    }
    #[cfg(not(unix))]
    {
        path.to_string_lossy().into_owned().into_bytes()
    }
}

fn permission_bits(meta: &fs::Metadata) -> u32 {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        meta.permissions().mode() & 0o777
    }
    #[cfg(not(unix))]
    {
        if meta.permissions().readonly() {
            0o444
        } else {
            0o666
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn two_identical_trees_hash_the_same() {
        let a = tempfile::tempdir().unwrap();
        let b = tempfile::tempdir().unwrap();
        for dir in [a.path(), b.path()] {
            std::fs::create_dir(dir.join("Contents")).unwrap();
            std::fs::write(dir.join("Contents/Info.plist"), b"x").unwrap();
        }
        assert_eq!(
            sha256_tree(a.path()).unwrap(),
            sha256_tree(b.path()).unwrap()
        );
    }

    #[test]
    fn a_changed_file_changes_the_tree_hash() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("a"), b"one").unwrap();
        let first = sha256_tree(dir.path()).unwrap();
        std::fs::write(dir.path().join("a"), b"two").unwrap();
        assert_ne!(first, sha256_tree(dir.path()).unwrap());
    }

    /// `a` = `X` and `b` = `Y` used to hash the same as one file `a` = `Xb\0Y`,
    /// because the record was `path \0 bytes` with no lengths.
    #[test]
    fn two_files_do_not_hash_like_one_file_that_embeds_the_separator() {
        let split = tempfile::tempdir().unwrap();
        std::fs::write(split.path().join("a"), b"X").unwrap();
        std::fs::write(split.path().join("b"), b"Y").unwrap();
        let fused = tempfile::tempdir().unwrap();
        let mut bytes = b"X".to_vec();
        bytes.extend_from_slice(b"b\0Y");
        std::fs::write(fused.path().join("a"), bytes).unwrap();
        assert_ne!(
            sha256_tree(split.path()).unwrap(),
            sha256_tree(fused.path()).unwrap()
        );
    }

    #[cfg(unix)]
    #[test]
    fn a_mode_change_changes_the_tree_hash() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("a");
        std::fs::write(&file, b"same").unwrap();
        let first = sha256_tree(dir.path()).unwrap();
        std::fs::set_permissions(&file, std::fs::Permissions::from_mode(0o755)).unwrap();
        assert_ne!(first, sha256_tree(dir.path()).unwrap());
    }

    #[cfg(unix)]
    #[test]
    fn a_symlink_is_hashed_by_its_target_and_not_as_a_file() {
        let linked = tempfile::tempdir().unwrap();
        std::os::unix::fs::symlink("target-a", linked.path().join("a")).unwrap();
        let as_file = tempfile::tempdir().unwrap();
        std::fs::write(as_file.path().join("a"), b"target-a").unwrap();
        assert_ne!(
            sha256_tree(linked.path()).unwrap(),
            sha256_tree(as_file.path()).unwrap()
        );

        let empty = tempfile::tempdir().unwrap();
        let bare = sha256_tree(empty.path()).unwrap();
        assert_ne!(bare, sha256_tree(linked.path()).unwrap());

        let retargeted = tempfile::tempdir().unwrap();
        std::os::unix::fs::symlink("target-b", retargeted.path().join("a")).unwrap();
        assert_ne!(
            sha256_tree(linked.path()).unwrap(),
            sha256_tree(retargeted.path()).unwrap()
        );

        let again = tempfile::tempdir().unwrap();
        std::os::unix::fs::symlink("target-a", again.path().join("a")).unwrap();
        assert_eq!(
            sha256_tree(linked.path()).unwrap(),
            sha256_tree(again.path()).unwrap()
        );
    }

    #[test]
    fn classifies_a_bare_binary() {
        let dir = tempfile::tempdir().unwrap();
        let bin = dir.path().join("tool");
        fs::write(&bin, b"#!/bin/sh\necho hi\n").unwrap();
        assert_eq!(classify(&bin).unwrap(), LocalKind::Binary);
    }

    #[cfg(unix)]
    #[test]
    fn classifies_a_symlink() {
        let dir = tempfile::tempdir().unwrap();
        let bin = dir.path().join("tool");
        fs::write(&bin, b"#!/bin/sh\necho hi\n").unwrap();
        let link = dir.path().join("link");
        std::os::unix::fs::symlink(&bin, &link).unwrap();
        assert_eq!(classify(&link).unwrap(), LocalKind::Symlink);
    }

    /// Windows place stages via copy_tree after extract may have created real
    /// symlinks; materialise must write the target bytes, not error out.
    #[cfg(unix)]
    #[test]
    fn materialize_local_symlink_writes_the_target_file() {
        let dir = tempfile::tempdir().unwrap();
        fs::write(dir.path().join("tool.exe"), b"MZ").unwrap();
        let link = dir.path().join("tool");
        std::os::unix::fs::symlink(Path::new("tool.exe"), &link).unwrap();
        let out = dir.path().join("tool.materialised");
        materialize_local_symlink(&link, &out).unwrap();
        assert_eq!(fs::read(&out).unwrap(), b"MZ");
        assert!(!fs::symlink_metadata(&out).unwrap().file_type().is_symlink());
    }

    #[cfg(unix)]
    #[test]
    fn materialize_local_symlink_errors_when_target_is_missing() {
        let dir = tempfile::tempdir().unwrap();
        let link = dir.path().join("dangling");
        std::os::unix::fs::symlink(Path::new("missing.bin"), &link).unwrap();
        let err = materialize_local_symlink(&link, &dir.path().join("out")).unwrap_err();
        assert!(
            err.to_string().contains("cannot materialise local symlink"),
            "{err}"
        );
    }

    #[test]
    fn refuses_a_plain_directory() {
        let dir = tempfile::tempdir().unwrap();
        let nested = dir.path().join("stuff");
        fs::create_dir(&nested).unwrap();
        assert!(classify(&nested)
            .unwrap_err()
            .to_string()
            .contains("directory"));
    }

    #[test]
    fn classifies_an_app_bundle() {
        let dir = tempfile::tempdir().unwrap();
        let app = dir.path().join("Thing.app");
        fs::create_dir_all(app.join("Contents/MacOS")).unwrap();
        assert_eq!(classify(&app).unwrap(), LocalKind::App);
    }

    #[cfg(unix)]
    #[test]
    fn refuses_a_named_pipe_before_open_can_block() {
        use std::process::Command;

        let dir = tempfile::tempdir().unwrap();
        let fifo = dir.path().join("pipe");
        let status = Command::new("mkfifo").arg(&fifo).status().unwrap();
        assert!(status.success(), "mkfifo failed");
        let err = classify(&fifo).unwrap_err().to_string();
        assert!(
            err.contains("not a regular file"),
            "unexpected error: {err}"
        );
    }

    #[test]
    fn absolute_path_resolves_relative() {
        // Do not touch the process cwd: unit tests share it and a parallel
        // suite that also reads cwd would race.
        let got = absolute_path("rel/tool").unwrap();
        assert_eq!(got, std::env::current_dir().unwrap().join("rel/tool"));
        #[cfg(unix)]
        {
            let abs = absolute_path("/tmp/ketch-local-abs-tool").unwrap();
            assert_eq!(abs, PathBuf::from("/tmp/ketch-local-abs-tool"));
        }
        #[cfg(windows)]
        {
            let abs = absolute_path(r"C:\ketch-local-abs-tool").unwrap();
            assert_eq!(abs, PathBuf::from(r"C:\ketch-local-abs-tool"));
        }
    }
}
