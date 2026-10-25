// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Error type shared by every module.
//!
//! One enum keeps the plumbing honest: any module may construct any variant,
//! and a front end renders them. Variants carry the data needed to write a
//! message a user can act on — never a bare string where a path or URL exists,
//! and never a CLI flag. Hints and process exit codes belong to the binary:
//! a desktop app shows the same errors and has no flags to name.

use std::path::{Path, PathBuf};
use thiserror::Error;

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug, Error)]
pub enum Error {
    #[error("{0}")]
    Msg(String),

    #[error("{path}: {source}")]
    Io {
        path: PathBuf,
        #[source]
        source: std::io::Error,
    },

    #[error("{0}")]
    PlainIo(#[from] std::io::Error),

    #[error("HTTP {status} from {url}")]
    Http {
        url: String,
        status: u16,
        detail: Option<String>,
    },

    /// The server refused because its rate limit was hit. Distinct from
    /// [`Http`] so a caller can say so without reading the body: GitHub's
    /// primary limit is status 403, the same status as a private repository.
    #[error("rate limited by {url}")]
    RateLimited { url: String, detail: Option<String> },

    #[error("network error requesting {url}")]
    Network {
        url: String,
        #[source]
        source: Box<ureq::Error>,
    },

    #[error("could not parse {what}: {detail}")]
    Parse { what: String, detail: String },

    #[error("no source is registered for scheme `{0}`")]
    UnknownScheme(String),

    #[error("`{0}` is not installed")]
    NotInstalled(String),

    #[error("`{0}` has no retained version to roll back to")]
    NoRetained(String),

    #[error("`{name}` {version} is already installed")]
    AlreadyInstalled { name: String, version: String },

    /// `ketch install` of a package already installed without a version:
    /// the resolved release is the installed one.
    #[error("cannot install `{name}`: {version} is already installed and no update is available")]
    NoUpdate { name: String, version: String },

    /// `ketch install` of an installed package that has a newer release:
    /// the command asks before it updates.
    #[error("`{name}` {installed} is installed and {latest} is available")]
    UpdateAvailable {
        name: String,
        installed: String,
        latest: String,
        /// The release tag to update to, so the update installs exactly what
        /// the question named.
        tag: String,
    },

    #[error("`{name}` is pinned to {version}")]
    Pinned { name: String, version: String },

    /// The name is already installed, from a different source than this
    /// request. The two sources are `scheme:id`. What to type instead is a
    /// hint the binary adds; this message is also shown by a GUI.
    #[error("`{name}` is already installed from {installed_from}; {requested} would replace it")]
    SourceConflict {
        name: String,
        installed_from: String,
        requested: String,
    },

    #[error("no release found for `{0}`")]
    NoRelease(String),

    #[error("release `{tag}` of `{id}` has no asset for {target}")]
    NoCompatibleAsset {
        id: String,
        tag: String,
        target: String,
    },

    #[error("checksum mismatch for {name}")]
    ChecksumMismatch {
        name: String,
        expected: String,
        actual: String,
    },

    #[error("no published checksum for {0}")]
    ChecksumMissing(String),

    #[error("{name} was installed against a published checksum, but {asset} publishes none")]
    ChecksumDropped { name: String, asset: String },

    #[error("no installable files found in {0}")]
    EmptyPayload(PathBuf),

    #[error("unsupported archive format: {0}")]
    UnsupportedArchive(PathBuf),

    #[error("`{cmd}` failed ({status})")]
    Command {
        cmd: String,
        status: String,
        stderr: String,
    },

    #[error("plugin `{name}`: {detail}")]
    Plugin {
        name: String,
        detail: String,
        stderr: String,
    },

    #[error("{0}")]
    Config(String),

    /// The command has already told the user everything, line by line, and
    /// only the exit code is left: `main` prints nothing more for it.
    #[error("")]
    Reported(i32),

    /// Another operation holds the install tree's lock: a ketch process named
    /// by `pid`, or (when the lock file could not be read) just `lock`.
    #[error("another ketch process holds the lock ({})", busy_holder(*pid, lock))]
    Busy { pid: Option<u32>, lock: PathBuf },

    /// The caller's `Cancel` token fired; nothing was left half-installed.
    #[error("cancelled")]
    Cancelled,
}

/// The parenthesised part of the busy message: the pid when known, else the
/// lock file, exactly as the message read before `Busy` was typed.
fn busy_holder(pid: Option<u32>, lock: &Path) -> String {
    match pid {
        Some(pid) => format!("pid {pid}"),
        None => lock.display().to_string(),
    }
}

impl Error {
    pub fn msg(text: impl Into<String>) -> Self {
        Error::Msg(text.into())
    }

