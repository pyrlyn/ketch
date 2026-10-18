// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Turning a downloaded file into a directory of files.
//!
//! Extractors are selected by sniffing content, not by trusting the file name:
//! release assets are routinely named `.tar.gz` while being a plain binary, or
//! `.zip` while being a tarball.

pub mod archive;

#[cfg(target_os = "macos")]
pub mod macos;

use crate::error::{Error, Result};
use std::fs::File;
use std::io::{Read, Write};
use std::path::{Component, Path, PathBuf};

/// One archive format.
pub trait Extractor: Send + Sync {
    /// Stable identifier used in logs and `--verbose` output.
    fn id(&self) -> &str;

    /// Can this extractor handle the file? `head` is the first 512 bytes,
    /// already read, so implementations do not each re-open the file.
    fn detect(&self, path: &Path, head: &[u8]) -> bool;

    /// Unpack `src` into the existing directory `dest`.
    fn extract(&self, src: &Path, dest: &Path) -> Result<()>;
}

/// Pick an extractor and run it. Returns the id of the one that ran, and
/// traces the choice on `report`.
pub fn extract_auto(
    src: &Path,
    dest: &Path,
    extractors: &[Box<dyn Extractor>],
    report: &crate::report::Report,
) -> Result<String> {
    let head = read_head(src)?;
    std::fs::create_dir_all(dest).map_err(|e| Error::io(dest, e))?;
    for extractor in extractors {
        if extractor.detect(src, &head) {
            report.debug(&format!(
                "extracting {} with `{}`",
                src.display(),
                extractor.id()
            ));
            extractor.extract(src, dest)?;
            return Ok(extractor.id().to_string());
        }
    }
    Err(Error::UnsupportedArchive(src.to_path_buf()))
}

/// How far a compressed archive may expand relative to its own size.
///
/// Real release archives sit well below 20:1; a bomb sits at 1000:1 and above.
const MAX_EXPANSION_RATIO: u64 = 100;

/// Every archive may unpack at least this much, so a small archive of
/// highly compressible files (zero-padded firmware, sparse data) still works.
const MIN_UNPACKED_BYTES: u64 = 256 * 1024 * 1024;

/// No archive unpacks past this, whatever its size.
const MAX_UNPACKED_BYTES: u64 = 32 * 1024 * 1024 * 1024;

/// No archive writes more members than this. The largest toolchains ship tens
/// of thousands of files; millions of empty members exist only to exhaust
/// inodes.
const MAX_ENTRIES: u64 = 250_000;

/// What one extraction may still write: bytes and entries.
///
/// Archive headers and compressed streams are written by someone else, so the
/// size an archive expands to is untrusted input. One budget per extraction,
/// charged by every extractor, keeps a small bomb from filling the disk under
/// the ketch root.
#[derive(Debug)]
pub struct Budget {
    bytes_left: u64,
    entries_left: u64,
    limit_bytes: u64,
    limit_entries: u64,
}

impl Budget {
    /// An explicit budget; tests use small ones.
    pub fn new(limit_bytes: u64, limit_entries: u64) -> Self {
        Budget {
            bytes_left: limit_bytes,
            entries_left: limit_entries,
            limit_bytes,
            limit_entries,
        }
    }

    /// The budget for an archive of `compressed` bytes: a ratio of its size,
    /// never below the floor and never above the ceiling.
    pub fn for_compressed_len(compressed: u64) -> Self {
        let bytes = compressed
            .saturating_mul(MAX_EXPANSION_RATIO)
            .clamp(MIN_UNPACKED_BYTES, MAX_UNPACKED_BYTES);
        Budget::new(bytes, MAX_ENTRIES)
    }

    /// The budget for the archive at `src`.
    pub fn for_archive(src: &Path) -> Result<Self> {
        let len = std::fs::metadata(src).map_err(|e| Error::io(src, e))?.len();
        Ok(Budget::for_compressed_len(len))
    }

    /// Charge one member (file, directory or link).
    pub fn entry(&mut self) -> Result<()> {
        if self.entries_left == 0 {
            return Err(Error::msg(format!(
                "refusing archive with more than {} entries",
                self.limit_entries
            )));
        }
        self.entries_left -= 1;
        Ok(())
    }

    /// Charge `n` bytes about to be written.
    pub fn bytes(&mut self, n: u64) -> Result<()> {
        if n > self.bytes_left {
            return Err(self.too_large());
        }
        self.bytes_left -= n;
        Ok(())
    }

    fn too_large(&self) -> Error {
        Error::msg(format!(
            "refusing archive that unpacks to more than {}",
            crate::text::bytes(self.limit_bytes)
        ))
    }

    /// Wrap `inner` so every byte written through it is charged here.
    pub fn writer<W: Write>(&mut self, inner: W) -> BudgetWriter<'_, W> {
        BudgetWriter {
            budget: self,
            inner,
        }
    }
}

/// A writer that fails once its [`Budget`] runs out, before writing the
/// bytes that would overrun it.
pub struct BudgetWriter<'a, W> {
    budget: &'a mut Budget,
    inner: W,
}

