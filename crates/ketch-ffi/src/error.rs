// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The one error type a foreign caller sees, and how the core's map onto it.
//!
//! Exit codes and hints that name CLI flags stay in the binary. A front end
//! branches on the questions it can act on — retry a held lock, ignore a
//! cancel, offer Update or Unpin, explain a rate limit — and `Other` is what
//! is left, worded as the core words it, without those hints.

use ketch_core::error::Error;

/// Why an operation failed.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error, serde::Serialize)]
#[cfg_attr(feature = "schema", derive(schemars::JsonSchema))]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum KetchError {
    /// Another operation holds the install tree's lock: a ketch process named
    /// by `pid`, or this process on another thread, or (when the lock file
    /// could not be read) nobody known. Retry once it finishes.
    #[error("another ketch process holds the lock")]
    Busy { pid: Option<u32> },
    /// The operation's `CancelToken` fired; nothing was left half-installed.
    #[error("cancelled")]
    Cancelled,
    /// No installed package, or no release, answers to `name`.
    #[error("`{name}` not found")]
    NotFound { name: String },
    /// A source could not be reached, or answered with an HTTP error.
    #[error("{message}")]
    Network { message: String },
    /// A download did not match its published or recorded checksum, or none
    /// was published where one was required.
    #[error("{message}")]
    Verification { message: String },
    /// This exact version is already installed. An app can offer to reinstall.
    #[error("`{name}` {version} is already installed")]
    AlreadyInstalled { name: String, version: String },
    /// A newer release is available. `tag` is what an update installs, so an
    /// app can offer Update without asking again.
    #[error("`{name}` {installed} is installed and {latest} is available")]
    UpdateAvailable {
        name: String,
        installed: String,
        latest: String,
        tag: String,
    },
    /// Held by a pin. An app can offer Unpin.
    #[error("`{name}` is pinned to {version}")]
    Pinned { name: String, version: String },
    /// The release has no asset this target can install.
    #[error("release `{tag}` of `{id}` has no asset for {target}")]
    NoCompatibleAsset {
        id: String,
        tag: String,
        target: String,
    },
    /// The name is already installed from `installed_from`; `requested` would
    /// replace it. Both are `scheme:id`.
    #[error("`{name}` is already installed from {installed_from}; {requested} would replace it")]
    SourceConflict {
        name: String,
        installed_from: String,
        requested: String,
    },
    /// The source's rate limit refused the request. Distinct from [`Network`]
    /// so an app can explain a token rather than a dead connection.
    #[error("{message}")]
    RateLimited { message: String },
    /// Anything else, in the core's own wording.
    #[error("{message}")]
    Other { message: String },
}

impl From<Error> for KetchError {
    fn from(error: Error) -> Self {
        match &error {
            Error::Busy { pid, .. } => KetchError::Busy { pid: *pid },
            Error::Cancelled => KetchError::Cancelled,
            Error::NotInstalled(name) | Error::NoRelease(name) => {
                KetchError::NotFound { name: name.clone() }
            }
            Error::Http { .. } | Error::Network { .. } => KetchError::Network {
                message: describe(&error),
            },
            Error::ChecksumMismatch { .. }
            | Error::ChecksumMissing(_)
            | Error::ChecksumDropped { .. } => KetchError::Verification {
                message: describe(&error),
            },
            Error::AlreadyInstalled { name, version } => KetchError::AlreadyInstalled {
                name: name.clone(),
                version: version.clone(),
            },
            Error::UpdateAvailable {
                name,
                installed,
                latest,
                tag,
            } => KetchError::UpdateAvailable {
                name: name.clone(),
                installed: installed.clone(),
                latest: latest.clone(),
                tag: tag.clone(),
            },
            Error::Pinned { name, version } => KetchError::Pinned {
                name: name.clone(),
                version: version.clone(),
            },
            Error::NoCompatibleAsset { id, tag, target } => KetchError::NoCompatibleAsset {
                id: id.clone(),
                tag: tag.clone(),
                target: target.clone(),
            },
            Error::SourceConflict {
                name,
                installed_from,
                requested,
            } => KetchError::SourceConflict {
                name: name.clone(),
                installed_from: installed_from.clone(),
                requested: requested.clone(),
            },
            Error::RateLimited { .. } => KetchError::RateLimited {
                message: describe(&error),
            },
            _ => KetchError::Other {
                message: describe(&error),
            },
        }
    }
}

