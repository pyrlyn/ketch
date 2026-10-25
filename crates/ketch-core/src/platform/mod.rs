// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Per-operating-system behaviour.
//!
//! Everything that differs between macOS, Linux and Windows lives behind this
//! trait: which release asset is even installable, how a payload becomes
//! something on PATH, and what "is this code trustworthy" means locally.
//!
//! macOS, Linux and Windows each have a backend. Adding another OS means adding
//! a file here and one arm in `host()` — no changes anywhere else. Asking which
//! asset another OS would choose does not need that backend: those modules are
//! compiled only on their own host, and [`for_target`] scores with the shared
//! rules without placing anything.

pub mod scoring;

#[cfg(any(unix, windows))]
pub(crate) mod claim;

#[cfg(target_os = "linux")]
pub mod linux;
#[cfg(target_os = "macos")]
pub mod macos;
#[cfg(unix)]
pub mod unix;
#[cfg(target_os = "windows")]
pub mod windows;

use crate::config::Config;
use crate::error::{Error, Result};
use crate::extra::ExtraPlacement;
use crate::model::{
    Arch, AskedTarget, BinSpec, CompletionShell, LinkRecord, Os, PackageKind, TargetSpec,
};
use std::path::{Path, PathBuf};
use std::sync::Arc;

/// Why an asset was chosen, and at what cost.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AssetScore {
    /// Higher wins. Only compared between assets of the same release.
    pub score: i32,
    /// Architecture this asset actually provides.
    pub arch: Arch,
    /// True when it runs only under emulation (x86_64 on Apple Silicon).
    pub emulated: bool,
    /// Short explanation, shown with `--verbose` and in `ketch info`.
    pub reason: String,
}

/// Everything the platform needs to place an extracted payload.
pub struct Placement<'a> {
    pub name: &'a str,
    /// Directory holding the extracted release payload.
    pub payload_dir: &'a Path,
    /// Final home of this version inside the store.
    pub store_dir: &'a Path,
    pub bin_dir: &'a Path,
    /// macOS `.app` install root; unused on Linux/Windows placement.
    #[cfg_attr(not(target_os = "macos"), allow(dead_code))]
    pub apps_dir: &'a Path,
    pub kind: PackageKind,
    /// Explicit binaries from the manifest. Empty means "discover them".
    pub bin_specs: &'a [BinSpec],
    /// Links recorded for the version being replaced, which still exist:
    /// placement runs before the old version is retired. A destination listed
    /// here is ketch's own to overwrite. Anything else occupying a destination
    /// belongs to another package or to the user.
    pub replacing: &'a [LinkRecord],
    /// Symlink `.app` bundles rather than copying them.
    #[cfg_attr(not(target_os = "macos"), allow(dead_code))]
    pub link_apps: bool,
    /// Create user-visible links. False still moves the payload into the
    /// store, so `ketch relink` can expose it later without re-downloading.
    pub link: bool,
    /// Man pages and completions already classified and given destinations.
    pub extras: &'a [ExtraPlacement],
}

/// Result of a local trust check on downloaded code.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TrustVerdict {
    /// Validly signed and accepted by the system policy.
    #[cfg_attr(not(target_os = "macos"), allow(dead_code))]
    Trusted { authority: String },
    /// Signed, but the system would still warn (ad-hoc, or unnotarized).
    #[cfg_attr(not(target_os = "macos"), allow(dead_code))]
    Weak { detail: String },
    /// No usable signature.
    #[cfg_attr(not(target_os = "macos"), allow(dead_code))]
    Untrusted { detail: String },
    /// This platform does not do signature checks.
    NotApplicable,
}

