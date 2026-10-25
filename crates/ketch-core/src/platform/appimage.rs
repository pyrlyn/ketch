// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Read an icon out of a Linux AppImage without running it.
//!
//! Type 2 AppImages are an ELF runtime with a squashfs image appended. The
//! icon is the root member `.DirIcon` (or a sibling image). Running the file
//! — `--appimage-extract` included — would execute a publisher's code at
//! install time, so this module only parses bytes it can check.
//!
//! A missing icon is not an install failure, and neither is a compressor this
//! build cannot read. gzip and xz are read when those features are on; zstd
//! and lz4, which current appimagetool builds often use, are not. A member
//! name that would leave the destination is skipped. Callers still write the
//! `.desktop` file and record only the paths they actually created.
//!
//! Member names are untrusted. They are accepted only when
//! [`icon_member_ok`] says so, which is [`crate::extract::safe_member_path`]
//! plus a single path component. The bytes are returned in memory; the
//! destination file name is chosen by the caller from the package name, never
//! from the member name.

use crate::error::{Error, Result};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

/// Largest icon this will hold. Real `.DirIcon` files are small; a bigger
/// member is not an icon.
const MAX_ICON_BYTES: usize = 4 * 1024 * 1024;
/// Squashfs metadata blocks are at most 8 KiB. A larger header is not one.
const MAX_META_BYTES: usize = 8192;
/// One fragment may hold several small files. Cap it so a hostile size field
/// cannot allocate the disk.
const MAX_FRAGMENT_BYTES: usize = 8 * 1024 * 1024;
/// How far past the ELF to look for the squashfs magic. Runtimes pad.
const MAX_SQUASHFS_SCAN: u64 = 1024 * 1024;
/// `SQUASHFS_COMPRESSED_BIT_BLOCK`: set means the data block is stored as-is.
const UNCOMPRESSED_BLOCK: u32 = 1 << 24;
/// Metadata headers use the same idea in bit 15.
const UNCOMPRESSED_META: u16 = 1 << 15;
/// Fragment index used when the file lives in data blocks instead.
const NO_FRAGMENT: u32 = 0xffff_ffff;

/// A `.desktop` file for one AppImage.
///
/// `package` and `exec` are written as their own lines. A newline or other
/// control character in either would add a field the publisher chose, so
/// that input is refused rather than stripped into a still-wrong file.
pub(crate) fn desktop_entry(package: &str, exec: &Path, icon: Option<&Path>) -> Result<String> {
    let name = desktop_value("package name", package)?;
    let exec = exec
        .to_str()
        .ok_or_else(|| Error::msg("AppImage path is not valid Unicode"))?;
    let exec = desktop_value("AppImage path", exec)?;
    let mut text = format!(
        "[Desktop Entry]\nType=Application\nName={name}\nExec={exec} %U\nTerminal=false\nCategories=Utility;\nX-Ketch-Package={name}\n",
        exec = quote_exec(&exec),
    );
    if let Some(icon) = icon {
        let icon = icon
            .to_str()
            .ok_or_else(|| Error::msg("icon path is not valid Unicode"))?;
        let icon = desktop_value("icon path", icon)?;
        text.push_str("Icon=");
        text.push_str(&escape_desktop_string(&icon));
        text.push('\n');
    }
    Ok(text)
}

/// True when `path` still contains the marker [`desktop_entry`] writes.
///
/// A person who replaced the file no longer has that line. Uninstall must
/// leave their file alone; the marker is how a copied file is recognised,
/// the same role a symlink's target plays for a binary.
pub(crate) fn desktop_marker_present(path: &Path) -> bool {
    let Ok(text) = std::fs::read_to_string(path) else {
        return false;
    };
    text.lines().any(|line| {
        let Some(value) = line.strip_prefix("X-Ketch-Package=") else {
            return false;
        };
        !value.is_empty() && value.len() < 256 && !value.chars().any(|c| c.is_control())
    })
}

