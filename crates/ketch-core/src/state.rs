// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! What is installed, on disk.
//!
//! `state.json` is the only durable record ketch keeps. It is rewritten
//! atomically — staged next to the real file and renamed — so an interrupted
//! write can never leave a half-parsed state file behind, which would look
//! exactly like "nothing is installed".
//!
//! Version 1 stays readable when fields are added with serde defaults. A
//! file written before retention existed loads as keep-1 with an empty
//! retained list per package. Upgrade never deletes an old prefix; `ketch
//! prune` is the only command that does.

use crate::config::Config;
use crate::error::{Error, Result};
use crate::model::{InstalledPackage, PackageSpec, RetentionPolicy};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

/// Bumped only when the on-disk shape changes incompatibly.
pub const STATE_VERSION: u32 = 1;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct State {
    pub version: u32,
    /// How many previous prefixes `ketch prune` leaves. Upgrade never prunes.
    #[serde(default)]
    pub retention: RetentionPolicy,
    /// Keyed by package name, which is unique across sources by construction.
    #[serde(default)]
    pub packages: BTreeMap<String, InstalledPackage>,
}

impl Default for State {
    fn default() -> Self {
        State {
            version: STATE_VERSION,
            retention: RetentionPolicy::default(),
            packages: BTreeMap::new(),
        }
    }
}

impl State {
    /// Read the state file. A missing file is an empty state, not an error.
    pub fn load(cfg: &Config) -> Result<Self> {
        Self::load_path(&cfg.state_file)
    }

    pub fn load_path(path: &Path) -> Result<Self> {
        let text = match std::fs::read_to_string(path) {
            Ok(t) => t,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(State::default()),
            Err(e) => return Err(Error::io(path, e)),
        };
        // An absent file means nothing has been installed yet. An empty one
        // means a write was lost, and every package on disk is about to be
        // forgotten — say so rather than quietly starting over.
        if text.trim().is_empty() {
            return Err(Error::msg(format!(
                "{} is empty, which usually means an interrupted write. Anything \
                 already installed is still in the store; remove the file to start \
                 a fresh record, then `ketch relink` each package.",
                path.display()
            )));
        }
        let state: State = serde_json::from_str(&text)
            .map_err(|e| Error::parse(path.display().to_string(), e.to_string()))?;
        if state.version > STATE_VERSION {
            return Err(Error::msg(format!(
                "{} was written by a newer ketch (state version {}); upgrade with `ketch self upgrade`",
                path.display(),
                state.version
            )));
        }
        Ok(state)
    }

    pub fn save(&self, cfg: &Config) -> Result<()> {
        self.save_path(&cfg.state_file)
    }

    pub fn save_path(&self, path: &Path) -> Result<()> {
        let json = serde_json::to_string_pretty(self)
            .map_err(|e| Error::parse("state".to_string(), e.to_string()))?;
        // Durable by default in fs-kit: without the fsync the kernel is free to
        // record the rename and lose the bytes, leaving a zero-length state
        // file, which is to say an empty list of installed packages.
        crate::fsio::write_private(path, format!("{json}\n"))
    }

    pub fn get(&self, name: &str) -> Option<&InstalledPackage> {
        self.packages.get(name)
    }

    pub fn get_mut(&mut self, name: &str) -> Option<&mut InstalledPackage> {
        self.packages.get_mut(name)
    }

    pub fn insert(&mut self, pkg: InstalledPackage) {
        self.packages.insert(pkg.name.clone(), pkg);
    }

    pub fn remove(&mut self, name: &str) -> Option<InstalledPackage> {
        self.packages.remove(name)
    }

    pub fn names(&self) -> Vec<&str> {
        self.packages.keys().map(|k| k.as_str()).collect()
    }

    pub fn iter(&self) -> impl Iterator<Item = &InstalledPackage> {
        self.packages.values()
    }

    /// Look a package up the way a user would name it: by install name, by a
    /// binary it provides, or by its source id (`owner/repo`).
    pub fn find(&self, query: &str) -> Option<&InstalledPackage> {
        if let Some(hit) = self.packages.get(query) {
            return Some(hit);
        }
        self.packages.values().find(|p| {
            p.source.id.eq_ignore_ascii_case(query)
                || p.source.to_string().eq_ignore_ascii_case(query)
                || p.binaries()
                    .any(|b| b.link.file_name().is_some_and(|n| n == query))
        })
    }