impl TrustVerdict {
    /// Whether it is safe to remove the quarantine flag without silently
    /// disabling a protection the user is relying on.
    pub fn may_strip_quarantine(&self) -> bool {
        matches!(self, TrustVerdict::Trusted { .. })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CheckStatus {
    Ok,
    Warn,
    Fail,
}

/// One line of `ketch doctor` output.
#[derive(Debug, Clone)]
pub struct DoctorCheck {
    pub name: String,
    pub status: CheckStatus,
    pub detail: String,
    pub fix: Option<String>,
}

impl DoctorCheck {
    pub fn ok(name: impl Into<String>, detail: impl Into<String>) -> Self {
        DoctorCheck {
            name: name.into(),
            status: CheckStatus::Ok,
            detail: detail.into(),
            fix: None,
        }
    }
    pub fn warn(
        name: impl Into<String>,
        detail: impl Into<String>,
        fix: impl Into<String>,
    ) -> Self {
        DoctorCheck {
            name: name.into(),
            status: CheckStatus::Warn,
            detail: detail.into(),
            fix: Some(fix.into()),
        }
    }
    pub fn fail(
        name: impl Into<String>,
        detail: impl Into<String>,
        fix: impl Into<String>,
    ) -> Self {
        DoctorCheck {
            name: name.into(),
            status: CheckStatus::Fail,
            detail: detail.into(),
            fix: Some(fix.into()),
        }
    }
}

/// Present so `ketch doctor` can colour a summary without re-deriving it.
pub fn worst_status(checks: &[DoctorCheck]) -> CheckStatus {
    if checks.iter().any(|c| c.status == CheckStatus::Fail) {
        CheckStatus::Fail
    } else if checks.iter().any(|c| c.status == CheckStatus::Warn) {
        CheckStatus::Warn
    } else {
        CheckStatus::Ok
    }
}

/// The host operating system's rules.
pub trait Platform: Send + Sync {
    /// Stable identifier, e.g. `macos`.
    #[allow(dead_code)]
    fn id(&self) -> &str;

    fn target(&self) -> TargetSpec;

    /// Rate an asset by file name alone.
    ///
    /// `None` means "cannot run here" and the asset is discarded. This is the
    /// single most important function for install quality: it is what stops
    /// ketch grabbing a Linux tarball or a `.sha256` sidecar.
    fn score_asset(&self, asset_name: &str, allow_emulation: bool) -> Option<AssetScore>;

    /// Extractors this platform can use, most specific first.
    fn extractors(&self) -> Vec<Box<dyn crate::extract::Extractor>>;

    /// Move the payload into the store and create user-visible links.
    fn place(&self, plan: &Placement<'_>) -> Result<Vec<LinkRecord>>;

    /// The executables `place` would discover and link in `payload` when the
    /// manifest names none, in discovery order. The file named `package` leads.
    /// Install asks before placing, so that a choice between them is made once,
    /// by `bin_choice`, and not by whichever order this platform happens to
    /// sort them in.
    fn bin_candidates(&self, _payload: &Path, _kind: PackageKind, _package: &str) -> Vec<PathBuf> {
        Vec::new()
    }

    /// Undo `place`. Must tolerate links that are already gone, and leaves a
    /// link that is no longer ketch's, saying so on `report`.
    fn unplace(&self, links: &[LinkRecord], report: &crate::report::Report) -> Result<()>;

    /// Inspect downloaded code before it is exposed to the user.
    fn verify_trust(&self, _path: &Path) -> Result<TrustVerdict> {
        Ok(TrustVerdict::NotApplicable)
    }

    /// Clear the OS "downloaded from the internet" mark. Only called when the
    /// trust verdict allows it.
    fn clear_quarantine(&self, _path: &Path) -> Result<()> {
        Ok(())
    }

    /// Is this file something we can execute and link onto PATH?
    fn is_executable(&self, path: &Path) -> bool;

    /// Files this platform treats as app bundles rather than executables.
    #[allow(dead_code)]
    fn app_bundle_extension(&self) -> Option<&str> {
        None
    }

    /// Environment checks for `ketch doctor`.
    fn doctor(&self, cfg: &Config) -> Vec<DoctorCheck>;

    /// User-writable man root. Pages go in `manN/` underneath.
    ///
    /// Defaults to `$XDG_DATA_HOME/man` or `~/.local/share/man`, not
    /// `dirs::data_dir()`, which on macOS is Application Support — a place
    /// `man` never looks.
    fn user_man_root(&self) -> PathBuf {
        data_home().join("man")
    }

    /// Directory this shell searches for user completion scripts.
    fn completion_dir(&self, shell: CompletionShell) -> PathBuf {
        completion_dir_for(shell)
    }
}

/// `$XDG_DATA_HOME`, falling back to `~/.local/share` on every OS.
pub fn data_home() -> PathBuf {
    std::env::var_os("XDG_DATA_HOME")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            dirs::home_dir()
                .unwrap_or_else(|| PathBuf::from("."))
                .join(".local/share")
        })
}

/// `$XDG_CONFIG_HOME`, falling back to `~/.config`.
pub fn config_home() -> PathBuf {
    std::env::var_os("XDG_CONFIG_HOME")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            dirs::home_dir()
                .unwrap_or_else(|| PathBuf::from("."))
                .join(".config")
        })
}