    pub fn io(path: impl AsRef<Path>, source: std::io::Error) -> Self {
        Error::Io {
            path: path.as_ref().to_path_buf(),
            source,
        }
    }

    /// An error from `fs-kit`. It already names the exact path that failed
    /// (a file deep inside a copied tree), which wins over `path`; the
    /// original error goes in as the source, so the path prints once.
    pub fn fs_kit(path: impl AsRef<Path>, err: std::io::Error) -> Self {
        let (at, source) = fs_kit::split(err);
        Error::io(at.as_deref().unwrap_or(path.as_ref()), source)
    }

    pub fn parse(what: impl Into<String>, detail: impl Into<String>) -> Self {
        Error::Parse {
            what: what.into(),
            detail: detail.into(),
        }
    }

    /// An HTTP status. A rate limit is [`RateLimited`] rather than [`Http`],
    /// because GitHub reports its primary limit as 403 — the same status as a
    /// private repository — and the body is what separates them. 429 always is
    /// one.
    pub fn from_http_status(url: impl Into<String>, status: u16, detail: Option<String>) -> Self {
        let url = url.into();
        let limited = status == 429
            || (status == 403
                && detail
                    .as_deref()
                    .is_some_and(|text| text.to_ascii_lowercase().contains("rate limit")));
        if limited {
            Error::RateLimited { url, detail }
        } else {
            Error::Http {
                url,
                status,
                detail,
            }
        }
    }

    /// Extra lines shown under the headline message. Keeps the `Display` impl
    /// short while still surfacing server bodies, diffs and stderr.
    pub fn details(&self) -> Vec<String> {
        match self {
            Error::Http { detail, .. } | Error::RateLimited { detail, .. } => {
                detail.iter().cloned().collect()
            }
            Error::Command { stderr, .. } | Error::Plugin { stderr, .. }
                if !stderr.trim().is_empty() =>
            {
                stderr.trim().lines().map(|l| l.to_string()).collect()
            }
            Error::ChecksumMismatch {
                expected, actual, ..
            } => vec![format!("expected {expected}"), format!("actual   {actual}")],
            _ => Vec::new(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_fs_kit_error_names_the_path_that_failed_once() {
        let dir = tempfile::tempdir().unwrap();
        // Reading a directory as text fails on every platform.
        let err = fs_kit::read::text_or_empty(dir.path()).unwrap_err();
        let text = Error::fs_kit("caller-path", err).to_string();
        let path = dir.path().display().to_string();
        assert_eq!(text.matches(&path).count(), 1, "{text}");
        assert!(!text.contains("caller-path"), "{text}");
    }

    #[test]
    fn a_rate_limit_body_on_403_is_not_a_plain_http_error() {
        let err = Error::from_http_status(
            "https://api.github.com/x",
            403,
            Some("API rate limit exceeded".into()),
        );
        assert!(matches!(err, Error::RateLimited { .. }), "{err:?}");
        assert!(err.to_string().contains("rate limited"), "{err}");
        assert!(!err.to_string().contains("--"), "{err}");
        assert_eq!(err.details(), vec!["API rate limit exceeded".to_string()]);
    }

    #[test]
    fn a_forbidden_body_without_a_rate_limit_stays_http() {
        let err = Error::from_http_status(
            "https://api.github.com/x",
            403,
            Some("Resource not accessible by integration".into()),
        );
        assert!(matches!(err, Error::Http { status: 403, .. }), "{err:?}");
    }

    #[test]
    fn http_429_is_a_rate_limit_even_without_a_body() {
        let err = Error::from_http_status("https://api.github.com/x", 429, None);
        assert!(matches!(err, Error::RateLimited { detail: None, .. }));
    }

    #[test]
    fn a_source_conflict_names_both_sources_and_no_flag() {
        let err = Error::SourceConflict {
            name: "rg".into(),
            installed_from: "github:BurntSushi/ripgrep".into(),
            requested: "github:other/ripgrep".into(),
        };
        let text = err.to_string();
        assert!(text.contains("github:BurntSushi/ripgrep"), "{text}");
        assert!(text.contains("github:other/ripgrep"), "{text}");
        assert!(!text.contains("--"), "{text}");
    }
}