    /// Look up the package `spec` names. `pkg@version` is not a state key, so
    /// this falls back to its alias and its source ref, which lets a request
    /// for another version still find the payload on disk.
    pub fn find_spec(&self, spec: &PackageSpec) -> Option<&InstalledPackage> {
        if let Some(pkg) = self.find(&spec.raw) {
            return Some(pkg);
        }
        if let Some(pkg) = spec.alias.as_deref().and_then(|alias| self.find(alias)) {
            return Some(pkg);
        }
        let reference = spec.reference.as_ref()?;
        self.find(&reference.to_string())
            .or_else(|| self.find(&reference.id))
    }
}

// ---------------------------------------------------------------------------
// Locking
// ---------------------------------------------------------------------------

/// Exclusive access to the install tree, released on drop.
///
/// Two `ketch install` runs writing the same `state.json` would each save a
/// view that omits the other's package, silently losing an install. Two layers
/// enforce it: a lock file excludes other ketch processes, and `HELD` excludes
/// other threads of this one. A long-running host (a GUI) calls the core
/// concurrently from one process, so a lock file naming our own pid proves
/// nothing about who inside the process holds it: only `HELD` does. Nothing
/// nests a second acquire inside a held one, so a second acquire in this
/// process is always a different operation and fails as `Error::Busy`.
pub struct Lock {
    path: PathBuf,
    /// Whether this `Lock` wrote the lock file, and so must delete it.
    wrote_file: bool,
}

/// Lock files currently held by this process.
static HELD: Mutex<Vec<PathBuf>> = Mutex::new(Vec::new());

fn held() -> std::sync::MutexGuard<'static, Vec<PathBuf>> {
    HELD.lock().unwrap_or_else(|e| e.into_inner())
}

impl Lock {
    /// Take the lock, or fail with `Error::Busy` without waiting.
    pub fn acquire(cx: &crate::report::Ctx<'_>) -> Result<Lock> {
        Self::acquire_path(&cx.cfg.lock_file, cx.report)
    }

    /// `acquire` for a lock file anywhere; `report` hears about a stale one cleared.
    pub fn acquire_path(path: &Path, report: &crate::report::Report) -> Result<Lock> {
        let parent = path.parent().unwrap_or(Path::new("."));
        std::fs::create_dir_all(parent).map_err(|e| Error::io(parent, e))?;
        let me = std::process::id();

        {
            let mut held = held();
            if held.iter().any(|p| p == path) {
                return Err(Error::Busy {
                    pid: Some(me),
                    lock: path.to_path_buf(),
                });
            }
            held.push(path.to_path_buf());
        }
        // From here `lock` releases the in-process claim on every early return.
        let mut lock = Lock {
            path: path.to_path_buf(),
            wrote_file: false,
        };

        for _ in 0..3 {
            if create_exclusive(path, me).map_err(|e| Error::io(path, e))? {
                lock.wrote_file = true;
                return Ok(lock);
            }
            match judge(path, me) {
                // Released between the two calls: try again.
                None => continue,
                Some(Holder::Live(pid)) => {
                    return Err(Error::Busy {
                        pid,
                        lock: path.to_path_buf(),
                    })
                }
                Some(Holder::Stale(pid)) => {
                    report.debug(&format!(
                        "clearing stale lock {} ({})",
                        path.display(),
                        pid.map(|p| format!("pid {p} is gone"))
                            .unwrap_or_else(|| "unreadable".into())
                    ));
                    match reclaim(path, me).map_err(|e| Error::io(path, e))? {
                        Reclaim::Taken => {
                            lock.wrote_file = true;
                            return Ok(lock);
                        }
                        Reclaim::Busy(pid) => {
                            return Err(Error::Busy {
                                pid,
                                lock: path.to_path_buf(),
                            })
                        }
                        Reclaim::Again => continue,
                    }
                }
            }
        }
        Err(Error::Busy {
            pid: None,
            lock: path.to_path_buf(),
        })
    }
}

/// An empty or unreadable lock or reclaim file younger than this belongs to
/// a process still writing its pid, not to one that crashed.
const UNREADABLE_GRACE: std::time::Duration = std::time::Duration::from_secs(10);