/// True when `path` is `root` or a descendant of it.
///
/// On Windows the filesystem is case-insensitive but `Path::starts_with` is
/// not: a store root typed `C:\Users\IVAN\…` and a payload recorded as
/// `C:\Users\ivan\…` must still count as inside, or uninstall/GC refuses to
/// delete the files and leaves orphans forever.
pub fn path_is_within(path: &Path, root: &Path) -> bool {
    if root.as_os_str().is_empty() {
        return false;
    }
    if path.starts_with(root) {
        return true;
    }
    cfg!(windows) && path_is_within_ascii_case_insensitive(path, root)
}

/// Like [`path_is_within`], but false when `path` and `root` are the same.
pub fn path_is_strict_within(path: &Path, root: &Path) -> bool {
    path_is_within(path, root) && path != root && !paths_eq_ascii_case(path, root)
}

fn paths_eq_ascii_case(a: &Path, b: &Path) -> bool {
    if a == b {
        return true;
    }
    if !cfg!(windows) {
        return false;
    }
    let a: Vec<_> = a.components().collect();
    let b: Vec<_> = b.components().collect();
    a.len() == b.len()
        && a.iter()
            .zip(b.iter())
            .all(|(x, y)| components_eq_ascii_case(x, y))
}

fn path_is_within_ascii_case_insensitive(path: &Path, root: &Path) -> bool {
    let path_c: Vec<_> = path.components().collect();
    let root_c: Vec<_> = root.components().collect();
    if root_c.is_empty() || root_c.len() > path_c.len() {
        return false;
    }
    path_c
        .iter()
        .zip(root_c.iter())
        .all(|(p, r)| components_eq_ascii_case(p, r))
}

fn components_eq_ascii_case(a: &std::path::Component<'_>, b: &std::path::Component<'_>) -> bool {
    use std::path::Component;
    match (a, b) {
        (Component::Normal(x), Component::Normal(y)) => x.eq_ignore_ascii_case(y),
        (Component::Prefix(x), Component::Prefix(y)) => {
            x.as_os_str().eq_ignore_ascii_case(y.as_os_str())
        }
        (x, y) => x == y,
    }
}

/// Unix-style completion directories. Windows overrides PowerShell.
pub fn completion_dir_for(shell: CompletionShell) -> PathBuf {
    match shell {
        CompletionShell::Bash => data_home().join("bash-completion/completions"),
        CompletionShell::Zsh => data_home().join("zsh/site-functions"),
        CompletionShell::Fish => config_home().join("fish/completions"),
        CompletionShell::Elvish => config_home().join("elvish/lib"),
        CompletionShell::Powershell => config_home().join("powershell/Completions"),
    }
}

/// Doctor lines for the destinations `place` will write. Missing directories
/// are reported as ok: install creates them rather than surprising the user
/// with a write they were not shown.
pub fn extra_destination_checks(platform: &dyn Platform) -> Vec<DoctorCheck> {
    let mut checks = vec![dest_check("man", &platform.user_man_root(), true)];
    for shell in CompletionShell::ALL {
        checks.push(dest_check(
            &format!("completions-{}", shell.as_str()),
            &platform.completion_dir(shell),
            false,
        ));
    }
    checks
}

fn dest_check(name: &str, path: &Path, mention_manpath: bool) -> DoctorCheck {
    let mut detail = path.display().to_string();
    if !path.exists() {
        detail.push_str(" — created on install");
    }
    if mention_manpath {
        detail.push_str("; add this directory to MANPATH so `man` finds pages ketch installs");
    }
    if path.exists() && probe_writable(path).is_err() {
        return DoctorCheck::fail(
            name,
            format!("{} is not writable", path.display()),
            format!("chmod u+w {}", path.display()),
        );
    }
    DoctorCheck::ok(name, detail)
}

fn probe_writable(dir: &Path) -> std::result::Result<(), String> {
    tempfile::Builder::new()
        .prefix(".ketch-probe")
        .tempfile_in(dir)
        .map(|_| ())
        .map_err(|e| e.to_string())
}