impl<W: Write> Write for BudgetWriter<'_, W> {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        if buf.len() as u64 > self.budget.bytes_left {
            return Err(std::io::Error::other(self.budget.too_large().to_string()));
        }
        let n = self.inner.write(buf)?;
        self.budget.bytes_left -= n as u64;
        Ok(n)
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.inner.flush()
    }
}

/// First 512 bytes of a file, or fewer if it is shorter.
pub fn read_head(path: &Path) -> Result<Vec<u8>> {
    let mut file = File::open(path).map_err(|e| Error::io(path, e))?;
    let mut buffer = vec![0u8; 512];
    let mut filled = 0;
    while filled < buffer.len() {
        match file.read(&mut buffer[filled..]) {
            Ok(0) => break,
            Ok(n) => filled += n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(Error::io(path, e)),
        }
    }
    buffer.truncate(filled);
    Ok(buffer)
}

/// Reject archive member paths that would write outside the destination.
///
/// This is the guard against "zip slip" / tar traversal: a member named
/// `../../../.zshrc` must never be honoured, no matter who published it.
/// Absolute paths and `..` components are refused outright; the result is
/// always a relative path safe to join onto the destination. On Windows a
/// component that names a device or loses a trailing dot or space is refused
/// too.
pub fn safe_member_path(raw: &Path) -> Result<PathBuf> {
    safe_member_path_for(raw, cfg!(windows))
}

/// [`safe_member_path`] with the Windows rules switched on or off, so both are
/// tested on every host.
fn safe_member_path_for(raw: &Path, windows: bool) -> Result<PathBuf> {
    let text = raw.to_string_lossy();
    // Check the raw string before `components()`: on Windows a backslash is a
    // separator, so `a\b` would otherwise become the ordinary relative path
    // `a/b` and slip through. Archive member names must never carry one.
    if text.contains('\\') {
        return Err(Error::msg(format!(
            "refusing archive entry with unsafe name: {}",
            raw.display()
        )));
    }
    // `std::path` on Unix treats `C:/Windows` as relative normals; typed-path
    // still sees a Windows prefix/root so a foreign absolute cannot slip in.
    let win = typed_path::Utf8WindowsPath::new(text.as_ref());
    if win.has_root()
        || win.components().any(|c| {
            matches!(
                c,
                typed_path::Utf8WindowsComponent::ParentDir
                    | typed_path::Utf8WindowsComponent::Prefix(_)
            )
        })
    {
        return Err(Error::msg(format!(
            "refusing archive entry that escapes the target directory: {}",
            raw.display()
        )));
    }
    let mut safe = PathBuf::new();
    for component in raw.components() {
        match component {
            Component::Normal(part) => {
                let part_text = part.to_string_lossy();
                // NTFS stream / drive-relative crumbs that are not a full root.
                if part_text.contains(':')
                    || part_text.contains('\\')
                    || (windows && crate::config::windows_unusable_component(&part_text))
                {
                    return Err(Error::msg(format!(
                        "refusing archive entry with unsafe name: {}",
                        raw.display()
                    )));
                }
                safe.push(part);
            }
            Component::CurDir => {}
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                return Err(Error::msg(format!(
                    "refusing archive entry that escapes the target directory: {}",
                    raw.display()
                )));
            }
        }
    }
    if safe.as_os_str().is_empty() {
        return Err(Error::msg("refusing archive entry with an empty name"));
    }
    Ok(safe)
}

/// More links than this on one path is a loop, as the kernel decides too.
const MAX_LINK_HOPS: u32 = 40;

/// True when `path`, with every symlink along it and at its end followed,
/// stays under `root`.
///
/// A link target checked on its own text cannot answer this: `L -> .` makes
/// `N -> L/..` look one level deep while it resolves to the parent of the
/// payload. Links are followed one component at a time rather than through
/// `canonicalize`, so a dangling link is judged by where it would point, not
/// waved through because it does not resolve yet. Anything that cannot be
/// read, or loops, counts as outside.
pub(crate) fn resolves_inside(path: &Path, root: &Path) -> bool {
    let mut hops = 0;
    match (resolve(root, &mut hops), resolve(path, &mut hops)) {
        (Some(root), Some(path)) => path.starts_with(root),
        _ => false,
    }
}

/// `path` with every existing symlink on it followed and `..` applied, or
/// `None` on a loop or an unreadable entry.
fn resolve(path: &Path, hops: &mut u32) -> Option<PathBuf> {
    let absolute = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir().ok()?.join(path)
    };
    let mut resolved = PathBuf::new();
    for component in absolute.components() {
        match component {
            Component::Prefix(_) | Component::RootDir => resolved.push(component.as_os_str()),
            Component::CurDir => {}
            Component::ParentDir => {
                resolved.pop();
            }
            Component::Normal(name) => {
                let next = resolved.join(name);
                match std::fs::symlink_metadata(&next) {
                    Ok(meta) if meta.file_type().is_symlink() => {
                        *hops += 1;
                        if *hops > MAX_LINK_HOPS {
                            return None;
                        }
                        let target = std::fs::read_link(&next).ok()?;
                        resolved = resolve(&resolved.join(target), hops)?;
                    }
                    Ok(_) => resolved = next,
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => resolved = next,
                    Err(_) => return None,
                }
            }
        }
    }
    Some(resolved)
}