/// Who a lock file names, as far as this process can tell.
enum Holder {
    Live(Option<u32>),
    Stale(Option<u32>),
}

/// `None` when the file is gone.
fn judge(path: &Path, me: u32) -> Option<Holder> {
    let text = match std::fs::read_to_string(path) {
        Ok(text) => text,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return None,
        Err(_) => String::new(),
    };
    match text.trim().parse::<u32>() {
        // Our own pid with no in-process claim (the caller holds that claim)
        // is a leftover: a crashed run whose pid was reused.
        Ok(pid) if pid != me && process_alive(pid) => Some(Holder::Live(Some(pid))),
        Ok(pid) => Some(Holder::Stale(Some(pid))),
        Err(_) => {
            let age = std::fs::metadata(path)
                .and_then(|m| m.modified())
                .ok()?
                .elapsed()
                .unwrap_or_default();
            Some(if age < UNREADABLE_GRACE {
                Holder::Live(None)
            } else {
                Holder::Stale(None)
            })
        }
    }
}

/// Create `path` holding `me`, failing rather than replacing it. `false` when
/// it already exists.
///
/// The pid is written to a private file first and hard-linked into place, so
/// the file is never seen empty: with `create_new` and a write after it, a
/// second process can read the empty file in between, call it stale and take
/// the live lock away.
fn create_exclusive(path: &Path, me: u32) -> std::io::Result<bool> {
    static STAGED: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let n = STAGED.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let staged = path.with_extension(format!("new.{me}.{n}"));
    std::fs::write(&staged, me.to_string())?;
    let linked = std::fs::hard_link(&staged, path);
    let _ = std::fs::remove_file(&staged);
    match linked {
        Ok(()) => Ok(true),
        Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => Ok(false),
        // A file system without hard links (FAT, some network shares). The
        // pid then lands a moment after the file, which `UNREADABLE_GRACE`
        // covers.
        Err(_) => match std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(path)
        {
            Ok(mut file) => {
                let _ = write!(file, "{me}");
                Ok(true)
            }
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => Ok(false),
            Err(e) => Err(e),
        },
    }
}

enum Reclaim {
    Taken,
    Busy(Option<u32>),
    Again,
}

/// Replace a stale lock file with ours, as the one process allowed to.
///
/// Renaming or deleting it on the strength of an earlier read is a race: by
/// then another process may have reclaimed it and written a fresh lock, which
/// would be thrown away, and both would run. So the right to reclaim is a
/// second file, taken the same exclusive way. Only its holder removes the lock
/// file, and judges it again first; nothing else can replace a lock file that
/// exists, so what it judged is what it removes.
fn reclaim(path: &Path, me: u32) -> std::io::Result<Reclaim> {
    let right = path.with_extension("reclaim");
    if !create_exclusive(&right, me)? {
        // Held for microseconds, so one that is old as well as orphaned was
        // left by a crash mid-reclaim. Clearing it is still a race with
        // another process clearing it, which is why this attempt stops here.
        let old = std::fs::metadata(&right)
            .and_then(|m| m.modified())
            .ok()
            .and_then(|t| t.elapsed().ok())
            .is_some_and(|age| age >= UNREADABLE_GRACE);
        if old && matches!(judge(&right, me), Some(Holder::Stale(_))) {
            let _ = std::fs::remove_file(&right);
        }
        return Ok(Reclaim::Busy(None));
    }
    let outcome = match judge(path, me) {
        None => Ok(Reclaim::Again),
        Some(Holder::Live(pid)) => Ok(Reclaim::Busy(pid)),
        Some(Holder::Stale(_)) => match std::fs::remove_file(path) {
            Ok(()) => create_exclusive(path, me).map(|taken| {
                if taken {
                    Reclaim::Taken
                } else {
                    Reclaim::Again
                }
            }),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Reclaim::Again),
            Err(e) => Err(e),
        },
    };
    let _ = std::fs::remove_file(&right);
    outcome
}

impl Drop for Lock {
    fn drop(&mut self) {
        // Only while it still names us: a file another process wrote is
        // that process's to remove.
        let ours = std::fs::read_to_string(&self.path)
            .is_ok_and(|t| t.trim().parse::<u32>().ok() == Some(std::process::id()));
        if self.wrote_file && ours {
            let _ = std::fs::remove_file(&self.path);
        }
        // After the file is gone, so a waiting thread never finds the claim
        // released while the file still names us.
        held().retain(|p| p != &self.path);
    }
}