/// Link classified extras from `root` (the store prefix) to their dests.
pub fn expose_extras(
    extras: &[ExtraPlacement],
    root: &Path,
    owned: &Path,
    recorded: &[LinkRecord],
) -> Result<Vec<LinkRecord>> {
    #[cfg(any(unix, windows))]
    {
        let mut claims = claim::Claims::default();
        #[cfg(unix)]
        let linked = unix::link_planned_extras(extras, root, owned, recorded, &mut claims);
        #[cfg(windows)]
        let linked = {
            let _ = owned;
            windows::link_planned_extras(extras, root, recorded, &mut claims)
        };
        match linked {
            Ok(links) => {
                claims.discard();
                Ok(links)
            }
            Err(e) => {
                claims.restore();
                Err(e)
            }
        }
    }
    #[cfg(not(any(unix, windows)))]
    {
        let _ = (extras, root, owned, recorded);
        Ok(Vec::new())
    }
}

/// Scores assets for a platform this process may not be running on.
///
/// The real backends are `cfg`-gated to their own OS, so a Mac cannot
/// construct `LinuxPlatform` to answer `ketch why --target linux-x86_64`.
/// Scoring is shared and runs everywhere. Placement is refused: installing
/// still goes through [`host`], whose link and copy rules are the ones that
/// machine can actually perform.
struct CrossTarget {
    spec: TargetSpec,
    musl: bool,
}

impl Platform for CrossTarget {
    fn id(&self) -> &str {
        match self.spec.os {
            Os::MacOs => "macos",
            Os::Linux => "linux",
            Os::Windows => "windows",
        }
    }

    fn target(&self) -> TargetSpec {
        self.spec
    }

    fn score_asset(&self, asset_name: &str, allow_emulation: bool) -> Option<AssetScore> {
        match self.spec.os {
            Os::MacOs => scoring::score_macos_asset(asset_name, self.spec.arch, allow_emulation),
            Os::Linux => {
                scoring::score_linux_asset(asset_name, self.spec.arch, allow_emulation, self.musl)
            }
            Os::Windows => scoring::score_windows_asset(asset_name, self.spec.arch),
        }
    }

    fn extractors(&self) -> Vec<Box<dyn crate::extract::Extractor>> {
        Vec::new()
    }

    fn place(&self, _plan: &Placement<'_>) -> Result<Vec<LinkRecord>> {
        Err(Error::msg(
            "resolving for another target does not install; it only chooses an asset",
        ))
    }

    fn unplace(&self, _links: &[LinkRecord], _report: &crate::report::Report) -> Result<()> {
        Err(Error::msg(
            "resolving for another target does not install; it only chooses an asset",
        ))
    }

    fn is_executable(&self, _path: &Path) -> bool {
        false
    }

    fn doctor(&self, _cfg: &Config) -> Vec<DoctorCheck> {
        Vec::new()
    }
}

/// A platform that only scores assets for `asked`.
///
/// Use this for `ketch why --target` and `ketch info --assets --target`.
/// Install keeps using [`host`]: a foreign target is a question about an
/// asset name, not a request to link one.
pub fn for_target(asked: AskedTarget) -> Arc<dyn Platform> {
    Arc::new(CrossTarget {
        spec: asked.spec,
        musl: asked.musl_libc(),
    })
}

/// The platform for the machine we are on.
///
/// Unsupported hosts fail here with one clear message rather than misbehaving
/// deeper in the install pipeline.
pub fn host() -> Result<Arc<dyn Platform>> {
    #[cfg(target_os = "macos")]
    {
        Ok(Arc::new(macos::MacOsPlatform::new()))
    }
    #[cfg(target_os = "linux")]
    {
        Ok(Arc::new(linux::LinuxPlatform::new()))
    }
    #[cfg(target_os = "windows")]
    {
        Ok(Arc::new(windows::WindowsPlatform::new()))
    }
    #[cfg(not(any(target_os = "macos", target_os = "linux", target_os = "windows")))]
    {
        Err(crate::error::Error::msg(format!(
            "ketch {} has no backend for this operating system. \
             Implementing `Platform` in src/platform/ is all that is required.",
            env!("CARGO_PKG_VERSION")
        )))
    }
}

/// Tokens in an asset name that mean "this is not a program".
///
/// Shared by every platform, because signature and checksum sidecars look the
/// same everywhere.
pub const SIDECAR_SUFFIXES: &[&str] = &[
    ".sha256",
    ".sha512",
    ".sha1",
    ".md5",
    ".asc",
    ".sig",
    ".sigstore",
    ".pem",
    ".crt",
    ".sbom",
    ".sbom.json",
    ".spdx.json",
    ".intoto.jsonl",
    ".pubkey",
    ".minisig",
    ".cert",
];