/// If the payload is a single wrapper directory, return it.
///
/// Almost every release tarball unpacks to `tool-1.2.3-target/`; treating that
/// wrapper as the payload root is what makes `bin` paths in manifests short and
/// stable across versions.
/// True when a directory name is itself a macOS bundle rather than a container
/// of files.
///
/// A bundle is a directory to everything below the filesystem and a single
/// opaque item to everything above it, which is exactly the distinction
/// `unwrap_single_dir` and app discovery both need.
pub fn is_bundle_name(name: &str) -> bool {
    name.ends_with(".app") || name.ends_with(".framework")
}

pub fn unwrap_single_dir(dir: &Path) -> Result<PathBuf> {
    let mut entries = Vec::new();
    for entry in std::fs::read_dir(dir).map_err(|e| Error::io(dir, e))? {
        let entry = entry.map_err(|e| Error::io(dir, e))?;
        let name = entry.file_name();
        let name = name.to_string_lossy();
        // Metadata directories macOS and archivers leave behind are not payload.
        if name == "__MACOSX" || name == ".DS_Store" {
            continue;
        }
        entries.push(entry);
    }
    if entries.len() == 1 && entries[0].path().is_dir() {
        // A lone `.app` is the commonest shape a macOS zip or dmg has, and it
        // is the payload — not a wrapper around it. Unwrapping here would hand
        // back the bundle's `Contents`, and nothing downstream would ever see
        // an app to install.
        let name = entries[0].file_name();
        if !is_bundle_name(&name.to_string_lossy()) {
            return Ok(entries[0].path());
        }
    }
    Ok(dir.to_path_buf())
}

#[cfg(test)]
mod tests {
    #[test]
    fn a_lone_bundle_is_the_payload_and_is_not_unwrapped() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(tmp.path().join("TestApp.app/Contents/MacOS")).unwrap();

        assert_eq!(unwrap_single_dir(tmp.path()).unwrap(), tmp.path());
    }

    #[test]
    fn a_lone_plain_directory_is_still_unwrapped() {
        let tmp = tempfile::tempdir().unwrap();
        let inner = tmp.path().join("tool-1.0.0");
        std::fs::create_dir_all(inner.join("bin")).unwrap();

        assert_eq!(unwrap_single_dir(tmp.path()).unwrap(), inner);
    }

    use super::*;

    #[test]
    fn rejects_traversal_entries() {
        assert!(safe_member_path(Path::new("../../etc/passwd")).is_err());
        assert!(safe_member_path(Path::new("/etc/passwd")).is_err());
        assert!(safe_member_path(Path::new("a/../../b")).is_err());
        assert!(safe_member_path(Path::new("")).is_err());
    }

    #[test]
    fn accepts_and_normalises_ordinary_entries() {
        assert_eq!(
            safe_member_path(Path::new("./bin/rg")).unwrap(),
            PathBuf::from("bin/rg")
        );
        assert_eq!(
            safe_member_path(Path::new("rg-14/complete/rg.1")).unwrap(),
            PathBuf::from("rg-14/complete/rg.1")
        );
    }

    #[test]
    fn windows_device_names_and_trailing_dots_are_refused_there_only() {
        for raw in [
            "bin/nul",
            "bin/NUL.exe",
            "COM1",
            "docs/con.txt",
            "a/foo.",
            "a/foo /b",
            "x/a<b",
        ] {
            assert!(safe_member_path_for(Path::new(raw), true).is_err(), "{raw}");
            assert!(safe_member_path_for(Path::new(raw), false).is_ok(), "{raw}");
        }
        for raw in ["bin/console", "lib/nullable.so", "com10", "./bin/tool.exe"] {
            assert!(safe_member_path_for(Path::new(raw), true).is_ok(), "{raw}");
        }
    }

    #[test]
    fn rejects_windows_style_names() {
        assert!(safe_member_path(Path::new("C:windows")).is_err());
        assert!(safe_member_path(Path::new("a\\b")).is_err());
    }

    #[test]
    fn unwraps_a_single_wrapper_directory() {
        let tmp = tempfile::tempdir().unwrap();
        let inner = tmp.path().join("tool-1.2.3");
        std::fs::create_dir_all(inner.join("bin")).unwrap();
        std::fs::write(tmp.path().join(".DS_Store"), b"x").unwrap();
        assert_eq!(unwrap_single_dir(tmp.path()).unwrap(), inner);
    }

    #[test]
    fn keeps_root_when_payload_is_flat() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::write(tmp.path().join("a"), b"x").unwrap();
        std::fs::write(tmp.path().join("b"), b"x").unwrap();
        assert_eq!(unwrap_single_dir(tmp.path()).unwrap(), tmp.path());
    }
}