/// Whether `name` may be read as an icon member.
///
/// One component, no controls, and [`crate::extract::safe_member_path`] —
/// `../.bashrc` and `foo/../../etc/passwd` are archive paths, not icons.
pub(crate) fn icon_member_ok(name: &str) -> bool {
    if name.is_empty() || name.len() > 255 {
        return false;
    }
    if name.bytes().any(|b| b < 0x20 || b == 0x7f) {
        return false;
    }
    if name.contains('/') || name.contains('\\') {
        return false;
    }
    if crate::extract::safe_member_path(Path::new(name)).is_err() {
        return false;
    }
    let lower = name.to_ascii_lowercase();
    lower == ".diricon"
        || lower.ends_with(".png")
        || lower.ends_with(".svg")
        || lower.ends_with(".svgz")
        || lower.ends_with(".xpm")
}

/// File name extension and hicolor size directory for icon bytes, if they
/// look like an image. Anything else is not installed as an icon.
pub(crate) fn icon_image(bytes: &[u8]) -> Option<(&'static str, &'static str)> {
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        return Some(("png", "256x256"));
    }
    if bytes.starts_with(b"/* XPM */") {
        return Some(("xpm", "256x256"));
    }
    if looks_like_svg(bytes) {
        return Some(("svg", "scalable"));
    }
    None
}

/// Icon bytes embedded in `appimage`, when the file is a type 2 AppImage
/// whose root directory names one we can read.
///
/// `None` covers every failure: not an AppImage, no icon member, a member
/// name that fails [`icon_member_ok`], a compressor this build cannot
/// inflate, or a size that is not an icon. The caller installs without an
/// icon in that case.
pub(crate) fn embedded_icon(appimage: &Path) -> Option<Vec<u8>> {
    let mut file = File::open(appimage).ok()?;
    let file_len = file.metadata().ok()?.len();
    let base = find_squashfs(&mut file, file_len)?;
    let superblock = read_at(&mut file, base, 96, file_len)?;
    if &superblock[0..4] != b"hsqs" {
        return None;
    }
    let major = read_u16(&superblock, 28)?;
    if major != 4 {
        return None;
    }
    let compression = read_u16(&superblock, 20)?;
    let fragments = read_u32(&superblock, 16)?;
    if fragments > 4096 {
        return None;
    }
    let root = read_u64(&superblock, 32)?;
    let inode_table = base.checked_add(read_u64(&superblock, 64)?)?;
    let dir_table = base.checked_add(read_u64(&superblock, 72)?)?;
    let frag_table = base.checked_add(read_u64(&superblock, 80)?)?;
    let entries = root_entries(
        &mut file,
        file_len,
        compression,
        inode_table,
        dir_table,
        root,
    )?;
    let inode_ref = entries
        .iter()
        .find(|(name, _)| name.eq_ignore_ascii_case(".DirIcon") && icon_member_ok(name))
        .or_else(|| entries.iter().find(|(name, _)| icon_member_ok(name)))
        .map(|(_, inode_ref)| *inode_ref)?;
    // `icon_member_ok` already refused a name that would escape. The bytes are
    // copied under a file name the caller chooses, never under the member name.
    let inode = read_inode(&mut file, file_len, compression, inode_table, inode_ref)?;
    let bytes = read_fragment_file(
        &mut file,
        file_len,
        compression,
        base,
        frag_table,
        fragments,
        &inode,
    )?;
    if bytes.len() > MAX_ICON_BYTES || icon_image(&bytes).is_none() {
        return None;
    }
    Some(bytes)
}

