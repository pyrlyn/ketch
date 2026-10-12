// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Replacing a file by renaming a finished copy over it.
//!
//! State, the lockfile, manifests, shell startup files and the `latest` cache
//! are all rewritten whole, and an interrupted write must leave the old file
//! intact rather than half of the new one. Each kept its own copy of the
//! stage-then-rename dance, differing only in what it promised beyond that;
//! those differences are the [`Replace`] options here.

use crate::error::{Error, Result};
use std::io::Write;
use std::path::Path;

/// What a replacement promises beyond atomicity.
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct Replace {
    /// Give the new file the permissions the old one had. A staged file is
    /// created private, so without this a file someone else reads (or one
    /// they made private on purpose) would change mode on every save.
    pub keep_mode: bool,
    /// Create a file that has no predecessor the way `std::fs::write` would,
    /// readable under the umask, rather than private: a new shell startup
    /// file must look like one the user's editor made.
    pub ordinary_mode: bool,
    /// Flush the bytes before the rename and, best effort, the directory
    /// after it. The rename is atomic only over what the file really holds;
    /// without the flush a crash can record the rename and lose the bytes,
    /// leaving an empty file where a whole one used to be.
    pub durable: bool,
}

/// Write `body` to `target` through a staged sibling, creating the parent
/// directory first.
pub(crate) fn replace_file(target: &Path, body: &[u8], how: Replace) -> Result<()> {
    let parent = target
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    std::fs::create_dir_all(parent).map_err(|e| Error::io(parent, e))?;
    let mut builder = tempfile::Builder::new();
    #[cfg(unix)]
    if how.ordinary_mode {
        use std::os::unix::fs::PermissionsExt;
        builder.permissions(std::fs::Permissions::from_mode(0o666));
    }
    let mut staged = builder
        .tempfile_in(parent)
        .map_err(|e| Error::io(parent, e))?;
    staged
        .write_all(body)
        .map_err(|e| Error::io(staged.path(), e))?;
    staged.flush().map_err(|e| Error::io(staged.path(), e))?;
    if how.keep_mode {
        if let Ok(meta) = std::fs::metadata(target) {
            let _ = std::fs::set_permissions(staged.path(), meta.permissions());
        }
    }
    if how.durable {
        staged
            .as_file()
            .sync_all()
            .map_err(|e| Error::io(staged.path(), e))?;
    }
    staged
        .persist(target)
        .map_err(|e| Error::io(target, e.error))?;
    if how.durable {
        // Some filesystems refuse to sync a directory; that is no reason to
        // fail a save whose bytes are already on disk.
        if let Ok(dir) = std::fs::File::open(parent) {
            let _ = dir.sync_all();
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_replacement_creates_the_parent_and_leaves_no_staged_file() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("nested").join("file.txt");
        replace_file(&target, b"one", Replace::default()).unwrap();
        replace_file(&target, b"two", Replace::default()).unwrap();
        assert_eq!(std::fs::read_to_string(&target).unwrap(), "two");
        let siblings: Vec<_> = std::fs::read_dir(target.parent().unwrap())
            .unwrap()
            .map(|e| e.unwrap().file_name())
            .collect();
        assert_eq!(siblings, ["file.txt"]);
    }

    #[test]
    fn a_failed_replacement_leaves_the_old_file_whole() {
        let dir = tempfile::tempdir().unwrap();
        // A directory where the file should go: the rename cannot land.
        let target = dir.path().join("taken");
        std::fs::create_dir(&target).unwrap();
        std::fs::write(target.join("inside"), "kept").unwrap();
        assert!(replace_file(&target, b"new", Replace::default()).is_err());
        assert_eq!(
            std::fs::read_to_string(target.join("inside")).unwrap(),
            "kept"
        );
        assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 1);
    }

    #[cfg(unix)]
    #[test]
    fn keep_mode_carries_the_old_permissions_and_its_absence_stays_private() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let kept = dir.path().join("kept");
        let fresh = dir.path().join("fresh");
        for path in [&kept, &fresh] {
            std::fs::write(path, "old").unwrap();
            std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o640)).unwrap();
        }
        let keep = Replace {
            keep_mode: true,
            ..Replace::default()
        };
        replace_file(&kept, b"new", keep).unwrap();
        replace_file(&fresh, b"new", Replace::default()).unwrap();
        assert_eq!(mode(&kept), 0o640);
        assert_eq!(mode(&fresh), 0o600);
    }

    #[cfg(unix)]
    #[test]
    fn ordinary_mode_makes_a_new_file_as_std_write_would() {
        let dir = tempfile::tempdir().unwrap();
        let written = dir.path().join("written");
        let replaced = dir.path().join("replaced");
        std::fs::write(&written, "new").unwrap();
        let ordinary = Replace {
            ordinary_mode: true,
            ..Replace::default()
        };
        replace_file(&replaced, b"new", ordinary).unwrap();
        assert_eq!(mode(&replaced), mode(&written));
    }

    #[cfg(unix)]
    fn mode(path: &Path) -> u32 {
        use std::os::unix::fs::PermissionsExt;
        std::fs::metadata(path).unwrap().permissions().mode() & 0o777
    }
}