/// The headline and the detail lines under it: what the core says, without
/// the CLI hint. Hints name flags, and this string is what a GUI shows.
///
/// Error text quotes a server's response body and a plugin's stderr, which
/// someone else wrote; it is filtered here, the one place it leaves the core,
/// so no front end has to remember to.
fn describe(error: &Error) -> String {
    let mut lines = vec![error.to_string()];
    lines.extend(error.details());
    ketch_core::changelog::sanitize(&lines.join("\n"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use pretty_assertions::assert_eq;
    use std::path::PathBuf;

    #[test]
    fn a_held_lock_keeps_its_pid() {
        let error = Error::Busy {
            pid: Some(42),
            lock: PathBuf::from("/tmp/.lock"),
        };
        assert_eq!(KetchError::from(error), KetchError::Busy { pid: Some(42) });
    }

    #[test]
    fn cancellation_is_its_own_variant() {
        assert_eq!(KetchError::from(Error::Cancelled), KetchError::Cancelled);
    }

    #[test]
    fn a_missing_package_or_release_is_not_found() {
        assert_eq!(
            KetchError::from(Error::NotInstalled("rg".into())),
            KetchError::NotFound { name: "rg".into() }
        );
        assert_eq!(
            KetchError::from(Error::NoRelease("jq".into())),
            KetchError::NotFound { name: "jq".into() }
        );
    }

    #[test]
    fn http_errors_are_network_errors_with_the_server_detail() {
        let error = Error::Http {
            url: "https://api.github.com/x".into(),
            status: 503,
            detail: Some("unavailable".into()),
        };
        let KetchError::Network { message } = KetchError::from(error) else {
            panic!("not a network error");
        };
        assert_eq!(
            message.lines().collect::<Vec<_>>(),
            vec!["HTTP 503 from https://api.github.com/x", "unavailable",]
        );
    }

    #[test]
    fn a_rate_limit_is_its_own_kind_and_names_no_flag() {
        let error = Error::RateLimited {
            url: "https://api.github.com/x".into(),
            detail: Some("API rate limit exceeded".into()),
        };
        let KetchError::RateLimited { message } = KetchError::from(error) else {
            panic!("not rate limited");
        };
        assert_eq!(
            message.lines().collect::<Vec<_>>(),
            vec![
                "rate limited by https://api.github.com/x",
                "API rate limit exceeded",
            ]
        );
        assert!(!message.contains("--"), "{message}");
    }

    #[test]
    fn install_conflicts_keep_the_fields_an_app_acts_on() {
        assert_eq!(
            KetchError::from(Error::AlreadyInstalled {
                name: "rg".into(),
                version: "14.1.1".into(),
            }),
            KetchError::AlreadyInstalled {
                name: "rg".into(),
                version: "14.1.1".into(),
            }
        );
        assert_eq!(
            KetchError::from(Error::UpdateAvailable {
                name: "rg".into(),
                installed: "14.1.0".into(),
                latest: "14.1.1".into(),
                tag: "14.1.1".into(),
            }),
            KetchError::UpdateAvailable {
                name: "rg".into(),
                installed: "14.1.0".into(),
                latest: "14.1.1".into(),
                tag: "14.1.1".into(),
            }
        );
        assert_eq!(
            KetchError::from(Error::Pinned {
                name: "fd".into(),
                version: "10.1.0".into(),
            }),
            KetchError::Pinned {
                name: "fd".into(),
                version: "10.1.0".into(),
            }
        );
        assert_eq!(
            KetchError::from(Error::NoCompatibleAsset {
                id: "BurntSushi/ripgrep".into(),
                tag: "14.1.1".into(),
                target: "x86_64-unknown-linux-gnu".into(),
            }),
            KetchError::NoCompatibleAsset {
                id: "BurntSushi/ripgrep".into(),
                tag: "14.1.1".into(),
                target: "x86_64-unknown-linux-gnu".into(),
            }
        );
        assert_eq!(
            KetchError::from(Error::SourceConflict {
                name: "rg".into(),
                installed_from: "github:BurntSushi/ripgrep".into(),
                requested: "github:other/ripgrep".into(),
            }),
            KetchError::SourceConflict {
                name: "rg".into(),
                installed_from: "github:BurntSushi/ripgrep".into(),
                requested: "github:other/ripgrep".into(),
            }
        );
    }

    /// Every error whose hint used to name a flag, plus the clash that named
    /// `--name` in its own message. A GUI shows `to_string()` of this mapping.
    #[test]
    fn no_ffi_message_names_a_cli_flag() {
        let errors = [
            Error::RateLimited {
                url: "https://api.github.com/x".into(),
                detail: Some("API rate limit exceeded".into()),
            },
            Error::Http {
                url: "https://api.github.com/x".into(),
                status: 404,
                detail: None,
            },
            Error::NoCompatibleAsset {
                id: "o/r".into(),
                tag: "v1".into(),
                target: "t".into(),
            },
            Error::ChecksumMismatch {
                name: "a.tar.gz".into(),
                expected: "aa".into(),
                actual: "bb".into(),
            },
            Error::ChecksumDropped {
                name: "rg".into(),
                asset: "rg.tar.gz".into(),
            },
            Error::AlreadyInstalled {
                name: "rg".into(),
                version: "1".into(),
            },
            Error::NoUpdate {
                name: "rg".into(),
                version: "1".into(),
            },
            Error::UpdateAvailable {
                name: "rg".into(),
                installed: "1".into(),
                latest: "2".into(),
                tag: "v2".into(),
            },
            Error::Pinned {
                name: "rg".into(),
                version: "1".into(),
            },
            Error::SourceConflict {
                name: "rg".into(),
                installed_from: "github:a/rg".into(),
                requested: "github:b/rg".into(),
            },
            Error::NoRetained("rg".into()),
            Error::UnknownScheme("git".into()),
        ];
        for error in errors {
            let mapped = KetchError::from(error);
            let message = mapped.to_string();
            assert!(
                !message.contains("--"),
                "{mapped:?} names a flag: {message}"
            );
        }
    }

    #[test]
    fn a_checksum_mismatch_is_a_verification_error() {
        let error = Error::ChecksumMismatch {
            name: "rg.tar.gz".into(),
            expected: "aa".into(),
            actual: "bb".into(),
        };
        let KetchError::Verification { message } = KetchError::from(error) else {
            panic!("not a verification error");
        };
        assert!(message.starts_with("checksum mismatch for rg.tar.gz"));
        assert!(message.contains("expected aa"));
    }

    #[test]
    fn everything_else_keeps_the_core_wording_without_control_characters() {
        let error = Error::msg("bad \u{1b}[2Jthing\u{202e}");
        assert_eq!(
            KetchError::from(error),
            KetchError::Other {
                message: "bad [2Jthing".into()
            }
        );
    }
}