/// An icon file already extracted beside the AppImage.
///
/// The relative path has to pass the same member-name guard as an embedded
/// one. The destination is not this path; the caller copies the bytes under
/// a name it chose.
pub(crate) fn sibling_icon(root: &Path, appimage: &Path) -> Option<(PathBuf, Vec<u8>)> {
    let stem = appimage.file_stem()?.to_str()?.to_ascii_lowercase();
    let mut found: Vec<PathBuf> = walkdir::WalkDir::new(root)
        .max_depth(2)
        .follow_links(false)
        .into_iter()
        .filter_map(|entry| entry.ok())
        .filter(|entry| entry.file_type().is_file())
        .map(|entry| entry.into_path())
        .filter(|path| path != appimage)
        .filter(|path| {
            let Some(rel) = path.strip_prefix(root).ok() else {
                return false;
            };
            if rel.components().any(|part| {
                part.as_os_str()
                    .to_str()
                    .is_some_and(|text| text.starts_with(".ketch-icon"))
            }) {
                return false;
            }
            // A walk cannot produce `..`, but the name is still untrusted
            // input from the archive that was just extracted.
            let Some(file_name) = rel.file_name().and_then(|n| n.to_str()) else {
                return false;
            };
            icon_member_ok(file_name)
                && crate::extract::safe_member_path(rel).is_ok()
                && crate::extract::resolves_inside(path, root)
        })
        .collect();
    found.sort();
    let prefer = |path: &Path| {
        path.file_name()
            .and_then(|n| n.to_str())
            .is_some_and(|n| n.eq_ignore_ascii_case(".DirIcon"))
    };
    let named = |path: &Path| {
        path.file_stem()
            .and_then(|n| n.to_str())
            .is_some_and(|n| n.eq_ignore_ascii_case(&stem))
    };
    let path = found
        .iter()
        .find(|p| prefer(p))
        .or_else(|| found.iter().find(|p| named(p)))
        .or_else(|| found.first())?;
    let meta = std::fs::metadata(path).ok()?;
    if !meta.is_file() || meta.len() > MAX_ICON_BYTES as u64 {
        return None;
    }
    let bytes = std::fs::read(path).ok()?;
    if icon_image(&bytes).is_none() {
        return None;
    }
    Some((path.clone(), bytes))
}

fn looks_like_svg(bytes: &[u8]) -> bool {
    let head = &bytes[..bytes.len().min(1024)];
    if head.contains(&0) {
        return false;
    }
    let text = String::from_utf8_lossy(head);
    let trimmed = text.trim_start_matches(|c: char| c.is_whitespace() || c == '\u{feff}');
    trimmed.starts_with("<svg")
        || (trimmed.starts_with("<?xml") && text.to_ascii_lowercase().contains("<svg"))
}

fn desktop_value(what: &str, value: &str) -> Result<String> {
    if value.is_empty() || value.chars().any(|c| c.is_control()) {
        return Err(Error::msg(format!(
            "refusing to write a desktop entry whose {what} contains a control character"
        )));
    }
    Ok(value.to_string())
}

/// A desktop string value: spaces and backslashes use the entry escape
/// sequences, so a path with a space stays one field.
fn escape_desktop_string(value: &str) -> String {
    let mut out = String::new();
    for c in value.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            ' ' => out.push_str("\\s"),
            _ => out.push(c),
        }
    }
    out
}

/// Quote one `Exec` token. `%` is doubled so a path cannot be a field code.
fn quote_exec(path: &str) -> String {
    let mut out = String::from("\"");
    for c in path.chars() {
        match c {
            '\\' | '"' | '`' | '$' => {
                out.push('\\');
                out.push(c);
            }
            '%' => out.push_str("%%"),
            _ => out.push(c),
        }
    }
    out.push('"');
    out
}