/// Is that pid still running? Only consulted when a lock file already exists,
/// so shelling out costs nothing on the normal path and keeps the crate free of
/// a libc dependency.
///
/// `ps` rather than `kill -0`: signalling a process owned by another user fails
/// with EPERM, which is indistinguishable from "no such process" through an
/// exit status alone — and reading it as "gone" steals a lock that is very much
/// still held.
#[cfg(unix)]
pub fn process_alive(pid: u32) -> bool {
    std::process::Command::new("/bin/ps")
        .arg("-p")
        .arg(pid.to_string())
        .arg("-o")
        .arg("pid=")
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(true) // Unsure means "assume held" — never steal on doubt.
}

/// `tasklist` rather than OpenProcess: keeps the crate free of a Windows-sys
/// dependency, and a missing tool still fails closed (assume held).
#[cfg(windows)]
pub fn process_alive(pid: u32) -> bool {
    let output = std::process::Command::new("tasklist")
        .args(["/FI", &format!("PID eq {pid}"), "/NH"])
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .output();
    match output {
        Ok(out) => {
            let text = String::from_utf8_lossy(&out.stdout);
            // tasklist prints "INFO: No tasks..." when the pid is gone.
            out.status.success() && text.contains(&pid.to_string())
        }
        Err(_) => true,
    }
}