/// Substrings that mark a file as source code or metadata, not a build.
pub const NON_BINARY_TOKENS: &[&str] = &[
    "checksum",
    "checksums",
    "sha256sums",
    "sha512sums",
    "source-code",
    "sources",
    "src.tar",
    "src",
    "vendor",
    "manifest",
    "provenance",
    "attestation",
    "changelog",
    "release-notes",
];

/// Filename tokens for operating systems ketch does not support.
///
/// An asset naming one of these is never installable on macOS, Linux or
/// Windows — even when it also carries a recognised architecture token.
pub const FOREIGN_OS_TOKENS: &[&str] = &["freebsd", "netbsd", "openbsd", "plan9", "dragonfly"];

/// Extensions that never contain a runnable macOS/Linux payload.
pub const REJECTED_EXTENSIONS: &[&str] = &[
    ".txt",
    ".md",
    ".json",
    ".yaml",
    ".yml",
    ".xml",
    ".csv",
    ".log",
    ".deb",
    ".rpm",
    ".apk",
    ".msi",
    ".appimage",
    ".snap",
    ".flatpak",
    ".nupkg",
    ".jar",
    ".war",
    ".whl",
    ".gem",
];

/// True when `name` ends with any known sidecar suffix.
pub fn is_sidecar(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    SIDECAR_SUFFIXES.iter().any(|s| lower.ends_with(s))
}

/// File name compared as a package binary: a trailing `.exe` folded away, ASCII
/// case folded.
///
/// Only `.exe`. That suffix is what puts `rtok-hook.exe` ahead of `rtok.exe`
/// in a plain sort. Stripping every extension would also treat `rtok.cmd` as
/// the package binary.
fn executable_name_key(name: &std::ffi::OsStr) -> String {
    let lower = name.to_string_lossy().to_ascii_lowercase();
    lower.strip_suffix(".exe").unwrap_or(&lower).to_string()
}

/// Order discovered executables so the package's own binary comes first.
///
/// A plain sort is not that order. `rtok` sorts before `rtok-hook`, but
/// `rtok-hook.exe` sorts before `rtok.exe` (`-` before `.`), so Windows
/// handed the hook to whoever took the first name. The binary whose name
/// equals the package name wins (`.exe` ignored, ASCII case folded). Everything
/// else keeps alphabetical order, so the result does not depend on directory
/// listing order.
pub(crate) fn order_discovered_executables(found: &mut [PathBuf], package: &str) {
    let want = executable_name_key(std::ffi::OsStr::new(package));
    found.sort_by(|a, b| {
        let exact = |path: &Path| {
            path.file_name()
                .is_some_and(|name| executable_name_key(name) == want)
        };
        // `true` sorts after `false`; compare the other way so the match leads.
        exact(b).cmp(&exact(a)).then_with(|| a.cmp(b))
    });
}

/// Whether `candidate` is an aside the Windows backend made of a file named
/// `link_name`: `<link_name>.old.<pid>-<millis>`, the name a running `.exe` is
/// renamed to so its replacement can be copied in.
///
/// Matched this narrowly because whatever matches is deleted: a user's own
/// `tool.exe.old.backup` beside the bin copy is not ketch's. Case-insensitive
/// in the name, as the Windows file system is. Here rather than in the Windows
/// backend so the rule is tested on every host.
#[cfg_attr(not(windows), allow(dead_code))]
pub(crate) fn is_busy_aside_of(link_name: &str, candidate: &str) -> bool {
    let named = candidate
        .get(..link_name.len())
        .is_some_and(|head| head.eq_ignore_ascii_case(link_name));
    let Some(rest) = candidate
        .get(link_name.len()..)
        .filter(|_| named)
        .and_then(|rest| rest.strip_prefix(".old."))
    else {
        return false;
    };
    let digits = |s: &str| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit());
    rest.split_once('-')
        .is_some_and(|(pid, stamp)| digits(pid) && digits(stamp))
}

/// A path next to `original` whose last component is that name plus `suffix`.
///
/// Store swaps rename `.incoming` and `.old` beside the live directory, which
/// stays on one filesystem. One helper serves every backend: the suffix is
/// appended as bytes, so a non-UTF-8 name is not rewritten, and a path with no
/// file name (the root) still gets a name.
pub(crate) fn sibling(original: &Path, suffix: &str) -> PathBuf {
    let mut name = original
        .file_name()
        .map(|n| n.to_os_string())
        .unwrap_or_else(|| std::ffi::OsString::from("pkg"));
    name.push(suffix);
    original.with_file_name(name)
}