fn find_squashfs(file: &mut File, file_len: u64) -> Option<u64> {
    let header = read_at(file, 0, 64, file_len)?;
    if &header[0..4] != b"\x7fELF" {
        return None;
    }
    // AppImage overwrites the ELF padding with its own magic. Without it
    // this is some other ELF that happens to be named `.AppImage`.
    if &header[8..11] != b"AI\x02" && &header[8..11] != b"AI\x01" {
        return None;
    }
    let end = elf_payload_end(&header, file, file_len)?;
    // Runtimes pad the ELF out to a page before the image. The scan is capped
    // so a file that is not a squashfs does not get read to the end.
    let scan_end = end.saturating_add(MAX_SQUASHFS_SCAN).min(file_len);
    let mut offset = end;
    while offset + 4 <= scan_end {
        let magic = read_at(file, offset, 4, file_len)?;
        if &magic == b"hsqs" {
            return Some(offset);
        }
        offset += 1;
    }
    None
}

fn elf_payload_end(header: &[u8], file: &mut File, file_len: u64) -> Option<u64> {
    let class = header[4];
    let data = header[5];
    if data != 1 {
        return None;
    }
    let (phoff, phentsize, phnum, filesz_at, offset_at) = match class {
        2 => {
            let phoff = read_u64(header, 32)?;
            let phentsize = read_u16(header, 54)? as u64;
            let phnum = read_u16(header, 56)? as u64;
            (phoff, phentsize, phnum, 32usize, 8usize)
        }
        1 => {
            let phoff = read_u32(header, 28)? as u64;
            let phentsize = read_u16(header, 42)? as u64;
            let phnum = read_u16(header, 44)? as u64;
            (phoff, phentsize, phnum, 16usize, 4usize)
        }
        _ => return None,
    };
    if phnum > 64 || phentsize < (filesz_at + 8) as u64 || phentsize > 1024 {
        return None;
    }
    let mut end = 0u64;
    for i in 0..phnum {
        let at = phoff.checked_add(i.checked_mul(phentsize)?)?;
        let ph = read_at(file, at, phentsize as usize, file_len)?;
        let kind = read_u32(&ph, 0)?;
        if kind != 1 {
            continue;
        }
        let offset = read_int(&ph, offset_at, class)?;
        let filesz = read_int(&ph, filesz_at, class)?;
        end = end.max(offset.saturating_add(filesz));
    }
    Some(end)
}

fn read_int(buf: &[u8], at: usize, class: u8) -> Option<u64> {
    if class == 2 {
        read_u64(buf, at)
    } else {
        read_u32(buf, at).map(|n| n as u64)
    }
}

struct RegFile {
    fragment: u32,
    offset: u32,
    size: u64,
}

fn root_entries(
    file: &mut File,
    file_len: u64,
    compression: u16,
    inode_table: u64,
    dir_table: u64,
    root_ref: u64,
) -> Option<Vec<(String, u64)>> {
    let inode = read_inode(file, file_len, compression, inode_table, root_ref)?;
    if inode.kind != 1 || inode.dir_size < 3 {
        return None;
    }
    let stored = (inode.dir_size - 3) as usize;
    if stored > MAX_META_BYTES * 4 {
        return None;
    }
    let data = read_meta_span(
        file,
        file_len,
        compression,
        dir_table,
        inode.dir_block,
        inode.dir_offset as usize,
        stored,
    )?;
    parse_dir(&data)
}

struct Inode {
    kind: u16,
    dir_block: u32,
    dir_offset: u16,
    dir_size: u32,
    file: Option<RegFile>,
}

fn read_inode(
    file: &mut File,
    file_len: u64,
    compression: u16,
    inode_table: u64,
    inode_ref: u64,
) -> Option<Inode> {
    let block = (inode_ref >> 16) as u32;
    let offset = (inode_ref & 0xffff) as usize;
    if block > 64 {
        return None;
    }
    let body = meta_block_at(file, file_len, compression, inode_table, block)?;
    let kind = read_u16(&body, offset)?;
    if kind == 1 {
        if offset + 32 > body.len() {
            return None;
        }
        return Some(Inode {
            kind,
            dir_block: read_u32(&body, offset + 16)?,
            dir_offset: read_u16(&body, offset + 26)?,
            dir_size: u32::from(read_u16(&body, offset + 24)?),
            file: None,
        });
    }
    if kind == 2 {
        if offset + 32 > body.len() {
            return None;
        }
        return Some(Inode {
            kind,
            dir_block: 0,
            dir_offset: 0,
            dir_size: 0,
            file: Some(RegFile {
                fragment: read_u32(&body, offset + 20)?,
                offset: read_u32(&body, offset + 24)?,
                size: u64::from(read_u32(&body, offset + 28)?),
            }),
        });
    }
    None
}

