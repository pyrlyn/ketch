// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Portable archive formats.
//!
//! Detection is by magic bytes, never by extension. Every entry path goes
//! through `super::safe_member_path` before it is joined onto the destination.

use super::{resolves_inside, safe_member_path, Budget, Extractor};
use crate::error::{Error, Result};
use std::fs::File;
use std::io::{self, BufReader, Read, Write};
use std::path::{Component, Path, PathBuf};
use std::sync::mpsc::{self, Receiver, SyncSender};
use std::thread;

/// Permission bits taken from an untrusted archive: never setuid/setgid/sticky.
fn payload_mode(mode: u32) -> u32 {
    mode & 0o777
}

fn set_unix_mode(path: &Path, mode: u32) -> Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode))
            .map_err(|e| Error::io(path, e))
    }
    #[cfg(not(unix))]
    {
        let _ = mode;
        let _ = path;
        Ok(())
    }
}

fn write_symlink(target: &Path, link: &Path) -> Result<()> {
    #[cfg(unix)]
    {
        crate::platform::unix::symlink(target, link)
    }
    #[cfg(windows)]
    {
        // Prefer a real symlink when the process may create one (Developer Mode
        // or SeCreateSymbolicLink). Fall back to a same-tree copy so a release
        // that ships `bin/tool -> tool.exe` still places a runnable file.
        let resolved = link.parent().unwrap_or_else(|| Path::new(".")).join(target);
        if resolved.is_dir() {
            if std::os::windows::fs::symlink_dir(target, link).is_ok() {
                return Ok(());
            }
        } else if std::os::windows::fs::symlink_file(target, link).is_ok() {
            return Ok(());
        }
        materialize_symlink_as_copy(target, link)
    }
    #[cfg(not(any(unix, windows)))]
    {
        materialize_symlink_as_copy(target, link)
    }
}

/// Copy the symlink target into `link` when it already exists under the payload.
///
/// Used on Windows (and other non-Unix hosts) when creating a real symlink is
/// refused: silently returning Ok without writing left packages missing their
/// linked binaries. Directories are copied too — releases ship `share/doc ->
/// ../docs` and without this branch Windows extract failed with "not a file".
#[cfg_attr(unix, allow(dead_code))] // called from write_symlink on Windows; tests on Unix
fn materialize_symlink_as_copy(target: &Path, link: &Path) -> Result<()> {
    let resolved = link.parent().unwrap_or_else(|| Path::new(".")).join(target);
    if resolved.is_file() {
        std::fs::copy(&resolved, link).map_err(|e| Error::io(link, e))?;
        return Ok(());
    }
    if resolved.is_dir() {
        // `d -> .` or `sub/d -> ..` passes the lexical target check, yet
        // copying it would copy the link's own directory into itself.
        let parent = link.parent().unwrap_or_else(|| Path::new("."));
        let source = std::fs::canonicalize(&resolved).map_err(|e| Error::io(&resolved, e))?;
        let home = std::fs::canonicalize(parent).map_err(|e| Error::io(parent, e))?;
        if home.starts_with(&source) {
            return Err(Error::msg(format!(
                "cannot materialise archive symlink {} → {}: the target is an ancestor of the link",
                link.display(),
                target.display()
            )));
        }
        copy_dir_contents(&resolved, link)?;
        return Ok(());
    }
    Err(Error::msg(format!(
        "cannot materialise archive symlink {} → {} (target missing; on Windows enable Developer Mode or ship a real file/dir)",
        link.display(),
        target.display()
    )))
}

/// Recursively copy `src` into `dest` for directory symlink materialisation.
///
/// Kept local to this module so extract does not depend on `source::local`
/// (that crate already depends on extract). Nested symlinks are skipped: the
/// outer materialisation already flattened what the archive could express.
#[cfg_attr(unix, allow(dead_code))]
fn copy_dir_contents(src: &Path, dest: &Path) -> Result<()> {
    // Nested links inside a materialised directory are left out: creating
    // them would re-enter the Windows privilege problem this path exists
    // to avoid, and following them could escape the payload root.
    fs_kit::tree::copy_tree(src, dest, fs_kit::tree::Links::Skip).map_err(|e| Error::fs_kit(src, e))
}

/// `.tar.gz` / `.tgz`
pub struct TarGzExtractor;

/// `.tar.xz` / `.txz`
pub struct TarXzExtractor;

/// `.tar.bz2`
pub struct TarBz2Extractor;

/// Uncompressed `.tar`
pub struct TarExtractor;

/// `.zip`
pub struct ZipExtractor;

/// `.gz` wrapping a single file rather than a tar stream.
pub struct GzFileExtractor;

/// `.xz` wrapping a single file rather than a tar stream.
pub struct XzFileExtractor;

/// `.bz2` wrapping a single file rather than a tar stream.
pub struct Bz2FileExtractor;

/// A bare executable published with no container at all.
///
/// Must be last in the extractor list: it accepts anything the others refused.
pub struct RawBinaryExtractor;

const GZIP_MAGIC: &[u8] = &[0x1f, 0x8b];
const XZ_MAGIC: &[u8] = &[0xfd, b'7', b'z', b'X', b'Z', 0x00];
const BZ2_MAGIC: &[u8] = b"BZh";
const ZIP_MAGIC: &[u8] = &[b'P', b'K', 0x03, 0x04];

/// The `ustar` marker tar writes at offset 257 of every header block.
fn looks_like_tar(head: &[u8]) -> bool {
    head.len() >= 262 && &head[257..262] == b"ustar"
}

/// Inflate just enough of a gzip stream to see whether a tar header follows.
///
/// This is what separates `tool.tar.gz` from `tool.gz` when the publisher named
/// the file wrongly, which happens often enough to matter.
fn gzip_inner_head(path: &Path) -> Vec<u8> {
    let Ok(file) = File::open(path) else {
        return Vec::new();
    };
    let mut decoder = flate2::read::GzDecoder::new(BufReader::new(file));
    read_head_of(&mut decoder, 512)
}

fn xz_inner_head(path: &Path) -> Vec<u8> {
    let Ok(file) = File::open(path) else {
        return Vec::new();
    };
    let mut reader = BufReader::new(file);
    let mut out = CapWrite {
        buf: Vec::new(),
        cap: 512,
    };
    let _ = lzma_rs::xz_decompress(&mut reader, &mut out);
    out.buf
}

fn bz2_inner_head(path: &Path) -> Vec<u8> {
    let Ok(file) = File::open(path) else {
        return Vec::new();
    };
    let mut decoder = bzip2::read::BzDecoder::new(BufReader::new(file));
    read_head_of(&mut decoder, 512)
}

/// Collects a prefix of decompressed bytes, then errors so the decoder stops.
struct CapWrite {
    buf: Vec<u8>,
    cap: usize,
}