/// Move `payload` to `store`, retiring a previous tree to `.old`.
///
/// The replacement is assembled beside the destination and swapped in last.
/// Deleting the old directory first would mean an upgrade that fails while
/// copying leaves no working version. `copy` runs only when the cache and the
/// store are on different filesystems and a rename cannot cross them.
///
/// Returns true when it retired a previous tree. Only then may the caller
/// finalize or restore: a `.old` found otherwise is an earlier run's leftover,
/// and restoring it would replace the live prefix with it. `.old` stays until
/// links succeed, so a failed place can put the working version back.
pub(crate) fn swap_into_store(
    payload: &Path,
    store: &Path,
    copy: impl FnOnce(&Path, &Path) -> Result<()>,
) -> Result<bool> {
    // `relink` re-runs placement over a payload that is already in the store;
    // without this the swap below would move it out from under itself.
    if payload == store {
        return Ok(false);
    }
    if let Some(parent) = store.parent() {
        std::fs::create_dir_all(parent).map_err(|e| Error::io(parent, e))?;
    }

    let staged = sibling(store, ".incoming");
    let _ = fs_kit::tree::remove_any(&staged);
    if std::fs::rename(payload, &staged).is_err() {
        copy(payload, &staged)?;
    }

    if store.symlink_metadata().is_err() {
        return std::fs::rename(&staged, store)
            .map(|()| false)
            .map_err(|e| Error::io(store, e));
    }
    let retired = sibling(store, ".old");
    let _ = fs_kit::tree::remove_any(&retired);
    std::fs::rename(store, &retired).map_err(|e| Error::io(store, e))?;
    if let Err(e) = std::fs::rename(&staged, store) {
        let _ = std::fs::rename(&retired, store);
        let _ = fs_kit::tree::remove_any(&staged);
        return Err(Error::io(store, e));
    }
    Ok(true)
}

/// Drop the retired previous version once placement has finished successfully.
pub(crate) fn finalize_store_swap(store: &Path) {
    let _ = fs_kit::tree::remove_any(&sibling(store, ".old"));
}