fn parse_dir(data: &[u8]) -> Option<Vec<(String, u64)>> {
    let mut entries = Vec::new();
    let mut pos = 0;
    while pos + 12 <= data.len() {
        let count = read_u32(data, pos)?;
        let start_block = read_u32(data, pos + 4)?;
        pos += 12;
        if count > 1024 {
            return None;
        }
        for _ in 0..=count {
            if pos + 8 > data.len() {
                return None;
            }
            let offset = read_u16(data, pos)?;
            let name_size = read_u16(data, pos + 6)? as usize;
            pos += 8;
            let len = name_size.checked_add(1)?;
            if len > 256 || pos + len > data.len() {
                return None;
            }
            let raw = &data[pos..pos + len];
            pos += len;
            let name = std::str::from_utf8(raw).ok()?.to_string();
            let inode_ref = (u64::from(start_block) << 16) | u64::from(offset);
            entries.push((name, inode_ref));
        }
    }
    Some(entries)
}

fn read_fragment_file(
    file: &mut File,
    file_len: u64,
    compression: u16,
    base: u64,
    frag_table: u64,
    fragments: u32,
    inode: &Inode,
) -> Option<Vec<u8>> {
    let reg = inode.file.as_ref()?;
    if reg.fragment == NO_FRAGMENT || reg.fragment >= fragments {
        return None;
    }
    if reg.size > MAX_ICON_BYTES as u64 {
        return None;
    }
    let index = reg.fragment / 512;
    let slot = (reg.fragment % 512) as usize;
    let index_at = frag_table.checked_add(u64::from(index) * 8)?;
    let meta_rel = read_u64_at(file, index_at, file_len)?;
    let meta_at = base.checked_add(meta_rel)?;
    let body = read_meta_block(file, meta_at, compression, file_len)?.0;
    let at = slot.checked_mul(16)?;
    if at + 16 > body.len() {
        return None;
    }
    let start = read_u64(&body, at)?;
    let size_field = read_u32(&body, at + 8)?;
    let stored = (size_field & 0x00ff_ffff) as usize;
    if stored == 0 || stored > MAX_FRAGMENT_BYTES {
        return None;
    }
    let raw = read_at(file, base.checked_add(start)?, stored, file_len)?;
    let data = if size_field & UNCOMPRESSED_BLOCK != 0 {
        raw
    } else {
        decompress(compression, &raw, MAX_FRAGMENT_BYTES)?
    };
    let begin = reg.offset as usize;
    let end = begin.checked_add(reg.size as usize)?;
    if end > data.len() {
        return None;
    }
    Some(data[begin..end].to_vec())
}

fn read_meta_span(
    file: &mut File,
    file_len: u64,
    compression: u16,
    table: u64,
    block: u32,
    offset: usize,
    len: usize,
) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(len);
    let mut index = block;
    let mut skip = offset;
    let mut need = len;
    for _ in 0..4 {
        if index > 64 {
            return None;
        }
        let body = meta_block_at(file, file_len, compression, table, index)?;
        if skip >= body.len() {
            skip -= body.len();
            index += 1;
            continue;
        }
        let take = (body.len() - skip).min(need);
        out.extend_from_slice(&body[skip..skip + take]);
        need -= take;
        skip = 0;
        if need == 0 {
            return Some(out);
        }
        index += 1;
    }
    None
}