impl Write for CapWrite {
    fn write(&mut self, data: &[u8]) -> io::Result<usize> {
        if self.buf.len() >= self.cap {
            return Err(io::Error::new(io::ErrorKind::WriteZero, "head filled"));
        }
        let take = (self.cap - self.buf.len()).min(data.len());
        self.buf.extend_from_slice(&data[..take]);
        Ok(take)
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// Stream xz plaintext as `Read` so unpacking never holds the whole file in RAM.
fn xz_reader(file: File) -> impl Read {
    let (tx, rx): (SyncSender<io::Result<Vec<u8>>>, Receiver<_>) = mpsc::sync_channel(2);
    thread::spawn(move || {
        let mut input = BufReader::new(file);
        let mut output = ChannelWrite(tx.clone());
        if let Err(e) = lzma_rs::xz_decompress(&mut input, &mut output) {
            let _ = tx.send(Err(io::Error::new(io::ErrorKind::InvalidData, e)));
        }
    });
    ChannelRead {
        rx,
        buf: Vec::new(),
        at: 0,
    }
}

struct ChannelWrite(SyncSender<io::Result<Vec<u8>>>);

impl Write for ChannelWrite {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        self.0
            .send(Ok(buf.to_vec()))
            .map_err(|e| io::Error::new(io::ErrorKind::BrokenPipe, e))?;
        Ok(buf.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

struct ChannelRead {
    rx: Receiver<io::Result<Vec<u8>>>,
    buf: Vec<u8>,
    at: usize,
}

impl Read for ChannelRead {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        loop {
            if self.at < self.buf.len() {
                let n = (self.buf.len() - self.at).min(out.len());
                out[..n].copy_from_slice(&self.buf[self.at..self.at + n]);
                self.at += n;
                return Ok(n);
            }
            match self.rx.recv() {
                Ok(Ok(next)) => {
                    self.buf = next;
                    self.at = 0;
                    if self.buf.is_empty() {
                        continue;
                    }
                }
                Ok(Err(e)) => return Err(e),
                Err(_) => return Ok(0),
            }
        }
    }
}

fn read_head_of<R: Read>(reader: &mut R, want: usize) -> Vec<u8> {
    let mut buffer = vec![0u8; want];
    let mut filled = 0;
    while filled < buffer.len() {
        match reader.read(&mut buffer[filled..]) {
            Ok(0) => break,
            Ok(n) => filled += n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => break,
        }
    }
    buffer.truncate(filled);
    buffer
}

/// A link inside the payload may only point at something else inside it.
///
/// Checked lexically, before the link exists: a symlink to `../../../.ssh`
/// would otherwise turn the next `place` into an arbitrary-file copy.
fn check_link_target(member: &Path, target: &Path) -> Result<()> {
    let reject = || {
        Err(Error::msg(format!(
            "refusing archive link {} -> {} that escapes the target directory",
            member.display(),
            target.display()
        )))
    };
    if target.is_absolute() {
        return reject();
    }
    // Depth of the directory holding the link, relative to the payload root.
    let mut depth = member.components().count() as i64 - 1;
    for component in target.components() {
        match component {
            Component::CurDir => {}
            Component::Normal(_) => depth += 1,
            Component::ParentDir => {
                depth -= 1;
                if depth < 0 {
                    return reject();
                }
            }
            Component::RootDir | Component::Prefix(_) => return reject(),
        }
    }
    Ok(())
}

/// Walk every component of `path` below `dest`, refusing any that is a symlink.
///
/// This is what makes `check_link_target` sound. That guard counts depth from
/// the member *name*, but `create_dir_all` and every write follow links, so a
/// member called `a/link/b` lands wherever `link` points once an earlier member
/// of the same archive planted it — and the surplus `..` in a later target then
/// escapes `dest`. Refusing to traverse any link below `dest` keeps the path we
/// counted and the path the kernel resolves the same one, for files,
/// directories, symlinks and hard links alike.
///
/// `dest` itself is ketch's own directory and is trusted: on macOS it usually
/// *is* reached through a symlink (`/tmp` -> `private/tmp`).
fn walk_inside(dest: &Path, path: &Path, create: bool) -> Result<()> {
    let rel = path.strip_prefix(dest).map_err(|_| {
        Error::msg(format!(
            "refusing archive entry {} outside {}",
            path.display(),
            dest.display()
        ))
    })?;
    let mut built = dest.to_path_buf();
    for component in rel.components() {
        built.push(component);
        match std::fs::symlink_metadata(&built) {
            Ok(meta) if meta.file_type().is_symlink() => {
                return Err(Error::msg(format!(
                    "refusing archive entry that resolves through {}",
                    built.display()
                )))
            }
            // An ordinary directory, or a file at the leaf: both are fine.
            Ok(_) => {}
            Err(_) if create => std::fs::create_dir(&built).map_err(|e| Error::io(&built, e))?,
            // Nothing exists from here down, so nothing below can be a link.
            Err(_) => break,
        }
    }
    Ok(())
}

/// Make `out` safe to write: its directories exist, none of them is a link,
/// and `out` itself is not a link an earlier member planted for us to write
/// through (zip's `File::create` would happily truncate the far end).
fn ensure_parent(dest: &Path, out: &Path) -> Result<()> {
    if let Some(parent) = out.parent() {
        walk_inside(dest, parent, true)?;
    }
    if std::fs::symlink_metadata(out).is_ok_and(|m| m.file_type().is_symlink()) {
        std::fs::remove_file(out).map_err(|e| Error::io(out, e))?;
    }
    Ok(())
}

/// Unpack a tar stream, validating every member path and link target.
///
/// Written out rather than using `Archive::unpack` so the traversal guard is
/// ours and applies identically to files, directories and links.
///
/// Symlinks and hard links are deferred until every regular member is on disk:
/// Windows materialisation copies the target file, and tar/zip often list the
/// link before the file it points at.
fn unpack_tar<R: Read>(reader: R, dest: &Path, budget: &mut Budget) -> Result<()> {
    use tar::EntryType;

    let mut archive = tar::Archive::new(reader);
    archive.set_preserve_permissions(true);
    archive.set_overwrite(true);

    let mut deferred_symlinks: Vec<(PathBuf, PathBuf)> = Vec::new();
    let mut deferred_hardlinks: Vec<(PathBuf, PathBuf)> = Vec::new();
    let mut deferred_dir_modes: Vec<(PathBuf, u32)> = Vec::new();

    for entry in archive.entries().map_err(|e| Error::io(dest, e))? {
        let mut entry = entry.map_err(|e| Error::io(dest, e))?;
        let raw = entry.path().map_err(|e| Error::io(dest, e))?.into_owned();
        let kind = entry.header().entry_type();

        // Extended headers carry metadata, not payload, and have no real path.
        if kind.is_pax_global_extensions()
            || kind.is_pax_local_extensions()
            || kind.is_gnu_longname()
        {
            continue;
        }
        let safe = safe_member_path(&raw)?;
        let out = dest.join(&safe);
        budget.entry()?;

        match kind {
            EntryType::Directory => {
                // Created here, its mode set only once everything is written:
                // a 0555 directory listed before its children would refuse
                // them, and one listed after would refuse removal later.
                walk_inside(dest, &out, true)?;
                if let Ok(mode) = entry.header().mode() {
                    deferred_dir_modes.push((out, mode));
                }
            }
            EntryType::Symlink => {
                let target = entry
                    .link_name()
                    .map_err(|e| Error::io(&out, e))?
                    .ok_or_else(|| {
                        Error::msg(format!("archive symlink {} has no target", raw.display()))
                    })?
                    .into_owned();
                check_link_target(&safe, &target)?;
                ensure_parent(dest, &out)?;
                deferred_symlinks.push((out, target));
            }
            EntryType::Link => {
                // A tar hard link names its target from the archive root.
                let target = entry
                    .link_name()
                    .map_err(|e| Error::io(&out, e))?
                    .ok_or_else(|| {
                        Error::msg(format!("archive hard link {} has no target", raw.display()))
                    })?
                    .into_owned();
                let safe_target = safe_member_path(&target)?;
                // Hard-link targets are rooted at the archive prefix; the actual
                // link waits until that member has been unpacked.
                ensure_parent(dest, &out)?;
                deferred_hardlinks.push((out, safe_target));
            }
            EntryType::Regular | EntryType::Continuous | EntryType::GNUSparse => {
                // `size()` is the unpacked size, sparse holes included, so it
                // is charged before a byte reaches the disk.
                budget.bytes(entry.size())?;
                ensure_parent(dest, &out)?;
                unpack_member(&mut entry, &out)?;
            }
            // Character/block devices and fifos have no place in a release.
            _ => continue,
        }
    }
    finish_deferred_links(dest, deferred_hardlinks, deferred_symlinks)?;
    apply_dir_modes(deferred_dir_modes)
}

/// Set directory modes after every member is on disk, deepest first, always
/// leaving the owner `rwx`: ketch has to be able to remove its own store on
/// uninstall and upgrade, whatever the archive asked for.
fn apply_dir_modes(mut dirs: Vec<(PathBuf, u32)>) -> Result<()> {
    dirs.sort_by_key(|(path, _)| std::cmp::Reverse(path.components().count()));
    for (path, mode) in dirs {
        set_unix_mode(&path, payload_mode(mode) | 0o700)?;
    }
    Ok(())
}

fn unpack_member<R: Read>(entry: &mut tar::Entry<'_, R>, out: &Path) -> Result<()> {
    let mode = entry.header().mode().ok();
    entry.unpack(out).map_err(|e| Error::io(out, e))?;
    if let Some(mode) = mode {
        set_unix_mode(out, payload_mode(mode))?;
    }
    Ok(())
}

/// Create hard links then symlinks after the rest of the archive is on disk.
fn finish_deferred_links(
    dest: &Path,
    hardlinks: Vec<(PathBuf, PathBuf)>,
    symlinks: Vec<(PathBuf, PathBuf)>,
) -> Result<()> {
    for (out, safe_target) in hardlinks {
        // The kernel resolves the source too, so a hard link to
        // `planted-link/.ssh/id_rsa` would pull a file from outside the
        // payload into it.
        let source = dest.join(&safe_target);
        walk_inside(dest, &source, false)?;
        write_hardlink(&source, &out)?;
    }
    let mut written = Vec::with_capacity(symlinks.len());
    for (out, target) in symlinks {
        write_symlink(&target, &out)?;
        written.push((out, target));
    }
    // Only now, with every link in place, does a target mean what the kernel
    // will make of it: a later link can reroute an earlier one.
    for (out, target) in written {
        let is_link = std::fs::symlink_metadata(&out).is_ok_and(|m| m.file_type().is_symlink());
        if is_link && !resolves_inside(&out, dest) {
            return Err(Error::msg(format!(
                "refusing archive link {} -> {} that resolves outside the target directory",
                out.display(),
                target.display()
            )));
        }
    }
    Ok(())
}

/// Prefer a real hard link; fall back to a same-tree copy when the filesystem
/// refuses (cross-volume extracts, some Windows setups) — the same policy as
/// [`materialize_symlink_as_copy`] for symlinks.
fn write_hardlink(source: &Path, out: &Path) -> Result<()> {
    match std::fs::hard_link(source, out) {
        Ok(()) => Ok(()),
        Err(e) => {
            if source.is_file() {
                std::fs::copy(source, out).map_err(|e| Error::io(out, e))?;
                return Ok(());
            }
            Err(Error::io(out, e))
        }
    }
}

impl Extractor for TarGzExtractor {
    fn id(&self) -> &str {
        "tar.gz"
    }
    fn detect(&self, path: &Path, head: &[u8]) -> bool {
        head.starts_with(GZIP_MAGIC) && looks_like_tar(&gzip_inner_head(path))
    }
    fn extract(&self, src: &Path, dest: &Path) -> Result<()> {
        let file = File::open(src).map_err(|e| Error::io(src, e))?;
        unpack_tar(
            flate2::read::GzDecoder::new(BufReader::new(file)),
            dest,
            &mut Budget::for_archive(src)?,
        )
    }
}

impl Extractor for TarXzExtractor {
    fn id(&self) -> &str {
        "tar.xz"
    }
    fn detect(&self, path: &Path, head: &[u8]) -> bool {
        head.starts_with(XZ_MAGIC) && looks_like_tar(&xz_inner_head(path))
    }
    fn extract(&self, src: &Path, dest: &Path) -> Result<()> {
        let file = File::open(src).map_err(|e| Error::io(src, e))?;
        unpack_tar(xz_reader(file), dest, &mut Budget::for_archive(src)?)
    }
}

impl Extractor for TarBz2Extractor {
    fn id(&self) -> &str {
        "tar.bz2"
    }
    fn detect(&self, path: &Path, head: &[u8]) -> bool {
        head.starts_with(BZ2_MAGIC) && looks_like_tar(&bz2_inner_head(path))
    }
    fn extract(&self, src: &Path, dest: &Path) -> Result<()> {
        let file = File::open(src).map_err(|e| Error::io(src, e))?;
        unpack_tar(
            bzip2::read::BzDecoder::new(BufReader::new(file)),
            dest,
            &mut Budget::for_archive(src)?,
        )
    }
}

impl Extractor for TarExtractor {
    fn id(&self) -> &str {
        "tar"
    }
    fn detect(&self, _path: &Path, head: &[u8]) -> bool {
        looks_like_tar(head)
    }
    fn extract(&self, src: &Path, dest: &Path) -> Result<()> {
        let file = File::open(src).map_err(|e| Error::io(src, e))?;
        unpack_tar(BufReader::new(file), dest, &mut Budget::for_archive(src)?)
    }
}

impl Extractor for ZipExtractor {
    fn id(&self) -> &str {
        "zip"
    }
    fn detect(&self, _path: &Path, head: &[u8]) -> bool {
        head.starts_with(ZIP_MAGIC)
    }
    fn extract(&self, src: &Path, dest: &Path) -> Result<()> {
        unpack_zip(src, dest, &mut Budget::for_archive(src)?)
    }
}

/// The longest symlink target a zip member may carry; the body is read into
/// memory, and no real path is longer.
const MAX_ZIP_LINK_TARGET: u64 = 4096;

fn unpack_zip(src: &Path, dest: &Path, budget: &mut Budget) -> Result<()> {
    let file = File::open(src).map_err(|e| Error::io(src, e))?;
    let mut zip = zip::ZipArchive::new(BufReader::new(file))
        .map_err(|e| Error::parse(src.display().to_string(), e.to_string()))?;

    let mut deferred_symlinks: Vec<(PathBuf, PathBuf)> = Vec::new();

    for index in 0..zip.len() {
        let mut entry = zip
            .by_index(index)
            .map_err(|e| Error::parse(src.display().to_string(), e.to_string()))?;
        let name = entry.name().to_string();
        // Finder metadata, not payload.
        if name.starts_with("__MACOSX/") || name.ends_with("/.DS_Store") {
            continue;
        }
        let safe = safe_member_path(Path::new(&name))?;
        let out = dest.join(&safe);
        budget.entry()?;

        if entry.is_dir() || name.ends_with('/') {
            walk_inside(dest, &out, true)?;
            continue;
        }
        let mode = entry.unix_mode();
        ensure_parent(dest, &out)?;

        // Zip stores a symlink as a regular member whose body is the target.
        if mode.is_some_and(|m| m & 0o170000 == 0o120000) {
            let mut target = String::new();
            (&mut entry)
                .take(MAX_ZIP_LINK_TARGET + 1)
                .read_to_string(&mut target)
                .map_err(|e| Error::io(&out, e))?;
            if target.len() as u64 > MAX_ZIP_LINK_TARGET {
                return Err(Error::msg(format!(
                    "refusing archive link {} with an over-long target",
                    safe.display()
                )));
            }
            let target = PathBuf::from(target.trim());
            check_link_target(&safe, &target)?;
            deferred_symlinks.push((out, target));
            continue;
        }

        let file = File::create(&out).map_err(|e| Error::io(&out, e))?;
        std::io::copy(&mut entry, &mut budget.writer(file)).map_err(|e| Error::io(&out, e))?;
        // Without this, every binary in a zip lands non-executable.
        if let Some(mode) = mode {
            set_unix_mode(&out, payload_mode(mode))?;
        }
        // A zip written on Windows — Java's `ZipOutputStream`, .NET, Gradle
        // distributions — marks every member 0644 or carries no unix mode
        // at all. Placement only links files it can run, so from the
        // outside that payload looks like it contains nothing. The bytes
        // are the evidence left: a program header says the file is one.
        if is_program_head(&crate::extract::read_head(&out).unwrap_or_default()) {
            #[cfg(unix)]
            crate::platform::unix::ensure_executable(&out)?;
        }
    }
    finish_deferred_links(dest, Vec::new(), deferred_symlinks)
}

impl Extractor for GzFileExtractor {
    fn id(&self) -> &str {
        "gz"
    }
    fn detect(&self, path: &Path, head: &[u8]) -> bool {
        head.starts_with(GZIP_MAGIC) && !looks_like_tar(&gzip_inner_head(path))
    }
    fn extract(&self, src: &Path, dest: &Path) -> Result<()> {
        let file = File::open(src).map_err(|e| Error::io(src, e))?;
        extract_lone_file(
            flate2::read::GzDecoder::new(BufReader::new(file)),
            src,
            dest,
            ".gz",
            &mut Budget::for_archive(src)?,
        )
    }
}

/// A lone compressed file in a release is a program; nothing else is
/// published this way, hence the 0755.
fn extract_lone_file<D: Read>(
    mut decoder: D,
    src: &Path,
    dest: &Path,
    suffix: &str,
    budget: &mut Budget,
) -> Result<()> {
    let stem = src
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "payload".to_string());
    let stem = stem.strip_suffix(suffix).unwrap_or(&stem).to_string();
    let out = dest.join(safe_member_path(Path::new(&stem))?);
    budget.entry()?;
    let written = File::create(&out).map_err(|e| Error::io(&out, e))?;
    std::io::copy(&mut decoder, &mut budget.writer(written)).map_err(|e| Error::io(&out, e))?;
    set_unix_mode(&out, 0o755)
}

impl Extractor for XzFileExtractor {
    fn id(&self) -> &str {
        "xz"
    }
    fn detect(&self, path: &Path, head: &[u8]) -> bool {
        head.starts_with(XZ_MAGIC) && !looks_like_tar(&xz_inner_head(path))
    }
    fn extract(&self, src: &Path, dest: &Path) -> Result<()> {
        let file = File::open(src).map_err(|e| Error::io(src, e))?;
        extract_lone_file(
            xz_reader(file),
            src,
            dest,
            ".xz",
            &mut Budget::for_archive(src)?,
        )
    }
}

impl Extractor for Bz2FileExtractor {
    fn id(&self) -> &str {
        "bz2"
    }
    fn detect(&self, path: &Path, head: &[u8]) -> bool {
        head.starts_with(BZ2_MAGIC) && !looks_like_tar(&bz2_inner_head(path))
    }
    fn extract(&self, src: &Path, dest: &Path) -> Result<()> {
        let file = File::open(src).map_err(|e| Error::io(src, e))?;
        extract_lone_file(
            bzip2::read::BzDecoder::new(BufReader::new(file)),
            src,
            dest,
            ".bz2",
            &mut Budget::for_archive(src)?,
        )
    }
}

impl Extractor for RawBinaryExtractor {
    fn id(&self) -> &str {
        "raw"
    }
    fn detect(&self, _path: &Path, _head: &[u8]) -> bool {
        true
    }
    fn extract(&self, src: &Path, dest: &Path) -> Result<()> {
        let name = src
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| "payload".to_string());
        let out = dest.join(safe_member_path(Path::new(&name))?);
        std::fs::copy(src, &out).map_err(|e| Error::io(&out, e))?;
        set_unix_mode(&out, 0o755)
    }
}

/// True when the first bytes are a Mach-O image, an ELF image, or a `#!` line.
///
/// Used to tell "a program" from "a README that happens to be marked +x".
pub fn is_program_head(head: &[u8]) -> bool {
    if head.starts_with(b"#!") {
        return true;
    }
    // DOS/Windows PE. The next two bytes are the PE offset; the MZ magic is enough
    // to tell a program from a text file published with the same name.
    if head.starts_with(b"MZ") {
        return true;
    }
    if head.starts_with(b"\x7fELF") {
        return true;
    }
    if head.len() < 4 {
        return false;
    }
    let magic = u32::from_be_bytes([head[0], head[1], head[2], head[3]]);
    matches!(
        magic,
        // Mach-O 32/64, both byte orders, plus the fat/universal wrappers.
        0xfeed_face | 0xcefa_edfe | 0xfeed_facf | 0xcffa_edfe | 0xcafe_babe | 0xbebafeca
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    #[cfg(unix)]
    use std::os::unix::fs::PermissionsExt;

    #[cfg(unix)]
    fn symlink_entry(builder: &mut tar::Builder<Vec<u8>>, name: &str, target: &str) {
        let mut header = tar::Header::new_gnu();
        header.set_entry_type(tar::EntryType::Symlink);
        header.set_size(0);
        header.set_mode(0o777);
        builder.append_link(&mut header, name, target).unwrap();
    }

    /// The escape this guards against is not hypothetical: every member name
    /// below passes `safe_member_path` and `check_link_target`, because both
    /// reason about the name while the kernel resolves the link.
    #[cfg(unix)]
    #[test]
    fn a_planted_symlink_is_never_written_through() {
        let mut builder = tar::Builder::new(Vec::new());
        // 1. A link back to the payload root. Lexical depth 4, four `..` land
        //    on 0, so the target guard accepts it.
        symlink_entry(&mut builder, "a/b/c/d/link", "../../../..");
        // 2. A link named *through* member 1, so its real depth is four rather
        //    than the nine its name claims — and the surplus `..` escapes.
        symlink_entry(
            &mut builder,
            "a/b/c/d/link/e/f/g/h/esc",
            "../../../../../../../../..",
        );
        // 3. The payload, written through member 2.
        let mut header = tar::Header::new_gnu();
        header.set_entry_type(tar::EntryType::Regular);
        header.set_size(5);
        header.set_mode(0o644);
        builder
            .append_data(
                &mut header,
                "a/b/c/d/link/e/f/g/h/esc/victim",
                &b"pwned"[..],
            )
            .unwrap();
        let archive = builder.into_inner().unwrap();

        let outer = tempfile::tempdir().unwrap();
        let dest = outer.path().join("dest");
        std::fs::create_dir_all(&dest).unwrap();

        assert!(
            unpack_tar(&archive[..], &dest, &mut unlimited()).is_err(),
            "an entry named through a planted symlink must be refused"
        );
        // Member 2 would have been created here had the link been followed.
        assert!(!dest.join("e").exists(), "the symlink was resolved anyway");
    }

    /// Each target passes the lexical check alone; through `L -> .` they
    /// all resolve above the payload.
    #[cfg(unix)]
    #[test]
    fn a_link_routed_through_an_earlier_link_is_refused() {
        for (name, target) in [("N", "L/.."), ("N", "L/L/../.."), ("sub/N", "../L/L/../..")] {
            let mut builder = tar::Builder::new(Vec::new());
            symlink_entry(&mut builder, "L", ".");
            symlink_entry(&mut builder, name, target);
            let archive = builder.into_inner().unwrap();

            let outer = tempfile::tempdir().unwrap();
            let dest = outer.path().join("dest");
            std::fs::create_dir_all(&dest).unwrap();
            let err = unpack_tar(&archive[..], &dest, &mut unlimited()).unwrap_err();
            assert!(
                err.to_string().contains("resolves outside"),
                "{target}: {err}"
            );
        }
    }

    #[cfg(unix)]
    #[test]
    fn resolves_inside_follows_every_link_and_judges_dangling_ones_by_their_target() {
        let outer = tempfile::tempdir().unwrap();
        let root = outer.path().join("root");
        std::fs::create_dir_all(root.join("bin")).unwrap();
        std::fs::write(root.join("bin/tool"), b"x").unwrap();
        let link = |name: &str, target: &str| {
            std::os::unix::fs::symlink(target, root.join(name)).unwrap();
            root.join(name)
        };

        assert!(crate::extract::resolves_inside(
            &link("alias", "bin/tool"),
            &root
        ));
        assert!(crate::extract::resolves_inside(
            &link("dangling", "bin/missing"),
            &root
        ));
        link("self", ".");
        assert!(crate::extract::resolves_inside(
            &root.join("self/self/bin/tool"),
            &root
        ));
        assert!(!crate::extract::resolves_inside(
            &link("up", "self/.."),
            &root
        ));
        assert!(!crate::extract::resolves_inside(
            &link("gone", "self/../nothing"),
            &root
        ));
        assert!(!crate::extract::resolves_inside(
            &link("abs", "/etc/passwd"),
            &root
        ));
        link("loop-a", "loop-b");
        assert!(!crate::extract::resolves_inside(
            &link("loop-b", "loop-a"),
            &root
        ));
    }

    #[test]
    fn materialize_symlink_as_copy_writes_the_target_file() {
        let dest = tempfile::tempdir().unwrap();
        let target = dest.path().join("tool.exe");
        std::fs::write(&target, b"MZ").unwrap();
        let link = dest.path().join("tool-alias");
        materialize_symlink_as_copy(Path::new("tool.exe"), &link).unwrap();
        assert_eq!(std::fs::read(&link).unwrap(), b"MZ");
        assert!(!link.is_symlink());
    }

    #[test]
    fn materialize_symlink_as_copy_errors_when_target_is_missing() {
        let dest = tempfile::tempdir().unwrap();
        let link = dest.path().join("tool-alias");
        let err = materialize_symlink_as_copy(Path::new("missing"), &link).unwrap_err();
        assert!(err.to_string().contains("cannot materialise"), "{err}");
        assert!(std::fs::symlink_metadata(&link).is_err());
    }

    /// A directory link to its own ancestor would be copied into itself while
    /// the copy walks it: endless recursion, or a full disk.
    #[test]
    fn materialize_symlink_as_copy_refuses_a_link_to_its_own_ancestor() {
        let dest = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dest.path().join("sub")).unwrap();
        std::fs::write(dest.path().join("sub/file"), b"x").unwrap();

        for (link, target) in [
            ("d", "."),
            ("sub/d", ".."),
            ("sub/d", "."),
            ("sub/d", "../sub"),
        ] {
            let link = dest.path().join(link);
            let err = materialize_symlink_as_copy(Path::new(target), &link).unwrap_err();
            assert!(err.to_string().contains("ancestor"), "{target}: {err}");
            assert!(
                std::fs::symlink_metadata(&link).is_err(),
                "{target}: nothing written"
            );
        }
    }

    #[test]
    fn materialize_symlink_as_copy_writes_a_directory_tree() {
        let dest = tempfile::tempdir().unwrap();
        let docs = dest.path().join("docs");
        std::fs::create_dir_all(docs.join("nested")).unwrap();
        std::fs::write(docs.join("readme.txt"), b"hi").unwrap();
        std::fs::write(docs.join("nested/more.txt"), b"there").unwrap();
        let link = dest.path().join("share-doc");
        materialize_symlink_as_copy(Path::new("docs"), &link).unwrap();
        assert!(link.is_dir());
        assert!(!link.is_symlink());
        assert_eq!(std::fs::read(link.join("readme.txt")).unwrap(), b"hi");
        assert_eq!(
            std::fs::read(link.join("nested/more.txt")).unwrap(),
            b"there"
        );
    }

    /// Archives often list `bin/tool -> tool` before the real file. Deferring
    /// the link until unpack finishes is what makes Windows copy-materialisation
    /// (and late hard links) succeed.
    #[cfg(unix)]
    #[test]
    fn symlink_listed_before_its_target_still_materialises() {
        let mut builder = tar::Builder::new(Vec::new());
        symlink_entry(&mut builder, "bin/tool-alias", "tool");
        let mut header = tar::Header::new_gnu();
        header.set_entry_type(tar::EntryType::Regular);
        header.set_size(2);
        header.set_mode(0o755);
        builder
            .append_data(&mut header, "bin/tool", &b"hi"[..])
            .unwrap();
        let archive = builder.into_inner().unwrap();

        let dest = tempfile::tempdir().unwrap();
        unpack_tar(&archive[..], dest.path(), &mut unlimited()).unwrap();
        let alias = dest.path().join("bin/tool-alias");
        assert!(alias.symlink_metadata().unwrap().file_type().is_symlink());
        assert_eq!(std::fs::read(&alias).unwrap(), b"hi");
    }

    #[test]
    fn finish_deferred_links_materialises_after_target_exists() {
        let dest = tempfile::tempdir().unwrap();
        let link = dest.path().join("tool-alias");
        std::fs::write(dest.path().join("tool.exe"), b"MZ").unwrap();
        finish_deferred_links(
            dest.path(),
            Vec::new(),
            vec![(link.clone(), PathBuf::from("tool.exe"))],
        )
        .unwrap();
        assert_eq!(std::fs::read(&link).unwrap(), b"MZ");
    }

    #[test]
    fn write_hardlink_falls_back_to_copy_when_link_fails() {
        let dest = tempfile::tempdir().unwrap();
        let source = dest.path().join("tool.exe");
        std::fs::write(&source, b"MZ").unwrap();
        // An existing destination makes hard_link fail on every host; copy must
        // still replace the stale bytes (Windows extracts that refuse hard links).
        let out = dest.path().join("tool-alias");
        std::fs::write(&out, b"stale").unwrap();
        write_hardlink(&source, &out).unwrap();
        assert_eq!(std::fs::read(&out).unwrap(), b"MZ");
    }

    #[cfg(unix)]
    #[test]
    fn ordinary_symlinks_inside_the_payload_still_work() {
        let mut builder = tar::Builder::new(Vec::new());
        let mut header = tar::Header::new_gnu();
        header.set_entry_type(tar::EntryType::Regular);
        header.set_size(2);
        header.set_mode(0o755);
        builder
            .append_data(&mut header, "bin/tool", &b"hi"[..])
            .unwrap();
        symlink_entry(&mut builder, "bin/tool-alias", "tool");
        let archive = builder.into_inner().unwrap();

        let dest = tempfile::tempdir().unwrap();
        unpack_tar(&archive[..], dest.path(), &mut unlimited()).unwrap();
        let alias = dest.path().join("bin/tool-alias");
        assert!(alias.symlink_metadata().unwrap().file_type().is_symlink());
        assert_eq!(std::fs::read(&alias).unwrap(), b"hi");
    }

    fn tar_gz_with(entries: &[(&str, &[u8], u32)]) -> Vec<u8> {
        let mut builder = tar::Builder::new(Vec::new());
        for (name, body, mode) in entries {
            let mut header = tar::Header::new_gnu();
            header.set_size(body.len() as u64);
            header.set_mode(*mode);
            header.set_cksum();
            builder.append_data(&mut header, name, *body).unwrap();
        }
        let plain = builder.into_inner().unwrap();
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
        encoder.write_all(&plain).unwrap();
        encoder.finish().unwrap()
    }

    #[cfg(unix)]
    #[test]
    fn extracts_a_tar_gz_and_keeps_the_executable_bit() {
        let dir = tempfile::tempdir().unwrap();
        let src = dir.path().join("t.tar.gz");
        std::fs::write(&src, tar_gz_with(&[("rg-1/rg", b"#!/bin/sh\n", 0o755)])).unwrap();
        let dest = dir.path().join("out");
        std::fs::create_dir_all(&dest).unwrap();

        TarGzExtractor.extract(&src, &dest).unwrap();
        let binary = dest.join("rg-1/rg");
        assert!(binary.is_file());
        assert_ne!(
            std::fs::metadata(&binary).unwrap().permissions().mode() & 0o111,
            0
        );
    }

    #[cfg(unix)]
    #[test]
    fn bzip2_archives_use_the_same_traversal_guard_as_tar() {
        let mut builder = tar::Builder::new(Vec::new());
        symlink_entry(&mut builder, "a/b/c/d/link", "../../../..");
        symlink_entry(
            &mut builder,
            "a/b/c/d/link/e/f/g/h/esc",
            "../../../../../../../../..",
        );
        let mut header = tar::Header::new_gnu();
        header.set_size(5);
        header.set_mode(0o644);
        builder
            .append_data(
                &mut header,
                "a/b/c/d/link/e/f/g/h/esc/victim",
                &b"pwned"[..],
            )
            .unwrap();
        let plain = builder.into_inner().unwrap();
        let mut encoder = bzip2::write::BzEncoder::new(Vec::new(), bzip2::Compression::fast());
        encoder.write_all(&plain).unwrap();
        let archive = encoder.finish().unwrap();

        let outer = tempfile::tempdir().unwrap();
        let src = outer.path().join("payload.tar.bz2");
        std::fs::write(&src, archive).unwrap();
        let dest = outer.path().join("dest");
        std::fs::create_dir_all(&dest).unwrap();

        assert!(TarBz2Extractor.extract(&src, &dest).is_err());
        assert!(!outer.path().join("e").exists());
    }

    #[test]
    fn detection_separates_tar_gz_from_a_lone_gz() {
        let dir = tempfile::tempdir().unwrap();

        let tarball = dir.path().join("a.gz");
        std::fs::write(&tarball, tar_gz_with(&[("x", b"y", 0o644)])).unwrap();
        let head = crate::extract::read_head(&tarball).unwrap();
        assert!(TarGzExtractor.detect(&tarball, &head));
        assert!(!GzFileExtractor.detect(&tarball, &head));

        let plain = dir.path().join("jq.gz");
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
        encoder.write_all(b"\x7fELF and then some payload").unwrap();
        std::fs::write(&plain, encoder.finish().unwrap()).unwrap();
        let head = crate::extract::read_head(&plain).unwrap();
        assert!(!TarGzExtractor.detect(&plain, &head));
        assert!(GzFileExtractor.detect(&plain, &head));
    }

    fn xz_bytes(plain: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        lzma_rs::xz_compress(&mut &plain[..], &mut out).unwrap();
        out
    }

    fn tar_xz_with(entries: &[(&str, &[u8], u32)]) -> Vec<u8> {
        let mut builder = tar::Builder::new(Vec::new());
        for (name, body, mode) in entries {
            let mut header = tar::Header::new_gnu();
            header.set_size(body.len() as u64);
            header.set_mode(*mode);
            header.set_cksum();
            builder.append_data(&mut header, name, *body).unwrap();
        }
        xz_bytes(&builder.into_inner().unwrap())
    }

    #[test]
    fn detection_separates_tar_xz_from_a_lone_xz() {
        let dir = tempfile::tempdir().unwrap();

        let tarball = dir.path().join("a.xz");
        std::fs::write(&tarball, tar_xz_with(&[("x", b"y", 0o644)])).unwrap();
        let head = crate::extract::read_head(&tarball).unwrap();
        assert!(TarXzExtractor.detect(&tarball, &head));
        assert!(!XzFileExtractor.detect(&tarball, &head));

        let plain = dir.path().join("jq.xz");
        std::fs::write(&plain, xz_bytes(b"\x7fELF and then some payload")).unwrap();
        let head = crate::extract::read_head(&plain).unwrap();
        assert!(!TarXzExtractor.detect(&plain, &head));
        assert!(XzFileExtractor.detect(&plain, &head));
    }

    #[test]
    fn a_lone_xz_file_is_extracted_as_the_program() {
        let dir = tempfile::tempdir().unwrap();
        let src = dir.path().join("jq.xz");
        std::fs::write(&src, xz_bytes(b"\x7fELF payload")).unwrap();
        let dest = dir.path().join("out");
        std::fs::create_dir_all(&dest).unwrap();
        XzFileExtractor.extract(&src, &dest).unwrap();
        assert_eq!(std::fs::read(dest.join("jq")).unwrap(), b"\x7fELF payload");
    }

    fn bz2_bytes(plain: &[u8]) -> Vec<u8> {
        let mut encoder = bzip2::write::BzEncoder::new(Vec::new(), bzip2::Compression::fast());
        encoder.write_all(plain).unwrap();
        encoder.finish().unwrap()
    }

    #[test]
    fn detection_separates_tar_bz2_from_a_lone_bz2() {
        let dir = tempfile::tempdir().unwrap();
        let mut builder = tar::Builder::new(Vec::new());
        let mut header = tar::Header::new_gnu();
        header.set_size(1);
        header.set_mode(0o644);
        header.set_cksum();
        builder
            .append_data(&mut header, "x", b"y".as_slice())
            .unwrap();
        let tarball = dir.path().join("a.bz2");
        std::fs::write(&tarball, bz2_bytes(&builder.into_inner().unwrap())).unwrap();
        let head = crate::extract::read_head(&tarball).unwrap();
        assert!(TarBz2Extractor.detect(&tarball, &head));
        assert!(!Bz2FileExtractor.detect(&tarball, &head));

        let plain = dir.path().join("jq.bz2");
        std::fs::write(&plain, bz2_bytes(b"\x7fELF payload")).unwrap();
        let head = crate::extract::read_head(&plain).unwrap();
        assert!(!TarBz2Extractor.detect(&plain, &head));
        assert!(Bz2FileExtractor.detect(&plain, &head));
    }

    #[test]
    fn refuses_links_that_escape_the_payload() {
        assert!(check_link_target(Path::new("bin/tool"), Path::new("../lib/x.dylib")).is_ok());
        assert!(check_link_target(Path::new("bin/tool"), Path::new("../../../.ssh/id")).is_err());
        assert!(check_link_target(Path::new("tool"), Path::new("/etc/passwd")).is_err());
        assert!(check_link_target(Path::new("tool"), Path::new("../outside")).is_err());
    }

    /// A zip the way Windows tools write one: every member 0644, with no
    /// execute bit anywhere. Gradle and Maven `-bin.zip` distributions are
    /// these.
    #[cfg(unix)]
    fn write_zip_with_mode(path: &std::path::Path, members: &[(&str, &[u8], u32)]) {
        let file = std::fs::File::create(path).unwrap();
        let mut writer = zip::ZipWriter::new(file);
        for (name, body, mode) in members {
            let options: zip::write::SimpleFileOptions =
                zip::write::SimpleFileOptions::default().unix_permissions(*mode);
            writer.start_file(*name, options).unwrap();
            writer.write_all(body).unwrap();
        }
        writer.finish().unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn a_program_in_a_zip_without_an_execute_bit_still_comes_out_runnable() {
        let tmp = tempfile::tempdir().unwrap();
        let archive = tmp.path().join("payload.zip");
        write_zip_with_mode(
            &archive,
            &[
                ("bin/tool", b"#!/bin/sh\necho hi\n", 0o644),
                ("README.md", b"# hello\n", 0o644),
            ],
        );

        let dest = tmp.path().join("out");
        std::fs::create_dir_all(&dest).unwrap();
        ZipExtractor.extract(&archive, &dest).unwrap();

        let mode = std::fs::metadata(dest.join("bin/tool"))
            .unwrap()
            .permissions()
            .mode()
            & 0o7777;
        assert_eq!(mode, 0o744, "placement only links a file it can run");
        // A file that is not a program keeps the mode the archive gave it.
        let mode = std::fs::metadata(dest.join("README.md"))
            .unwrap()
            .permissions()
            .mode()
            & 0o7777;
        assert_eq!(mode, 0o644);
    }

    #[cfg(unix)]
    fn tar_gz_with_dir(name: &str, mode: u32) -> Vec<u8> {
        let mut builder = tar::Builder::new(Vec::new());
        let mut header = tar::Header::new_gnu();
        header.set_entry_type(tar::EntryType::Directory);
        header.set_size(0);
        header.set_mode(mode);
        header.set_cksum();
        builder
            .append_data(&mut header, name, &[] as &[u8])
            .unwrap();
        let plain = builder.into_inner().unwrap();
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
        encoder.write_all(&plain).unwrap();
        encoder.finish().unwrap()
    }

    #[cfg(unix)]
    #[test]
    fn tar_directory_members_keep_mode() {
        let dir = tempfile::tempdir().unwrap();
        let src = dir.path().join("t.tar.gz");
        std::fs::write(&src, tar_gz_with_dir("private/", 0o700)).unwrap();
        let dest = dir.path().join("out");
        std::fs::create_dir_all(&dest).unwrap();

        TarGzExtractor.extract(&src, &dest).unwrap();
        let private = dest.join("private");
        assert!(private.is_dir());
        let mode = std::fs::metadata(&private).unwrap().permissions().mode() & 0o7777;
        assert_eq!(mode, 0o700);
    }

    #[cfg(unix)]
    fn tar_with_read_only_dir(dir_first: bool) -> Vec<u8> {
        let mut builder = tar::Builder::new(Vec::new());
        let dir = |builder: &mut tar::Builder<Vec<u8>>| {
            let mut header = tar::Header::new_gnu();
            header.set_entry_type(tar::EntryType::Directory);
            header.set_size(0);
            header.set_mode(0o555);
            header.set_cksum();
            builder
                .append_data(&mut header, "ro/", &[] as &[u8])
                .unwrap();
        };
        if dir_first {
            dir(&mut builder);
        }
        let mut header = tar::Header::new_gnu();
        header.set_size(2);
        header.set_mode(0o644);
        header.set_cksum();
        builder
            .append_data(&mut header, "ro/file", &b"hi"[..])
            .unwrap();
        if !dir_first {
            dir(&mut builder);
        }
        builder.into_inner().unwrap()
    }

    /// Nix and some reproducible tarballs mark directories 0555. Applied as
    /// written, the mode either stops the children from being written or
    /// leaves a tree nothing can remove.
    #[cfg(unix)]
    #[test]
    fn a_read_only_directory_extracts_and_stays_removable_in_either_order() {
        for dir_first in [true, false] {
            let outer = tempfile::tempdir().unwrap();
            let dest = outer.path().join("dest");
            std::fs::create_dir_all(&dest).unwrap();

            unpack_tar(
                &tar_with_read_only_dir(dir_first)[..],
                &dest,
                &mut unlimited(),
            )
            .unwrap_or_else(|e| panic!("dir_first={dir_first}: {e}"));
            assert_eq!(std::fs::read(dest.join("ro/file")).unwrap(), b"hi");
            let mode = std::fs::metadata(dest.join("ro"))
                .unwrap()
                .permissions()
                .mode()
                & 0o777;
            assert_eq!(mode, 0o755, "dir_first={dir_first}: owner always gets rwx");
            std::fs::remove_dir_all(&dest).unwrap_or_else(|e| panic!("dir_first={dir_first}: {e}"));
        }
    }

    #[cfg(unix)]
    #[test]
    fn setuid_bits_from_an_archive_are_dropped() {
        let dir = tempfile::tempdir().unwrap();
        let src = dir.path().join("t.tar.gz");
        std::fs::write(&src, tar_gz_with(&[("tool", b"#!/bin/sh\n", 0o4755)])).unwrap();
        let dest = dir.path().join("out");
        std::fs::create_dir_all(&dest).unwrap();
        TarGzExtractor.extract(&src, &dest).unwrap();
        let mode = std::fs::metadata(dest.join("tool"))
            .unwrap()
            .permissions()
            .mode()
            & 0o7777;
        assert_eq!(mode, 0o755);
    }

    // Only the Unix-only tar tests use it; Windows would flag it as dead code.
    #[cfg(unix)]
    fn unlimited() -> Budget {
        Budget::new(u64::MAX, u64::MAX)
    }

    fn gz_bytes(plain: &[u8]) -> Vec<u8> {
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
        encoder.write_all(plain).unwrap();
        encoder.finish().unwrap()
    }

    #[test]
    fn a_lone_gzip_bomb_stops_at_the_budget() {
        let dir = tempfile::tempdir().unwrap();
        let src = dir.path().join("tool.gz");
        std::fs::write(&src, gz_bytes(&vec![0u8; 4 << 20])).unwrap();
        let dest = dir.path().join("out");
        std::fs::create_dir_all(&dest).unwrap();

        let file = File::open(&src).unwrap();
        let decoder = flate2::read::GzDecoder::new(BufReader::new(file));
        let err = extract_lone_file(decoder, &src, &dest, ".gz", &mut Budget::new(1 << 20, 10))
            .unwrap_err();
        assert!(err.to_string().contains("unpacks to more than"), "{err}");
        let written = std::fs::metadata(dest.join("tool")).map_or(0, |m| m.len());
        assert!(written <= 1 << 20, "wrote {written} bytes past the budget");
    }

    #[test]
    fn a_tar_member_larger_than_the_budget_is_refused_before_it_is_written() {
        let mut builder = tar::Builder::new(Vec::new());
        let body = vec![0u8; 2 << 20];
        let mut header = tar::Header::new_gnu();
        header.set_size(body.len() as u64);
        header.set_mode(0o644);
        header.set_cksum();
        builder.append_data(&mut header, "big", &body[..]).unwrap();
        let archive = builder.into_inner().unwrap();

        let dest = tempfile::tempdir().unwrap();
        let err = unpack_tar(&archive[..], dest.path(), &mut Budget::new(1 << 20, 10)).unwrap_err();
        assert!(err.to_string().contains("unpacks to more than"), "{err}");
        assert!(!dest.path().join("big").exists());
    }

    #[test]
    fn a_tar_with_too_many_members_is_refused() {
        let mut builder = tar::Builder::new(Vec::new());
        for i in 0..20 {
            let mut header = tar::Header::new_gnu();
            header.set_size(0);
            header.set_mode(0o644);
            header.set_cksum();
            builder
                .append_data(&mut header, format!("f{i}"), &[] as &[u8])
                .unwrap();
        }
        let archive = builder.into_inner().unwrap();

        let dest = tempfile::tempdir().unwrap();
        let err =
            unpack_tar(&archive[..], dest.path(), &mut Budget::new(u64::MAX, 10)).unwrap_err();
        assert!(err.to_string().contains("more than 10 entries"), "{err}");
    }

    #[test]
    fn a_zip_with_too_many_members_is_refused() {
        let dir = tempfile::tempdir().unwrap();
        let src = dir.path().join("many.zip");
        let mut writer = zip::ZipWriter::new(File::create(&src).unwrap());
        for i in 0..20 {
            writer
                .start_file(format!("f{i}"), zip::write::SimpleFileOptions::default())
                .unwrap();
        }
        writer.finish().unwrap();
        let dest = dir.path().join("out");
        std::fs::create_dir_all(&dest).unwrap();

        let err = unpack_zip(&src, &dest, &mut Budget::new(u64::MAX, 10)).unwrap_err();
        assert!(err.to_string().contains("more than 10 entries"), "{err}");
    }

    #[test]
    fn a_zip_bomb_stops_at_the_budget() {
        let dir = tempfile::tempdir().unwrap();
        let src = dir.path().join("bomb.zip");
        let mut writer = zip::ZipWriter::new(File::create(&src).unwrap());
        writer
            .start_file("zeros", zip::write::SimpleFileOptions::default())
            .unwrap();
        writer.write_all(&vec![0u8; 4 << 20]).unwrap();
        writer.finish().unwrap();
        let dest = dir.path().join("out");
        std::fs::create_dir_all(&dest).unwrap();

        let err = unpack_zip(&src, &dest, &mut Budget::new(1 << 20, 10)).unwrap_err();
        assert!(err.to_string().contains("unpacks to more than"), "{err}");
        let written = std::fs::metadata(dest.join("zeros")).map_or(0, |m| m.len());
        assert!(written <= 1 << 20, "wrote {written} bytes past the budget");
    }

    #[test]
    fn the_budget_scales_with_the_archive_between_a_floor_and_a_ceiling() {
        let tiny = Budget::for_compressed_len(1);
        let mid = Budget::for_compressed_len(1 << 30);
        let huge = Budget::for_compressed_len(u64::MAX);
        assert_eq!(tiny.limit_bytes, 256 << 20);
        assert_eq!(mid.limit_bytes, 32 << 30);
        assert_eq!(Budget::for_compressed_len(10 << 20).limit_bytes, 1000 << 20);
        assert_eq!(huge.limit_bytes, 32 << 30);
    }

    fn macho_match_32bit_only(head: &[u8]) -> bool {
        head.len() >= 4
            && matches!(
                u32::from_be_bytes([head[0], head[1], head[2], head[3]]),
                0xfeed_face | 0xcefa_edfe
            )
    }

    #[test]
    fn recognises_program_headers() {
        assert!(is_program_head(b"#!/bin/sh"));
        assert!(is_program_head(b"MZ\x90\x00"));
        // On-disk Mach-O prefixes: the byte order in each label is the order
        // written in the file, not the spelling of the u32 constant.
        const MACHO_ON_DISK: &[(&str, [u8; 4])] = &[
            ("Mach-O 64 BE", [0xfe, 0xed, 0xfa, 0xcf]),
            ("Mach-O 64 LE", [0xcf, 0xfa, 0xed, 0xfe]),
            ("Mach-O 32 BE", [0xfe, 0xed, 0xfa, 0xce]),
            ("Mach-O 32 LE", [0xce, 0xfa, 0xed, 0xfe]),
        ];
        for (label, prefix) in MACHO_ON_DISK {
            let mut head = prefix.to_vec();
            head.extend([0, 0]);
            assert!(is_program_head(&head), "{label}");
            if label.contains("64") {
                assert!(
                    !macho_match_32bit_only(&head),
                    "{label} must not be covered by 32-bit Mach-O detection alone"
                );
            }
        }
        // Universal/fat headers use their own on-disk prefixes too.
        assert!(is_program_head(&[0xca, 0xfe, 0xba, 0xbe, 0, 0]));
        assert!(is_program_head(&[0xbe, 0xba, 0xfe, 0xca, 0, 0]));
        assert!(!macho_match_32bit_only(&[0xca, 0xfe, 0xba, 0xbe, 0, 0]));
        assert!(is_program_head(b"\x7fELF\x02"));
        assert!(!is_program_head(b"# Readme\n"));
        assert!(!is_program_head(b"plain text, not a program\n"));
        assert!(!is_program_head(b""));
        // A near-miss must not be treated as Mach-O just because it shares a prefix.
        assert!(!is_program_head(&[0xfe, 0xed, 0xfa, 0xcd, 0, 0]));
    }
}
