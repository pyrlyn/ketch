// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The destinations one placement took over, and how to give them back.
//!
//! Placement writes links, copies and app bundles over what the version being
//! replaced put there. Deleting each occupant just before writing means a
//! failure halfway — the second link, a full disk while copying a large `.app`
//! — leaves the old version's links and app copy gone while state still
//! records them. So an occupant is moved aside, not deleted, and only thrown
//! away once the whole placement has succeeded. Kept apart from the backends
//! because every one of them needs the same rule.

#[cfg(unix)]
use super::unix::remove_any;
#[cfg(windows)]
use super::windows::remove_any;
use crate::error::{Error, Result};
use std::path::{Path, PathBuf};

/// Every destination a placement has claimed, with where its previous occupant
/// was moved to, if it had one.
pub(crate) struct Claims {
    taken: Vec<(PathBuf, Option<PathBuf>)>,
    aside: fn(&Path) -> PathBuf,
}

impl Default for Claims {
    fn default() -> Self {
        Claims::new(aside_name)
    }
}

impl Claims {
    /// `aside` names the sibling an occupant moves to; it must be in the same
    /// folder, so the move is a rename and cannot fail half done.
    pub(crate) fn new(aside: fn(&Path) -> PathBuf) -> Self {
        Claims {
            taken: Vec::new(),
            aside,
        }
    }

    /// Clear `path` for a new entry, keeping what was there to put back.
    ///
    /// The caller has already decided the occupant is ours to replace.
    pub(crate) fn take(&mut self, path: &Path) -> Result<()> {
        if path.symlink_metadata().is_err() {
            self.taken.push((path.to_path_buf(), None));
            return Ok(());
        }
        let aside = (self.aside)(path);
        remove_any(&aside).map_err(|e| Error::io(&aside, e))?;
        std::fs::rename(path, &aside).map_err(|e| Error::io(path, e))?;
        self.taken.push((path.to_path_buf(), Some(aside)));
        Ok(())
    }

    /// The placement succeeded: the previous occupants go for good.
    ///
    /// Best effort — a Windows executable that is still running cannot be
    /// deleted, and an aside left behind costs space, not correctness.
    pub(crate) fn discard(self) {
        for aside in self.taken.into_iter().filter_map(|(_, aside)| aside) {
            let _ = remove_any(&aside);
        }
    }

    /// The placement failed: remove whatever it wrote at each claimed path —
    /// a link, or a copy cut short — and move the previous occupant back.
    pub(crate) fn restore(self) {
        for (path, aside) in self.taken.into_iter().rev() {
            let _ = remove_any(&path);
            if let Some(aside) = aside {
                let _ = std::fs::rename(&aside, &path);
            }
        }
    }
}

/// `.<name>.ketch-aside` beside `path`: hidden on Unix, so a bin directory on
/// PATH never offers it as a command while it exists.
pub(crate) fn aside_name(path: &Path) -> PathBuf {
    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    path.with_file_name(format!(".{name}.ketch-aside"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn restore_puts_every_occupant_back_and_removes_what_was_new() {
        let tmp = tempfile::tempdir().unwrap();
        let occupied = tmp.path().join("tool");
        std::fs::write(&occupied, b"old").unwrap();
        let empty = tmp.path().join("other");

        let mut claims = Claims::default();
        claims.take(&occupied).unwrap();
        claims.take(&empty).unwrap();
        assert!(occupied.symlink_metadata().is_err(), "moved out of the way");
        std::fs::write(&occupied, b"new").unwrap();
        std::fs::create_dir_all(empty.join("half")).unwrap();

        claims.restore();

        assert_eq!(std::fs::read(&occupied).unwrap(), b"old");
        assert!(empty.symlink_metadata().is_err(), "a half copy is removed");
        assert_eq!(std::fs::read_dir(tmp.path()).unwrap().count(), 1);
    }

    #[test]
    fn discard_drops_the_previous_occupants_and_keeps_the_new_entries() {
        let tmp = tempfile::tempdir().unwrap();
        let occupied = tmp.path().join("Tool.app");
        std::fs::create_dir_all(occupied.join("Contents")).unwrap();

        let mut claims = Claims::default();
        claims.take(&occupied).unwrap();
        std::fs::create_dir_all(occupied.join("New")).unwrap();
        claims.discard();

        assert!(occupied.join("New").is_dir());
        assert!(!occupied.join("Contents").exists());
        assert_eq!(std::fs::read_dir(tmp.path()).unwrap().count(), 1);
    }
}