#[cfg(not(any(unix, windows)))]
pub fn process_alive(_pid: u32) -> bool {
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{ManifestOrigin, PackageRef, TargetSpec, Version};

    fn pkg(name: &str) -> InstalledPackage {
        InstalledPackage {
            name: name.to_string(),
            version: Version::parse("1.0.0"),
            source: PackageRef::github("o/r"),
            tag: "v1.0.0".into(),
            target: TargetSpec::host(),
            asset_name: "a.tar.gz".into(),
            sha256: "0".repeat(64),
            checksum_verified: true,
            installed_at: 0,
            prefix: PathBuf::from("/tmp/x"),
            links: Vec::new(),
            pinned: false,
            origin: ManifestOrigin::Inferred,
            manifest: None,
            local_kind: None,
            local_path: None,
            trust: crate::model::TrustResult::default(),
            retained: Vec::new(),
            provenance: None,
            bin_choice: None,
            channel: Default::default(),
            draft: false,
            published_at: None,
        }
    }

    #[test]
    fn a_versioned_spec_finds_the_package_by_name_or_source() {
        let mut state = State::default();
        state.insert(pkg("tool"));
        for raw in ["tool", "tool@2.0.0", "o/r@2.0.0", "github:o/r"] {
            let found = state.find_spec(&PackageSpec::parse(raw));
            assert_eq!(found.map(|p| p.name.as_str()), Some("tool"), "{raw}");
        }
        assert!(state.find_spec(&PackageSpec::parse("other")).is_none());
    }

    #[test]
    fn an_empty_state_file_is_not_an_empty_install() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state.json");
        std::fs::write(&path, "").unwrap();
        assert!(State::load_path(&path).is_err(), "corruption must be loud");

        std::fs::remove_file(&path).unwrap();
        assert!(State::load_path(&path).unwrap().packages.is_empty());
    }

    #[test]
    fn a_lock_left_by_a_dead_process_is_reclaimed_without_residue() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("lock");
        // Above the pid ceiling, so it can never name a running process.
        std::fs::write(&path, "999999").unwrap();

        let lock = Lock::acquire_path(&path, &crate::report::Report::silent()).unwrap();
        assert_eq!(
            std::fs::read_to_string(&path).unwrap(),
            std::process::id().to_string()
        );
        drop(lock);

        let left: Vec<_> = std::fs::read_dir(dir.path())
            .unwrap()
            .flatten()
            .map(|e| e.file_name())
            .collect();
        assert!(left.is_empty(), "reclaim left {left:?} behind");
    }

    #[test]
    #[cfg(unix)]
    fn a_lock_held_by_another_users_process_is_not_stolen() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("lock");
        // pid 1 is running and is not ours to signal — the case that reads as
        // "process is gone" if aliveness is judged by `kill -0` alone.
        std::fs::write(&path, "1").unwrap();
        assert!(matches!(
            Lock::acquire_path(&path, &crate::report::Report::silent()),
            Err(Error::Busy { .. })
        ));
    }

    #[test]
    fn round_trips_through_disk() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state.json");
        let mut state = State::default();
        state.insert(pkg("ripgrep"));
        state.save_path(&path).unwrap();

        let loaded = State::load_path(&path).unwrap();
        assert_eq!(loaded.version, STATE_VERSION);
        assert!(loaded.get("ripgrep").is_some());
    }

    #[test]
    fn missing_file_is_an_empty_state() {
        let dir = tempfile::tempdir().unwrap();
        let state = State::load_path(&dir.path().join("nope.json")).unwrap();
        assert!(state.packages.is_empty());
    }

    #[test]
    fn refuses_a_newer_state_version() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state.json");
        std::fs::write(&path, r#"{"version":99,"packages":{}}"#).unwrap();
        assert!(State::load_path(&path).is_err());
    }

    #[test]
    fn finds_by_source_id_and_binary() {
        let mut state = State::default();
        state.insert(pkg("ripgrep"));
        assert!(state.find("o/r").is_some());
        assert!(state.find("github:o/r").is_some());
        assert!(state.find("absent").is_none());
    }

    #[test]
    fn a_v1_state_without_retention_fields_still_loads() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state.json");
        // Shape written before retained versions existed: version 1, one
        // package, no `retention`, no `trust`, no `retained`.
        std::fs::write(
            &path,
            r#"{
                "version": 1,
                "packages": {
                    "ripgrep": {
                        "name": "ripgrep",
                        "version": "14.1.0",
                        "source": "github:BurntSushi/ripgrep",
                        "tag": "14.1.0",
                        "target": {"os": "macos", "arch": "aarch64"},
                        "asset_name": "a.tar.gz",
                        "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                        "installed_at": 0,
                        "prefix": "/tmp/x",
                        "origin": "inferred"
                    }
                }
            }"#,
        )
        .unwrap();

        let loaded = State::load_path(&path).unwrap();
        assert_eq!(loaded.version, STATE_VERSION);
        assert_eq!(loaded.retention.keep, 1);
        let pkg = loaded.get("ripgrep").expect("package");
        assert!(pkg.retained.is_empty());
        assert!(pkg.trust.is_not_applicable());
        assert!(pkg.provenance.is_none());
        assert!(pkg.bin_choice.is_none());
        assert_eq!(pkg.channel, crate::model::Channel::Stable);
        assert_eq!(pkg.publisher_trust(), "first use");
        assert_eq!(pkg.version.to_string(), "14.1.0");
    }

    #[test]
    fn provenance_round_trips_and_stays_out_of_unsigned_records() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state.json");
        let signed = crate::model::Provenance {
            verifier: crate::model::Verifier::Minisign,
            identity: "minisign key RWQ".into(),
            signature: "a.tar.gz.minisig".into(),
            signature_sha256: "1".repeat(64),
            signed: Some("SHA256SUMS".into()),
            log_index: None,
        };
        let mut state = State::default();
        state.insert(InstalledPackage {
            provenance: Some(signed.clone()),
            ..pkg("signed")
        });
        state.insert(pkg("plain"));
        state.save_path(&path).unwrap();

        let written = std::fs::read_to_string(&path).unwrap();
        assert_eq!(written.matches("\"provenance\"").count(), 1, "{written}");
        let loaded = State::load_path(&path).unwrap();
        assert_eq!(loaded.get("signed").unwrap().provenance, Some(signed));
        assert_eq!(loaded.get("signed").unwrap().publisher_trust(), "signed");
        assert!(loaded.get("plain").unwrap().provenance.is_none());
    }

    #[test]
    fn a_bin_choice_round_trips_and_stays_out_of_records_without_one() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state.json");
        let mut state = State::default();
        state.insert(InstalledPackage {
            bin_choice: Some("rtok-cli".into()),
            ..pkg("chosen")
        });
        state.insert(pkg("plain"));
        state.save_path(&path).unwrap();

        let written = std::fs::read_to_string(&path).unwrap();
        assert_eq!(written.matches("\"bin_choice\"").count(), 1, "{written}");
        let loaded = State::load_path(&path).unwrap();
        assert_eq!(
            loaded.get("chosen").unwrap().bin_choice.as_deref(),
            Some("rtok-cli")
        );
        assert!(loaded.get("plain").unwrap().bin_choice.is_none());
    }

    #[test]
    fn a_v1_link_without_a_role_still_reads_as_a_binary() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state.json");
        std::fs::write(
            &path,
            r#"{
                "version": 1,
                "packages": {
                    "ripgrep": {
                        "name": "ripgrep",
                        "version": "14.1.0",
                        "source": "github:BurntSushi/ripgrep",
                        "tag": "14.1.0",
                        "target": {"os": "macos", "arch": "aarch64"},
                        "asset_name": "a.tar.gz",
                        "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                        "installed_at": 0,
                        "prefix": "/tmp/x",
                        "origin": "inferred",
                        "links": [
                            {
                                "link": "/tmp/bin/rg",
                                "target": "/tmp/x/rg",
                                "kind": "symlink"
                            }
                        ]
                    }
                }
            }"#,
        )
        .unwrap();
        let loaded = State::load_path(&path).unwrap();
        let pkg = loaded.get("ripgrep").expect("package");
        assert_eq!(pkg.links.len(), 1);
        assert_eq!(pkg.links[0].role, crate::model::LinkRole::Binary);
        assert_eq!(pkg.binaries().count(), 1);
    }

    #[test]
    fn a_second_lock_in_this_process_is_busy_until_the_first_drops() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join(".lock");
        let held = Lock::acquire_path(&path, &crate::report::Report::silent()).unwrap();
        assert!(path.exists());
        match Lock::acquire_path(&path, &crate::report::Report::silent()) {
            Err(Error::Busy { pid, .. }) => assert_eq!(pid, Some(std::process::id())),
            other => panic!("expected Busy, got {:?}", other.map(|_| ())),
        }
        // The refused attempt must not have released the holder's lock file.
        assert!(path.exists());
        drop(held);
        assert!(!path.exists());
        drop(Lock::acquire_path(&path, &crate::report::Report::silent()).unwrap());
    }

    #[test]
    fn a_lock_held_under_one_path_does_not_block_another_path() {
        let dir = tempfile::tempdir().unwrap();
        let _a =
            Lock::acquire_path(&dir.path().join("a"), &crate::report::Report::silent()).unwrap();
        let _b =
            Lock::acquire_path(&dir.path().join("b"), &crate::report::Report::silent()).unwrap();
    }

    #[test]
    fn a_failed_acquire_leaves_no_claim_behind() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("lock");
        #[cfg(unix)]
        {
            // A live foreign holder: busy, and must not poison later attempts.
            std::fs::write(&path, "1").unwrap();
            assert!(Lock::acquire_path(&path, &crate::report::Report::silent()).is_err());
            std::fs::remove_file(&path).unwrap();
        }
        drop(Lock::acquire_path(&path, &crate::report::Report::silent()).unwrap());
    }

    #[test]
    fn a_lock_file_naming_this_process_without_a_holder_is_stale() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("lock");
        std::fs::write(&path, std::process::id().to_string()).unwrap();
        drop(Lock::acquire_path(&path, &crate::report::Report::silent()).unwrap());
        assert!(!path.exists());
    }

    #[test]
    fn a_held_lock_can_be_contended_from_another_thread() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("lock");
        let _held = Lock::acquire_path(&path, &crate::report::Report::silent()).unwrap();
        let other = std::thread::spawn(move || {
            Lock::acquire_path(&path, &crate::report::Report::silent()).map(|_| ())
        })
        .join()
        .unwrap();
        assert!(matches!(other, Err(Error::Busy { .. })));
    }

    #[test]
    fn a_pre_channel_round_trips_and_stable_stays_out_of_the_file() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("state.json");
        let mut state = State::default();
        state.insert(InstalledPackage {
            channel: crate::model::Channel::Pre,
            ..pkg("beta")
        });
        state.insert(pkg("plain"));
        state.save_path(&path).unwrap();

        let written = std::fs::read_to_string(&path).unwrap();
        assert_eq!(written.matches("\"channel\"").count(), 1, "{written}");
        let loaded = State::load_path(&path).unwrap();
        assert_eq!(
            loaded.get("beta").unwrap().channel,
            crate::model::Channel::Pre
        );
        assert_eq!(
            loaded.get("plain").unwrap().channel,
            crate::model::Channel::Stable
        );
    }

    #[test]
    fn an_empty_lock_file_is_busy_until_it_is_old() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("lock");
        // What a holder that has created the file but not yet written its
        // pid looks like from outside.
        let file = std::fs::File::create(&path).unwrap();
        assert!(matches!(
            Lock::acquire_path(&path, &crate::report::Report::silent()),
            Err(Error::Busy { pid: None, .. })
        ));
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "");

        let old = std::time::SystemTime::now() - UNREADABLE_GRACE * 2;
        file.set_modified(old).unwrap();
        drop(file);
        drop(Lock::acquire_path(&path, &crate::report::Report::silent()).unwrap());
        assert!(!path.exists());
    }

    #[test]
    fn a_late_reclaim_never_takes_a_fresh_lock_away() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("lock");
        std::fs::write(&path, "999999").unwrap();
        // This process reclaims the dead pid's lock first...
        let held = Lock::acquire_path(&path, &crate::report::Report::silent()).unwrap();
        // ...while another, which judged the same file stale a moment
        // earlier, reclaims now.
        let other_pid = 999_998;
        match reclaim(&path, other_pid).unwrap() {
            Reclaim::Busy(pid) => assert_eq!(pid, Some(std::process::id())),
            _ => panic!("a live lock was reclaimed"),
        }
        assert_eq!(
            std::fs::read_to_string(&path).unwrap(),
            std::process::id().to_string()
        );
        drop(held);
        assert!(!path.exists());
    }

    #[test]
    fn dropping_a_lock_leaves_a_file_another_process_wrote() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("lock");
        let held = Lock::acquire_path(&path, &crate::report::Report::silent()).unwrap();
        std::fs::remove_file(&path).unwrap();
        std::fs::write(&path, "1").unwrap();
        drop(held);
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "1");
    }

    /// Run by [`processes_never_hold_the_lock_together`] in child processes;
    /// a plain test run finds no variable and does nothing.
    #[test]
    fn lock_contender() {
        let Some(dir) = std::env::var_os("KETCH_TEST_LOCK_CONTENDER").map(PathBuf::from) else {
            return;
        };
        let lock = dir.join("lock");
        let inside = dir.join("inside");
        for round in 0..150 {
            let Ok(held) = Lock::acquire_path(&lock, &crate::report::Report::silent()) else {
                continue;
            };
            if std::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&inside)
                .is_err()
            {
                std::fs::write(dir.join(format!("overlap.{}", std::process::id())), "").unwrap();
            }
            std::thread::sleep(std::time::Duration::from_micros(300));
            let _ = std::fs::remove_file(&inside);
            if round % 7 == 0 {
                // A crash: the file stays behind naming a pid that is gone,
                // and every other contender judges it stale at once.
                std::fs::write(&lock, "999999").unwrap();
                std::mem::forget(held);
                held_release(&lock);
            }
        }
    }

    /// What a crashed process leaves: no claim in `HELD`, the file on disk.
    fn held_release(path: &Path) {
        held().retain(|p| p != path);
    }

    #[test]
    fn processes_never_hold_the_lock_together() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("lock"), "999999").unwrap();
        let exe = std::env::current_exe().unwrap();
        let children: Vec<_> = (0..6)
            .map(|_| {
                std::process::Command::new(&exe)
                    .args(["--exact", "state::tests::lock_contender", "--nocapture"])
                    .env("KETCH_TEST_LOCK_CONTENDER", dir.path())
                    .stdout(std::process::Stdio::null())
                    .stderr(std::process::Stdio::null())
                    .spawn()
                    .unwrap()
            })
            .collect();
        for mut child in children {
            assert!(child.wait().unwrap().success());
        }
        let overlaps: Vec<_> = std::fs::read_dir(dir.path())
            .unwrap()
            .flatten()
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .filter(|n| n.starts_with("overlap."))
            .collect();
        assert!(
            overlaps.is_empty(),
            "two processes held the lock: {overlaps:?}"
        );
    }
}