fn meta_block_at(
    file: &mut File,
    file_len: u64,
    compression: u16,
    table: u64,
    index: u32,
) -> Option<Vec<u8>> {
    let mut off = table;
    for i in 0..=index {
        let (body, next) = read_meta_block(file, off, compression, file_len)?;
        if i == index {
            return Some(body);
        }
        off = next;
    }
    None
}

fn read_meta_block(
    file: &mut File,
    offset: u64,
    compression: u16,
    file_len: u64,
) -> Option<(Vec<u8>, u64)> {
    let hdr = read_at(file, offset, 2, file_len)?;
    let word = u16::from_le_bytes([hdr[0], hdr[1]]);
    let size = (word & !UNCOMPRESSED_META) as usize;
    if size == 0 || size > MAX_META_BYTES {
        return None;
    }
    let raw = read_at(file, offset + 2, size, file_len)?;
    let body = if word & UNCOMPRESSED_META != 0 {
        raw
    } else {
        decompress(compression, &raw, MAX_META_BYTES)?
    };
    if body.len() > MAX_META_BYTES {
        return None;
    }
    Some((body, offset + 2 + size as u64))
}

fn decompress(compression: u16, data: &[u8], limit: usize) -> Option<Vec<u8>> {
    match compression {
        1 => decompress_zlib(data, limit),
        4 => decompress_xz(data, limit),
        _ => None,
    }
}

#[cfg(feature = "gzip")]
fn decompress_zlib(data: &[u8], limit: usize) -> Option<Vec<u8>> {
    let mut decoder = flate2::read::ZlibDecoder::new(std::io::Cursor::new(data));
    read_limited(&mut decoder, limit)
}

#[cfg(not(feature = "gzip"))]
fn decompress_zlib(_data: &[u8], _limit: usize) -> Option<Vec<u8>> {
    None
}

#[cfg(feature = "xz")]
fn decompress_xz(data: &[u8], limit: usize) -> Option<Vec<u8>> {
    // 8 MiB of decoder dictionary is far past an icon and refuses a
    // multi-gigabyte one before it is allocated.
    let mut decoder =
        lzma_rust2::XzReader::new_mem_limit(std::io::Cursor::new(data), false, 8 * 1024);
    read_limited(&mut decoder, limit)
}

#[cfg(not(feature = "xz"))]
fn decompress_xz(_data: &[u8], _limit: usize) -> Option<Vec<u8>> {
    None
}

fn read_limited(reader: &mut impl Read, limit: usize) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    let mut buf = [0u8; 8192];
    loop {
        match reader.read(&mut buf) {
            Ok(0) => return Some(out),
            Ok(n) => {
                if out.len().saturating_add(n) > limit {
                    return None;
                }
                out.extend_from_slice(&buf[..n]);
            }
            Err(_) => return None,
        }
    }
}

fn read_at(file: &mut File, offset: u64, len: usize, file_len: u64) -> Option<Vec<u8>> {
    let end = offset.checked_add(len as u64)?;
    if len == 0 || end > file_len {
        return None;
    }
    file.seek(SeekFrom::Start(offset)).ok()?;
    let mut buf = vec![0u8; len];
    file.read_exact(&mut buf).ok()?;
    Some(buf)
}

fn read_u64_at(file: &mut File, offset: u64, file_len: u64) -> Option<u64> {
    let buf = read_at(file, offset, 8, file_len)?;
    read_u64(&buf, 0)
}

fn read_u16(buf: &[u8], at: usize) -> Option<u16> {
    let b = buf.get(at..at + 2)?;
    Some(u16::from_le_bytes([b[0], b[1]]))
}

