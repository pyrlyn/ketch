// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! A package's releases, as `ketch releases` and `ketch info --releases` list
//! them.
//!
//! Separate from `info.rs` because the question is different: `info` asks for
//! the one release an install would pick, this asks for what the source
//! publishes and what each of it is. Every release is untrusted client data —
//! a tag, a date stamp — so nothing here is filtered for a terminal; the
//! callers sanitise what they show.

use crate::error::Result;
use crate::manifest::same_source;
use crate::model::{InstalledPackage, Manifest, Release, Version};
use crate::report::Ctx;
use crate::source::{ListOpts, SourceRegistry};

/// What a listed release is. The one place that names a kind, so a new channel
/// is one more arm here and not a second opinion in a renderer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReleaseKind {
    Stable,
    /// The forge's prerelease flag, or a semver prerelease in the tag.
    Pre,
    /// Not published: `Release.draft`. Sources do not return these yet.
    Draft,
    /// A tag that is not a version at all (`nightly`), republished in place.
    Dev,
}

impl ReleaseKind {
    /// Draft outranks everything: it is not out yet. A tag that is not a
    /// version outranks the prerelease flag, because projects that float a
    /// `nightly` tag nearly always flag it prerelease too, and what matters to
    /// a person is that it moves.
    pub fn of(release: &Release) -> ReleaseKind {
        if release.draft {
            ReleaseKind::Draft
        } else if release.version.sem.is_none() {
            ReleaseKind::Dev
        } else if release.prerelease || release.version.is_prerelease() {
            ReleaseKind::Pre
        } else {
            ReleaseKind::Stable
        }
    }

    /// The lowercase name the table and `--json` use.
    pub fn as_str(self) -> &'static str {
        match self {
            ReleaseKind::Stable => "stable",
            ReleaseKind::Pre => "pre",
            ReleaseKind::Draft => "draft",
            ReleaseKind::Dev => "dev",
        }
    }
}

/// One line of a release listing.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub tag: String,
    pub version: Version,
    /// The source's publication stamp, as it gave it.
    pub published_at: Option<String>,
    pub kind: ReleaseKind,
    /// Whether this is the release the package is installed from.
    pub installed: bool,
}

impl Entry {
    fn new(release: Release, installed: Option<&InstalledPackage>) -> Entry {
        Entry {
            kind: ReleaseKind::of(&release),
            installed: installed.is_some_and(|p| p.tag == release.tag),
            tag: release.tag,
            version: release.version,
            published_at: release.published_at,
        }
    }

    /// The calendar day of the publication stamp, `YYYY-MM-DD`. A stamp that
    /// does not start with one is not shown: it comes from a source plugin and
    /// is only ever as good as what that plugin wrote.
    pub fn date(&self) -> Option<&str> {
        let day = self.published_at.as_deref()?.get(..10)?;
        let digits =
            |range: std::ops::Range<usize>| day.as_bytes()[range].iter().all(u8::is_ascii_digit);
        (digits(0..4)
            && day.as_bytes()[4] == b'-'
            && digits(5..7)
            && day.as_bytes()[7] == b'-'
            && digits(8..10))
        .then_some(day)
    }
}

/// The releases of `manifest`'s source, newest first as the source gave them,
/// at most `limit` of them.
///
/// `prerelease` is the caller's own `--pre`; the package's usual rules widen it
/// (`config.toml`, the manifest, a `pre` install) the way `info` and
/// `outdated` already do. An installed package is marked only when it came
/// from this source, since a tag means nothing in another one.
pub fn list(
    cx: &Ctx<'_>,
    sources: &SourceRegistry,
    manifest: &Manifest,
    installed: Option<&InstalledPackage>,
    prerelease: bool,
    limit: usize,
) -> Result<Vec<Entry>> {
    let opts = ListOpts {
        include_prerelease: crate::info::wants_prerelease(cx.cfg, manifest, installed, prerelease),
        include_draft: false,
        limit,
    };
    let releases = sources
        .for_ref(&manifest.source)?
        .list_releases(&manifest.source.id, &opts)?;
    let installed = installed.filter(|p| same_source(&p.source, &manifest.source));
    Ok(releases
        .into_iter()
        .take(limit)
        .map(|release| Entry::new(release, installed))
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use pretty_assertions::assert_eq;

    fn release(tag: &str, prerelease: bool, draft: bool) -> Release {
        Release {
            version: Version::parse(tag),
            tag: tag.to_string(),
            prerelease,
            draft,
            published_at: None,
            notes: None,
            assets: Vec::new(),
        }
    }

    fn kind(tag: &str, prerelease: bool, draft: bool) -> &'static str {
        ReleaseKind::of(&release(tag, prerelease, draft)).as_str()
    }

    #[test]
    fn a_plain_version_is_stable() {
        assert_eq!(kind("v1.2.3", false, false), "stable");
    }

    #[test]
    fn the_prerelease_flag_or_a_semver_prerelease_makes_it_pre() {
        assert_eq!(kind("v1.2.3", true, false), "pre");
        assert_eq!(kind("v2.0.0-rc.1", false, false), "pre");
    }

    #[test]
    fn a_tag_that_is_not_a_version_is_dev_even_when_flagged_prerelease() {
        assert_eq!(kind("nightly", false, false), "dev");
        assert_eq!(kind("nightly", true, false), "dev");
    }

    #[test]
    fn a_draft_is_a_draft_whatever_else_it_is() {
        assert_eq!(kind("v1.2.3", false, true), "draft");
        assert_eq!(kind("nightly", true, true), "draft");
    }

    #[test]
    fn the_date_is_the_day_of_a_well_formed_stamp() {
        let entry = |stamp: Option<&str>| Entry {
            tag: "v1".into(),
            version: Version::parse("v1"),
            published_at: stamp.map(str::to_string),
            kind: ReleaseKind::Stable,
            installed: false,
        };
        assert_eq!(
            entry(Some("2026-10-10T12:00:00Z")).date(),
            Some("2026-10-10")
        );
        assert_eq!(entry(Some("2026-10-10")).date(), Some("2026-10-10"));
        assert_eq!(entry(Some("yesterday-ish")).date(), None);
        assert_eq!(entry(Some("2026")).date(), None);
        assert_eq!(entry(Some("\u{1b}[31m2026-10-1")).date(), None);
        assert_eq!(entry(None).date(), None);
    }

    #[test]
    fn a_stamp_cut_inside_a_multibyte_character_is_not_a_date() {
        let entry = Entry {
            tag: "v1".into(),
            version: Version::parse("v1"),
            published_at: Some("2026-10-1é".into()),
            kind: ReleaseKind::Stable,
            installed: false,
        };
        assert_eq!(entry.date(), None);
    }
}