/// Put the version this call swapped out back, after place failed on the new tree.
pub(crate) fn restore_retired_store(store: &Path) {
    let retired = sibling(store, ".old");
    if retired.symlink_metadata().is_ok() {
        let _ = fs_kit::tree::remove_any(store);
        let _ = std::fs::rename(&retired, store);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sibling_appends_the_suffix_to_the_file_name() {
        let path = PathBuf::from("store").join("tool").join("1.0");
        assert_eq!(
            sibling(&path, ".old"),
            PathBuf::from("store").join("tool").join("1.0.old")
        );
        assert_eq!(
            sibling(&path, ".incoming"),
            PathBuf::from("store").join("tool").join("1.0.incoming")
        );
    }

    #[test]
    fn sibling_of_a_nameless_path_uses_pkg() {
        assert_eq!(sibling(Path::new(""), ".old"), PathBuf::from("pkg.old"));
    }

    #[test]
    fn swap_into_store_of_the_same_path_does_not_retire_it() {
        let tmp = tempfile::tempdir().unwrap();
        let store = tmp.path().join("store");
        std::fs::create_dir_all(&store).unwrap();
        std::fs::write(store.join("tool"), b"working").unwrap();
        let copied = std::cell::Cell::new(false);
        let swapped = swap_into_store(&store, &store, |_, _| {
            copied.set(true);
            Ok(())
        })
        .unwrap();
        assert!(!swapped);
        assert!(!copied.get());
        assert_eq!(std::fs::read(store.join("tool")).unwrap(), b"working");
    }

    #[test]
    fn swap_into_store_retires_the_previous_tree_and_restore_puts_it_back() {
        let tmp = tempfile::tempdir().unwrap();
        let store = tmp.path().join("store").join("tool").join("1.0");
        std::fs::create_dir_all(&store).unwrap();
        std::fs::write(store.join("tool"), b"working").unwrap();
        let payload = tmp.path().join("payload");
        std::fs::create_dir_all(&payload).unwrap();
        std::fs::write(payload.join("tool"), b"new").unwrap();
        assert!(swap_into_store(&payload, &store, |_, _| {
            Err(Error::msg("rename should stay on one filesystem"))
        })
        .unwrap());
        assert_eq!(std::fs::read(store.join("tool")).unwrap(), b"new");
        assert_eq!(
            std::fs::read(sibling(&store, ".old").join("tool")).unwrap(),
            b"working"
        );
        restore_retired_store(&store);
        assert_eq!(std::fs::read(store.join("tool")).unwrap(), b"working");
        assert!(!sibling(&store, ".old").exists());
        finalize_store_swap(&store);
    }

    #[test]
    fn swap_into_store_copies_when_the_rename_onto_the_stage_fails() {
        let tmp = tempfile::tempdir().unwrap();
        let store = tmp.path().join("store").join("1.0");
        let payload = tmp.path().join("missing-payload");
        // A missing source fails the rename, which is the same branch a
        // cross-filesystem rename takes before `copy` runs.
        let swapped = swap_into_store(&payload, &store, |from, to| {
            assert_eq!(from, payload);
            std::fs::create_dir(to).map_err(|e| Error::io(to, e))?;
            std::fs::write(to.join("tool"), b"copied").map_err(|e| Error::io(to, e))?;
            Ok(())
        })
        .unwrap();
        assert!(!swapped);
        assert_eq!(std::fs::read(store.join("tool")).unwrap(), b"copied");
    }

    #[cfg(unix)]
    #[test]
    fn sibling_keeps_a_non_utf8_file_name() {
        use std::ffi::OsStr;
        use std::os::unix::ffi::OsStrExt;
        let name = OsStr::from_bytes(&[0xff, 0xfe]);
        let path = PathBuf::from("store").join(name);
        let got = sibling(&path, ".old");
        let mut expected = name.to_os_string();
        expected.push(".old");
        assert_eq!(got.file_name(), Some(expected.as_os_str()));
    }

    #[test]
    fn only_a_pid_and_time_stamped_aside_of_the_link_is_one() {
        for ours in ["tool.exe.old.4242-1700000000000", "Tool.EXE.old.1-2"] {
            assert!(is_busy_aside_of("tool.exe", ours), "{ours}");
        }
        for theirs in [
            "tool.exe",
            "tool.exe.old",
            "tool.exe.old.",
            "tool.exe.old.backup",
            "tool.exe.old.12-",
            "tool.exe.old.-12",
            "tool.exe.old.1-2-3",
            "tool.exe.old.1-2x",
            "other.exe.old.1-2",
            "xtool.exe.old.1-2",
        ] {
            assert!(!is_busy_aside_of("tool.exe", theirs), "{theirs}");
        }
    }

    fn write_executable(dir: &Path, name: &str) {
        let path = dir.join(name);
        // A shebang counts as a program on Unix; the `.exe` suffix is enough
        // on Windows. The same bytes work for both.
        std::fs::write(&path, b"#!/bin/sh\n").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();
        }
    }

    #[test]
    fn a_windows_style_exe_pair_chooses_the_package_name_over_the_hook() {
        let tmp = tempfile::tempdir().unwrap();
        // Written hook-first. Alphabetical order still picks the hook, because
        // `-` sorts before `.` in `rtok-hook.exe` / `rtok.exe`.
        std::fs::write(tmp.path().join("rtok-hook.exe"), b"hook").unwrap();
        std::fs::write(tmp.path().join("rtok.exe"), b"main").unwrap();
        let mut found: Vec<PathBuf> = std::fs::read_dir(tmp.path())
            .unwrap()
            .map(|entry| entry.unwrap().path())
            .collect();
        order_discovered_executables(&mut found, "rtok");
        assert_eq!(found[0].file_name().unwrap(), "rtok.exe", "{found:?}");
    }

    #[test]
    fn an_unsuffixed_pair_chooses_the_package_name_over_the_hook() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::write(tmp.path().join("rtok-hook"), b"hook").unwrap();
        std::fs::write(tmp.path().join("rtok"), b"main").unwrap();
        let mut found: Vec<PathBuf> = std::fs::read_dir(tmp.path())
            .unwrap()
            .map(|entry| entry.unwrap().path())
            .collect();
        order_discovered_executables(&mut found, "rtok");
        assert_eq!(found[0].file_name().unwrap(), "rtok", "{found:?}");
    }

    #[test]
    fn the_package_name_match_ignores_exe_and_ascii_case() {
        // Upper case sorts first, so a plain sort selects the hook.
        let mut found = vec![
            PathBuf::from("payload/rtok.exe"),
            PathBuf::from("payload/RTOK-HOOK.EXE"),
        ];
        order_discovered_executables(&mut found, "Rtok");
        assert_eq!(found[0], PathBuf::from("payload/rtok.exe"));
    }

    #[test]
    fn place_chooses_the_package_named_binary_over_a_similarly_named_hook() {
        let tmp = tempfile::tempdir().unwrap();
        let payload = tmp.path().join("payload");
        let shipped = payload.join("bin");
        std::fs::create_dir_all(&shipped).unwrap();
        // The Windows spelling of `rtok` and `rtok-hook`. On every OS a plain
        // sort of these two names selects the hook.
        write_executable(&shipped, "rtok-hook.exe");
        write_executable(&shipped, "rtok.exe");
        let store = tmp.path().join("store/rtok/1.0");
        let bin = tmp.path().join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        let plan = Placement {
            name: "rtok",
            payload_dir: &payload,
            store_dir: &store,
            bin_dir: &bin,
            apps_dir: tmp.path(),
            kind: PackageKind::Binary,
            bin_specs: &[],
            replacing: &[],
            link_apps: false,
            link: true,
            extras: &[],
        };
        let links = host().unwrap().place(&plan).unwrap();
        let binaries: Vec<_> = links
            .iter()
            .filter(|link| link.role == crate::model::LinkRole::Binary)
            .collect();
        assert_eq!(binaries.len(), 2, "{links:?}");
        assert_eq!(
            binaries[0].target.file_name().unwrap(),
            "rtok.exe",
            "{binaries:?}"
        );
        assert!(
            binaries.iter().any(|link| link
                .target
                .file_name()
                .is_some_and(|n| n == "rtok-hook.exe")),
            "{binaries:?}"
        );
    }

    #[test]
    fn detects_sidecars() {
        assert!(is_sidecar("rg-14.tar.gz.sha256"));
        assert!(is_sidecar("tool.dmg.asc"));
        assert!(is_sidecar("bundle.intoto.jsonl"));
        assert!(!is_sidecar("rg-14.tar.gz"));
    }

    #[test]
    fn extra_destination_checks_name_man_and_each_shell() {
        let host = host().expect("host platform");
        let checks = extra_destination_checks(host.as_ref());
        let names: Vec<_> = checks.iter().map(|c| c.name.as_str()).collect();
        assert!(names.contains(&"man"), "{names:?}");
        assert!(names.contains(&"completions-bash"), "{names:?}");
        assert!(names.contains(&"completions-zsh"), "{names:?}");
        assert!(names.contains(&"completions-fish"), "{names:?}");
        assert!(
            checks
                .iter()
                .any(|c| c.name == "man" && c.detail.contains("MANPATH")),
            "{}",
            checks
                .iter()
                .find(|c| c.name == "man")
                .map(|c| c.detail.as_str())
                .unwrap_or("")
        );
    }

    #[test]
    fn user_man_root_is_under_xdg_data_home() {
        assert_eq!(data_home().join("man"), host().unwrap().user_man_root());
        assert_eq!(
            data_home().join("bash-completion/completions"),
            host()
                .unwrap()
                .completion_dir(crate::model::CompletionShell::Bash)
        );
    }
    #[cfg(target_os = "linux")]
    #[test]
    fn host_is_linux() {
        assert_eq!(host().unwrap().id(), "linux");
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn host_is_windows() {
        assert_eq!(host().unwrap().id(), "windows");
    }

    #[cfg(not(any(target_os = "macos", target_os = "linux", target_os = "windows")))]
    #[test]
    fn host_errs_on_an_os_with_no_backend() {
        let err = host().unwrap_err();
        assert!(err.to_string().contains("no backend"));
    }

    #[test]
    fn path_is_within_accepts_a_descendant() {
        let root = Path::new("/Users/ivan/.ketch/store");
        assert!(path_is_within(
            Path::new("/Users/ivan/.ketch/store/rg/1.0"),
            root
        ));
        assert!(!path_is_strict_within(root, root));
        assert!(!path_is_within(Path::new("/Users/ivan/.ketch/other"), root));
    }

    #[cfg(windows)]
    #[test]
    fn path_is_within_folds_ascii_case_on_windows() {
        let root = Path::new(r"C:\Users\ivan\.ketch\store");
        let folded = Path::new(r"C:\Users\IVAN\.ketch\store\rg\1.0");
        assert!(path_is_within(folded, root));
        assert!(path_is_strict_within(folded, root));
        assert!(!path_is_strict_within(
            Path::new(r"C:\Users\IVAN\.ketch\store"),
            root
        ));
    }
}