fn read_u32(buf: &[u8], at: usize) -> Option<u32> {
    let b = buf.get(at..at + 4)?;
    Some(u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
}

fn read_u64(buf: &[u8], at: usize) -> Option<u64> {
    let b = buf.get(at..at + 8)?;
    Some(u64::from_le_bytes([
        b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
    ]))
}

/// ELF runtime in front of `squashfs`, the shape a type 2 AppImage has.
///
/// Tests build this instead of invoking `appimagetool`. The program-header
/// end is where the reader starts looking for `hsqs`.
#[cfg(test)]
pub(crate) fn with_runtime(squashfs: &[u8]) -> Vec<u8> {
    let mut bytes = vec![0u8; 120];
    bytes[0..4].copy_from_slice(b"\x7fELF");
    bytes[4] = 2;
    bytes[5] = 1;
    bytes[6] = 1;
    bytes[8..11].copy_from_slice(b"AI\x02");
    bytes[16] = 2;
    bytes[18] = 0x3e;
    bytes[20] = 1;
    bytes[32] = 64;
    bytes[52] = 64;
    bytes[54] = 56;
    bytes[56] = 1;
    bytes[64] = 1;
    bytes[68] = 5;
    bytes[96] = 120;
    bytes[104] = 120;
    bytes.extend_from_slice(squashfs);
    bytes
}

#[cfg(test)]
mod tests {
    use super::*;

    const SQUASHFS: &[u8] = include_bytes!("../../../../tests/fixtures/appimage-diricon.sqfs");
    const PNG: &[u8] = include_bytes!("../../../../tests/fixtures/appimage-diricon.png");

    #[test]
    fn an_escaping_icon_member_is_refused() {
        assert!(icon_member_ok(".DirIcon"));
        assert!(icon_member_ok("demo.png"));
        assert!(!icon_member_ok("../.bashrc"));
        assert!(!icon_member_ok("../.Icon"));
        assert!(!icon_member_ok("foo/../../etc/passwd"));
        assert!(!icon_member_ok("/etc/passwd"));
        assert!(!icon_member_ok(".DirIcon\nExec=/bin/sh"));
        assert!(!icon_member_ok(""));
    }

    #[test]
    fn an_embedded_diricon_is_extracted_without_running_the_appimage() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("Demo.AppImage");
        std::fs::write(&path, with_runtime(SQUASHFS)).unwrap();
        let icon = embedded_icon(&path).expect("icon");
        assert_eq!(icon, PNG);
    }

    #[test]
    fn a_renamed_member_that_escapes_is_not_extracted() {
        let mut image = SQUASHFS.to_vec();
        let from = b".DirIcon";
        let at = image
            .windows(from.len())
            .position(|w| w == from)
            .expect("fixture names .DirIcon");
        image[at..at + from.len()].copy_from_slice(b"../.Icon");
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("Demo.AppImage");
        std::fs::write(&path, with_runtime(&image)).unwrap();
        assert!(embedded_icon(&path).is_none());
    }

    #[test]
    fn a_desktop_entry_refuses_a_value_that_would_add_a_line() {
        let exec = Path::new("/opt/demo");
        let text = desktop_entry("demo", exec, None).unwrap();
        assert!(text.contains("Type=Application"));
        assert!(text.contains("X-Ketch-Package=demo"));
        assert!(text.contains("Exec=\"/opt/demo\" %U"));
        assert!(desktop_entry("de\nmo", exec, None).is_err());
        let hostile = Path::new("/tmp/a\nb");
        assert!(desktop_entry("demo", hostile, None).is_err());
    }

    #[test]
    fn a_sibling_icon_is_read_only_when_its_name_stays_inside_the_payload() {
        let tmp = tempfile::tempdir().unwrap();
        let app = tmp.path().join("Demo.AppImage");
        std::fs::write(&app, b"app").unwrap();
        std::fs::write(tmp.path().join(".DirIcon"), PNG).unwrap();
        let (path, bytes) = sibling_icon(tmp.path(), &app).unwrap();
        assert_eq!(path.file_name().unwrap(), ".DirIcon");
        assert_eq!(bytes, PNG);
    }
}
