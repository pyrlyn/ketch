// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! How ketch replaces a file it owns, over `fs-kit`.

use crate::error::{Error, Result};
use std::path::Path;

/// Replace `path` with `bytes`, creating its parent directory.
///
/// The new file is private (`0600`) whatever the old one had, and a symlink at
/// `path` is replaced rather than written through. The state, lock and cache
/// files were written that way when each built its own temp file (a temp file
/// is born `0600`, and a rename over a link replaces the link); callers rely
/// on it, so moving onto `fs-kit` keeps it.
pub(crate) fn write_private(path: &Path, bytes: impl AsRef<[u8]>) -> Result<()> {
    let options = fs_kit::write::Options::new()
        .create_parent(true)
        .preserve_permissions(false)
        .follow_symlinks(false);
    #[cfg(unix)]
    let options = options.mode(0o600);
    options
        .write(path, bytes)
        .map_err(|e| Error::fs_kit(path, e))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_into_a_missing_directory_and_replaces_whole() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("a/b/state.json");
        write_private(&file, "one").unwrap();
        write_private(&file, "two").unwrap();
        assert_eq!(std::fs::read_to_string(&file).unwrap(), "two");
        let siblings = std::fs::read_dir(file.parent().unwrap()).unwrap().count();
        assert_eq!(siblings, 1, "no temp file may be left behind");
    }

    #[cfg(unix)]
    #[test]
    fn the_file_is_private_even_over_a_wider_one() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("ketch.lock");
        std::fs::write(&file, "old").unwrap();
        std::fs::set_permissions(&file, std::fs::Permissions::from_mode(0o644)).unwrap();
        write_private(&file, "new").unwrap();
        let mode = std::fs::metadata(&file).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);
    }

    #[cfg(unix)]
    #[test]
    fn a_symlink_is_replaced_not_followed() {
        let dir = tempfile::tempdir().unwrap();
        let real = dir.path().join("real");
        let link = dir.path().join("link");
        std::fs::write(&real, "keep").unwrap();
        std::os::unix::fs::symlink(&real, &link).unwrap();
        write_private(&link, "new").unwrap();
        assert_eq!(std::fs::read_to_string(&real).unwrap(), "keep");
        assert!(std::fs::symlink_metadata(&link).unwrap().is_file());
    }

    #[test]
    fn an_error_names_the_path_once() {
        let dir = tempfile::tempdir().unwrap();
        let blocker = dir.path().join("file");
        std::fs::write(&blocker, "x").unwrap();
        let err = write_private(&blocker.join("child"), "x").unwrap_err();
        let text = err.to_string();
        assert_eq!(text.matches("child").count(), 1, "{text}");
    }
}
