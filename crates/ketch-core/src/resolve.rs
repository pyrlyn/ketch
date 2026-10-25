// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Resolution trace shared by install, `ketch why` and `ketch info --assets`.
//!
//! Install and `ketch why` share these functions so an explanation cannot drift
//! from what would actually be installed. Nothing here downloads or fetches
//! checksum files. The one network call is the same `Source::resolve` install
//! makes; `explain` shows a spinner on stderr while that call runs.
//!
//! `--target` scores that same decision for another OS. The host backend cannot
//! do it — each one is compiled only for its own OS — so scoring goes through
//! [`crate::platform::for_target`] and nothing is placed.

use crate::config::Config;
use crate::error::Result;
use crate::manifest::Resolver;
use crate::model::{
    glob_match, AskedTarget, AssetSelector, Manifest, ManifestOrigin, PackageSpec, Release,
    ReleaseAsset,
};
use crate::platform::{AssetScore, Platform};
use crate::report::Ctx;
use crate::source::{ListOpts, RejectedRelease, Source, SourceRegistry};
use serde::Serialize;
use std::sync::Arc;

/// A release asset ranked for this platform.
#[derive(Debug, Clone)]
pub struct ScoredAsset {
    pub asset: ReleaseAsset,
    pub score: AssetScore,
}

/// An asset that scoring refused, and why.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct RejectedAsset {
    pub name: String,
    pub reason: String,
}

/// Ranked assets plus the ones that did not make the cut.
#[derive(Debug, Clone)]
pub struct AssetEvaluation {
    pub scored: Vec<ScoredAsset>,
    pub rejected: Vec<RejectedAsset>,
}

/// Rank a release's assets for this platform, best first.
///
/// Assets the platform or the manifest rejects are listed on `rejected` rather
/// than dropped, so `ketch why` can show the same decision install will make.
pub fn evaluate_assets(
    cfg: &Config,
    platform: &dyn Platform,
    release: &Release,
    selector: &AssetSelector,
) -> AssetEvaluation {
    let target_pattern = selector.target.get(&cfg.target.to_string());
    let mut scored: Vec<ScoredAsset> = Vec::new();
    let mut rejected: Vec<RejectedAsset> = Vec::new();

    for asset in &release.assets {
        if let Some(pattern) = selector.exclude.iter().find(|p| glob_match(p, &asset.name)) {
            rejected.push(RejectedAsset {
                name: asset.name.clone(),
                reason: format!("excluded by `{pattern}`"),
            });
            continue;
        }

        // A per-target pattern is the user naming the file outright, so it
        // overrides the platform's opinion rather than filtering it.
        if let Some(pattern) = target_pattern {
            if glob_match(pattern, &asset.name) {
                scored.push(ScoredAsset {
                    asset: asset.clone(),
                    score: AssetScore {
                        score: i32::MAX,
                        arch: cfg.target.arch,
                        emulated: false,
                        reason: format!("manifest pins `{pattern}` for {}", cfg.target),
                    },
                });
            } else {
                rejected.push(RejectedAsset {
                    name: asset.name.clone(),
                    reason: format!(
                        "does not match the pinned pattern `{pattern}` for {}",
                        cfg.target
                    ),
                });
            }
            continue;
        }

        if !selector.include.is_empty()
            && !selector.include.iter().any(|p| glob_match(p, &asset.name))
        {
            rejected.push(RejectedAsset {
                name: asset.name.clone(),
                reason: "does not match any include pattern".into(),
            });
            continue;
        }
        match platform.score_asset(&asset.name, cfg.allow_emulation) {
            Some(score) => scored.push(ScoredAsset {
                asset: asset.clone(),
                score,
            }),
            None => rejected.push(RejectedAsset {
                name: asset.name.clone(),
                reason: format!("incompatible with {}", cfg.target),
            }),
        }
    }

    // Name is the tie-break so repeated runs pick the same asset.
    scored.sort_by(|a, b| {
        b.score
            .score
            .cmp(&a.score.score)
            .then_with(|| a.asset.name.cmp(&b.asset.name))
    });
    rejected.sort_by(|a, b| a.name.cmp(&b.name));
    AssetEvaluation { scored, rejected }
}

/// Ranked assets only, the shape install and `info --assets` already use.
pub fn score_assets(
    cfg: &Config,
    platform: &dyn Platform,
    release: &Release,
    selector: &AssetSelector,
) -> Vec<ScoredAsset> {
    evaluate_assets(cfg, platform, release, selector).scored
}

/// How a checksum would be handled if this candidate were installed.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ChecksumPolicy {
    /// `require_checksums` from config. Sidecar files are not fetched here.
    pub require: bool,
    /// Algorithm on the chosen asset, when the release already carried one.
    pub asset_digest: Option<String>,
    /// What install would do with that information, without extra requests.
    pub policy: &'static str,
}

/// Local trust checks run after download and never refuse the install.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct TrustPolicy {
    pub strip_quarantine: bool,
    pub allow_emulation: bool,
    /// Platform trust is advisory: a failed check is reported, not fatal.
    pub blocks_install: bool,
}

/// Where the manifest came from, without embedding untrusted prose.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ManifestStep {
    pub tier: &'static str,
    pub origin: String,
    pub name: String,
    pub source: String,
    /// The alias or reference the user typed, when it differs from `name`.
    pub matched: String,
}

/// The source that will answer for this package.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct SourceStep {
    pub scheme: String,
    pub id: String,
}

/// The release `Source::resolve` returned, plus how it was asked for.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct VersionStep {
    pub request: String,
    pub include_prerelease: bool,
    pub selected: Option<SelectedRelease>,
    /// Other listing entries, when a caller walked a listing through `pick`.
    /// Empty after `Source::resolve`: that call is one request, like install.
    pub rejected: Vec<RejectedRelease>,
}

/// The release that won version selection.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct SelectedRelease {
    pub tag: String,
    pub version: String,
    pub prerelease: bool,
}

/// The asset that would be installed.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Candidate {
    pub name: String,
    pub score: i32,
    pub reason: String,
    pub emulated: bool,
}

/// A scored asset, stripped of URLs, headers and other untrusted control text.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ScoredAssetView {
    pub name: String,
    pub score: i32,
    pub reason: String,
    pub emulated: bool,
}

/// End-to-end explanation of one package decision.
#[derive(Debug, Clone, Serialize)]
pub struct ResolutionTrace {
    pub package: String,
    pub target: String,
    pub manifest: ManifestStep,
    pub source: SourceStep,
    pub version: VersionStep,
    pub assets: AssetTraceView,
    pub checksum: ChecksumPolicy,
    pub trust: TrustPolicy,
    pub candidate: Option<Candidate>,
}

/// Scored and rejected assets as they appear in `ketch why`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct AssetTraceView {
    pub scored: Vec<ScoredAssetView>,
    pub rejected: Vec<RejectedAsset>,
}

/// The listing options install and `why` both pass into `Source::resolve`.
pub fn list_opts(cfg: &Config, manifest: &Manifest, extra_prerelease: bool) -> ListOpts {
    ListOpts {
        include_prerelease: extra_prerelease || cfg.prerelease || manifest.prerelease,
        ..Default::default()
    }
}

/// Explain a package the way install would resolve it, without downloading.
///
/// One `Source::resolve` call, the same function install uses. Checksum
/// sidecars and trust inspection are described from config and the already
/// fetched release, not performed. Scores for this machine; [`explain_for`]
/// scores for another.
pub fn explain(
    cx: &Ctx<'_>,
    sources: &SourceRegistry,
    spec: &PackageSpec,
) -> Result<ResolutionTrace> {
    explain_for(cx, sources, spec, None)
}

/// [`explain`] for `target`, or for this machine when `target` is `None`.
///
/// `target` is `<os>-<arch>` (`linux-x86_64`, `linux-x86_64-musl`) or a Rust
/// triple (`x86_64-unknown-linux-gnu`). The release lookup is the same either
/// way; only asset scoring changes, and nothing is downloaded or installed.
pub fn explain_for(
    cx: &Ctx<'_>,
    sources: &SourceRegistry,
    spec: &PackageSpec,
    target: Option<&str>,
) -> Result<ResolutionTrace> {
    // A bad target fails before the source is asked, so a typo does not spend
    // a request that would be thrown away.
    let asked = match target {
        Some(text) => Some(AskedTarget::parse(text)?),
        None => None,
    };
    let (manifest, origin) = Resolver::new(cx)?.resolve(spec)?;
    let source = sources.for_ref(&manifest.source)?;
    explain_with(cx, source.as_ref(), spec, manifest, origin, asked)
}

/// Rank `release` the way [`explain_for`] would, for `ketch info --assets`.
///
/// `target` is the same string `explain_for` takes. `None` is this machine,
/// scored by [`crate::platform::host`] so it stays the install decision.
pub fn assets_for(
    cfg: &Config,
    release: &Release,
    selector: &AssetSelector,
    target: Option<&str>,
) -> Result<Vec<ScoredAsset>> {
    let asked = match target {
        Some(text) => Some(AskedTarget::parse(text)?),
        None => None,
    };
    let (cfg, platform) = scoring_setup(cfg, asked)?;
    Ok(score_assets(&cfg, platform.as_ref(), release, selector))
}

/// Config whose `target` is the one being scored, and the platform that scores it.
///
/// Manifest pins are keyed by [`crate::model::TargetSpec`]'s display form. The
/// host config would miss a pin for `linux-x86_64` when this machine is a Mac,
/// so the copy used for scoring carries the asked target. The caller's config
/// is left alone: this does not change what an install on this machine does.
fn scoring_setup(cfg: &Config, asked: Option<AskedTarget>) -> Result<(Config, Arc<dyn Platform>)> {
    match asked {
        None => Ok((cfg.clone(), crate::platform::host()?)),
        Some(asked) => {
            let mut cfg = cfg.clone();
            cfg.target = asked.spec;
            Ok((cfg, crate::platform::for_target(asked)))
        }
    }
}

/// How the trace names the platform. Musl is not part of [`crate::model::TargetSpec`],
/// so an explicit musl target would otherwise look identical to glibc.
fn target_label(cfg: &Config, asked: Option<AskedTarget>) -> String {
    match asked {
        Some(asked) if asked.musl_libc() => format!("{}-musl", asked.spec),
        _ => cfg.target.to_string(),
    }
}

fn explain_with(
    cx: &Ctx<'_>,
    source: &dyn Source,
    spec: &PackageSpec,
    manifest: Manifest,
    origin: ManifestOrigin,
    asked: Option<AskedTarget>,
) -> Result<ResolutionTrace> {
    let cfg = cx.cfg;
    // Same helper install uses. `why` has no `--prerelease` flag, so the extra
    // bit is false; config and the manifest still match a default install.
    let opts = list_opts(cfg, &manifest, false);
    let include_prerelease = opts.include_prerelease;
    let selected = match cx
        .report
        .activity(&format!("resolving {}", manifest.name))
        .run(|| source.resolve(&manifest.source.id, &spec.version, &opts))
    {
        Ok(release) => Some(release),
        Err(crate::error::Error::NoRelease(_)) => None,
        Err(e) => return Err(e),
    };

    let (score_cfg, platform) = scoring_setup(cfg, asked)?;
    let evaluation = match &selected {
        Some(release) => evaluate_assets(&score_cfg, platform.as_ref(), release, &manifest.asset),
        None => AssetEvaluation {
            scored: Vec::new(),
            rejected: Vec::new(),
        },
    };
    let candidate = evaluation.scored.first().map(|s| Candidate {
        name: safe(&s.asset.name),
        score: s.score.score,
        reason: safe(&s.score.reason),
        emulated: s.score.emulated,
    });

    let checksum = checksum_policy(cfg, evaluation.scored.first().map(|s| &s.asset));
    Ok(ResolutionTrace {
        package: safe(&manifest.name),
        target: target_label(&score_cfg, asked),
        manifest: ManifestStep {
            tier: origin.tier(),
            origin: safe(&origin.location()),
            name: safe(&manifest.name),
            source: safe(&manifest.source.to_string()),
            matched: safe(&spec.label()),
        },
        source: SourceStep {
            scheme: safe(&manifest.source.scheme),
            id: safe(&manifest.source.id),
        },
        version: VersionStep {
            request: spec.version.to_string(),
            include_prerelease,
            selected: selected.as_ref().map(|r| SelectedRelease {
                tag: safe(&r.tag),
                version: r.version.to_string(),
                prerelease: r.prerelease,
            }),
            rejected: Vec::new(),
        },
        assets: AssetTraceView {
            scored: evaluation
                .scored
                .iter()
                .map(|s| ScoredAssetView {
                    name: safe(&s.asset.name),
                    score: s.score.score,
                    reason: safe(&s.score.reason),
                    emulated: s.score.emulated,
                })
                .collect(),
            rejected: evaluation
                .rejected
                .into_iter()
                .map(|r| RejectedAsset {
                    name: safe(&r.name),
                    reason: safe(&r.reason),
                })
                .collect(),
        },
        checksum,
        trust: TrustPolicy {
            strip_quarantine: cfg.strip_quarantine,
            allow_emulation: cfg.allow_emulation,
            blocks_install: false,
        },
        candidate,
    })
}

fn checksum_policy(cfg: &Config, asset: Option<&ReleaseAsset>) -> ChecksumPolicy {
    let asset_digest = asset.and_then(|a| {
        a.digest
            .as_ref()
            .and_then(|d| d.algo.eq_ignore_ascii_case("sha256").then(|| safe(&d.algo)))
    });
    let policy = match (&asset_digest, cfg.require_checksums) {
        (None, _) if asset.is_none() => "not-applicable",
        (Some(_), _) => "published-digest",
        (None, true) => "require",
        (None, false) => "trust-on-first-use",
    };
    ChecksumPolicy {
        require: cfg.require_checksums,
        asset_digest,
        policy,
    }
}

fn safe(text: &str) -> String {
    crate::changelog::sanitize(text)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{Arch, Os, TargetSpec, Version};
    use crate::platform::Platform;
    use pretty_assertions::assert_eq;
    use std::collections::BTreeMap;
    use std::path::Path;

    struct FakePlatform;

    impl Platform for FakePlatform {
        fn id(&self) -> &str {
            "fake"
        }
        fn target(&self) -> TargetSpec {
            TargetSpec {
                os: Os::MacOs,
                arch: Arch::Aarch64,
            }
        }
        fn score_asset(&self, name: &str, _emu: bool) -> Option<AssetScore> {
            name.contains("darwin").then(|| AssetScore {
                score: 50,
                arch: Arch::Aarch64,
                emulated: false,
                reason: "fake".into(),
            })
        }
        fn extractors(&self) -> Vec<Box<dyn crate::extract::Extractor>> {
            Vec::new()
        }
        fn place(
            &self,
            _plan: &crate::platform::Placement<'_>,
        ) -> Result<Vec<crate::model::LinkRecord>> {
            Ok(Vec::new())
        }
        fn unplace(
            &self,
            _links: &[crate::model::LinkRecord],
            _report: &crate::report::Report,
        ) -> Result<()> {
            Ok(())
        }
        fn is_executable(&self, _path: &Path) -> bool {
            true
        }
        fn doctor(&self, _cfg: &Config) -> Vec<crate::platform::DoctorCheck> {
            Vec::new()
        }
    }

    fn asset(name: &str) -> ReleaseAsset {
        ReleaseAsset {
            name: name.to_string(),
            url: format!("https://example.invalid/{name}?token=SECRET"),
            size: 1,
            content_type: None,
            digest: None,
            headers: BTreeMap::from([("authorization".into(), "secret-token".into())]),
        }
    }

    fn release(names: &[&str]) -> Release {
        Release {
            version: Version::parse("1.0.0"),
            tag: "v1.0.0".into(),
            prerelease: false,
            draft: false,
            published_at: None,
            notes: None,
            assets: names.iter().map(|n| asset(n)).collect(),
        }
    }

    fn config() -> Config {
        let mut cfg = Config::load(
            Some(std::env::temp_dir().join("ketch-why-test-root")),
            &crate::report::Report::silent(),
        )
        .unwrap();
        cfg.target = TargetSpec {
            os: Os::MacOs,
            arch: Arch::Aarch64,
        };
        cfg
    }

    #[test]
    fn evaluate_assets_matches_score_assets_and_keeps_rejects() {
        let eval = evaluate_assets(
            &config(),
            &FakePlatform,
            &release(&["tool-linux.tar.gz", "tool-darwin.tar.gz"]),
            &AssetSelector::default(),
        );
        assert_eq!(eval.scored.len(), 1);
        assert_eq!(eval.scored[0].asset.name, "tool-darwin.tar.gz");
        assert_eq!(
            eval.rejected,
            vec![RejectedAsset {
                name: "tool-linux.tar.gz".into(),
                reason: "incompatible with macos-aarch64".into(),
            }]
        );
        let scored = score_assets(
            &config(),
            &FakePlatform,
            &release(&["tool-linux.tar.gz", "tool-darwin.tar.gz"]),
            &AssetSelector::default(),
        );
        assert_eq!(scored.len(), 1);
        assert_eq!(scored[0].asset.name, eval.scored[0].asset.name);
    }

    #[test]
    fn a_target_pin_is_the_only_scored_asset() {
        let cfg = config();
        let selector = AssetSelector {
            target: BTreeMap::from([(
                "macos-aarch64".to_string(),
                "*-mac-universal.zip".to_string(),
            )]),
            ..Default::default()
        };
        let eval = evaluate_assets(
            &cfg,
            &FakePlatform,
            &release(&["tool-darwin.tar.gz", "tool-mac-universal.zip"]),
            &selector,
        );
        assert_eq!(
            eval.rejected,
            vec![RejectedAsset {
                name: "tool-darwin.tar.gz".into(),
                reason: "does not match the pinned pattern `*-mac-universal.zip` for macos-aarch64"
                    .into(),
            }]
        );
        assert_eq!(eval.scored.len(), 1);
        assert_eq!(eval.scored[0].asset.name, "tool-mac-universal.zip");
        assert_eq!(eval.scored[0].score.score, i32::MAX);
    }

    #[test]
    fn exclude_wins_over_include_and_over_the_target_pin() {
        let cfg = config();
        let selector = AssetSelector {
            include: vec!["*darwin*".into()],
            exclude: vec!["*.dmg".into()],
            target: BTreeMap::from([("macos-aarch64".to_string(), "*.dmg".to_string())]),
        };
        let eval = evaluate_assets(
            &cfg,
            &FakePlatform,
            &release(&["tool-darwin.dmg", "tool-darwin.tar.gz"]),
            &selector,
        );
        assert!(eval.scored.is_empty());
        assert_eq!(
            eval.rejected,
            vec![
                RejectedAsset {
                    name: "tool-darwin.dmg".into(),
                    reason: "excluded by `*.dmg`".into(),
                },
                RejectedAsset {
                    name: "tool-darwin.tar.gz".into(),
                    reason: "does not match the pinned pattern `*.dmg` for macos-aarch64".into(),
                },
            ]
        );
    }

    #[test]
    fn no_compatible_asset_leaves_every_candidate_rejected() {
        let eval = evaluate_assets(
            &config(),
            &FakePlatform,
            &release(&["tool-linux.tar.gz", "SHA256SUMS"]),
            &AssetSelector::default(),
        );
        assert!(eval.scored.is_empty());
        assert_eq!(
            eval.rejected,
            vec![
                RejectedAsset {
                    name: "SHA256SUMS".into(),
                    reason: "incompatible with macos-aarch64".into(),
                },
                RejectedAsset {
                    name: "tool-linux.tar.gz".into(),
                    reason: "incompatible with macos-aarch64".into(),
                },
            ]
        );
    }

    #[test]
    fn include_patterns_reject_assets_that_do_not_match() {
        let selector = AssetSelector {
            include: vec!["*darwin*".into()],
            ..Default::default()
        };
        let eval = evaluate_assets(
            &config(),
            &FakePlatform,
            &release(&["tool-linux.tar.gz", "tool-darwin.tar.gz"]),
            &selector,
        );
        assert_eq!(eval.scored.len(), 1);
        assert_eq!(
            eval.rejected,
            vec![RejectedAsset {
                name: "tool-linux.tar.gz".into(),
                reason: "does not match any include pattern".into(),
            }]
        );
    }

    #[test]
    fn a_trace_view_strips_control_text_and_drops_secrets() {
        let mut rel = release(&["tool-darwin.tar.gz"]);
        rel.assets[0].name = "safe\u{202e}evil-darwin.tar.gz".into();
        let eval = evaluate_assets(&config(), &FakePlatform, &rel, &AssetSelector::default());
        let view = ScoredAssetView {
            name: safe(&eval.scored[0].asset.name),
            score: eval.scored[0].score.score,
            reason: safe(&eval.scored[0].score.reason),
            emulated: false,
        };
        assert!(!view.name.contains('\u{202e}'));
        let json = serde_json::to_string(&view).unwrap();
        assert!(!json.contains("SECRET"));
        assert!(!json.contains("secret-token"));
        assert!(!json.contains("example.invalid"));
        assert!(!json.contains("authorization"));
    }

    /// Stand-in for a native Windows host: real `score_windows_asset` scoring.
    struct WindowsX64Platform;

    impl Platform for WindowsX64Platform {
        fn id(&self) -> &str {
            "windows"
        }
        fn target(&self) -> TargetSpec {
            TargetSpec {
                os: Os::Windows,
                arch: Arch::X86_64,
            }
        }
        fn score_asset(&self, name: &str, _emu: bool) -> Option<AssetScore> {
            crate::platform::scoring::score_windows_asset(name, Arch::X86_64)
        }
        fn extractors(&self) -> Vec<Box<dyn crate::extract::Extractor>> {
            Vec::new()
        }
        fn place(
            &self,
            _plan: &crate::platform::Placement<'_>,
        ) -> Result<Vec<crate::model::LinkRecord>> {
            Ok(Vec::new())
        }
        fn unplace(
            &self,
            _links: &[crate::model::LinkRecord],
            _report: &crate::report::Report,
        ) -> Result<()> {
            Ok(())
        }
        fn is_executable(&self, _path: &Path) -> bool {
            true
        }
        fn doctor(&self, _cfg: &Config) -> Vec<crate::platform::DoctorCheck> {
            Vec::new()
        }
    }

    fn windows_cfg() -> Config {
        let mut cfg = config();
        cfg.target = TargetSpec {
            os: Os::Windows,
            arch: Arch::X86_64,
        };
        cfg
    }

    /// Patterns published for `rtok` in pyrlyn/ketch-registry (after the
    /// Windows zip was added to `include`).
    fn rtok_registry_selector() -> AssetSelector {
        AssetSelector {
            include: vec![
                "*-apple-darwin.tar.xz".into(),
                "*-linux-gnu.tar.xz".into(),
                "*-pc-windows-msvc.zip".into(),
            ],
            exclude: vec!["*-update*".into(), "source.tar.gz".into()],
            ..Default::default()
        }
    }

    /// Asset names from a real cargo-dist rtok release (v0.1.3 shape).
    fn rtok_release_assets() -> Release {
        release(&[
            "rtok-aarch64-apple-darwin.tar.xz",
            "rtok-aarch64-apple-darwin-update",
            "rtok-x86_64-unknown-linux-gnu.tar.xz",
            "rtok-x86_64-unknown-linux-gnu-update",
            "rtok-x86_64-pc-windows-msvc.zip",
            "rtok-x86_64-pc-windows-msvc-update",
            "source.tar.gz",
            "sha256.sum",
        ])
    }

    #[test]
    fn rtok_registry_include_picks_windows_msvc_zip() {
        // Regression: an include list of only darwin/linux .tar.xz made
        // `ketch install rtok` fail with "no asset for windows-x86_64" even
        // though the release published `*-pc-windows-msvc.zip`.
        let eval = evaluate_assets(
            &windows_cfg(),
            &WindowsX64Platform,
            &rtok_release_assets(),
            &rtok_registry_selector(),
        );
        assert_eq!(
            eval.scored
                .iter()
                .map(|s| s.asset.name.as_str())
                .collect::<Vec<_>>(),
            vec!["rtok-x86_64-pc-windows-msvc.zip"]
        );
    }

    #[test]
    fn rtok_registry_include_without_windows_leaves_windows_empty() {
        // The pre-fix selector that caused the Windows install failure.
        let selector = AssetSelector {
            include: vec!["*-apple-darwin.tar.xz".into(), "*-linux-gnu.tar.xz".into()],
            exclude: vec!["*-update*".into(), "source.tar.gz".into()],
            ..Default::default()
        };
        let eval = evaluate_assets(
            &windows_cfg(),
            &WindowsX64Platform,
            &rtok_release_assets(),
            &selector,
        );
        assert!(
            eval.scored.is_empty(),
            "expected no Windows pick, got {:?}",
            eval.scored
                .iter()
                .map(|s| &s.asset.name)
                .collect::<Vec<_>>()
        );
        assert!(
            eval.rejected.iter().any(|r| {
                r.name == "rtok-x86_64-pc-windows-msvc.zip"
                    && r.reason.contains("does not match any include pattern")
            }),
            "{:?}",
            eval.rejected
        );
    }

    fn sample_release() -> Release {
        release(&[
            "tool-aarch64-apple-darwin.tar.gz",
            "tool-x86_64-apple-darwin.tar.gz",
            "tool-universal-apple-darwin.tar.gz",
            "tool-aarch64-unknown-linux-gnu.tar.gz",
            "tool-x86_64-unknown-linux-gnu.tar.gz",
            "tool-aarch64-unknown-linux-musl.tar.gz",
            "tool-x86_64-unknown-linux-musl.tar.gz",
            "tool-aarch64-pc-windows-msvc.zip",
            "tool-x86_64-pc-windows-msvc.zip",
        ])
    }

    /// The asset `<os>-<arch>` picks. On a musl host that form means musl;
    /// everywhere else it means glibc. An explicit triple does not inherit.
    fn linux_os_arch<'a>(gnu: &'a str, musl: &'a str) -> &'a str {
        if cfg!(target_os = "linux") && cfg!(target_env = "musl") {
            musl
        } else {
            gnu
        }
    }

    #[test]
    fn each_supported_target_selects_its_own_asset() {
        let gnu_x64 = "tool-x86_64-unknown-linux-gnu.tar.gz";
        let musl_x64 = "tool-x86_64-unknown-linux-musl.tar.gz";
        let gnu_arm = "tool-aarch64-unknown-linux-gnu.tar.gz";
        let musl_arm = "tool-aarch64-unknown-linux-musl.tar.gz";
        let host_x64 = linux_os_arch(gnu_x64, musl_x64);
        let host_arm = linux_os_arch(gnu_arm, musl_arm);
        let cases = [
            ("macos-aarch64", "tool-aarch64-apple-darwin.tar.gz"),
            ("aarch64-apple-darwin", "tool-aarch64-apple-darwin.tar.gz"),
            ("arm64-apple-darwin", "tool-aarch64-apple-darwin.tar.gz"),
            ("macos-x86_64", "tool-x86_64-apple-darwin.tar.gz"),
            ("x86_64-apple-darwin", "tool-x86_64-apple-darwin.tar.gz"),
            ("macos-universal", "tool-universal-apple-darwin.tar.gz"),
            (
                "universal-apple-darwin",
                "tool-universal-apple-darwin.tar.gz",
            ),
            ("linux-aarch64", host_arm),
            ("aarch64-unknown-linux-gnu", gnu_arm),
            ("linux-x86_64", host_x64),
            ("x86_64-unknown-linux-gnu", gnu_x64),
            ("amd64-unknown-linux-gnu", gnu_x64),
            ("linux-x86_64-musl", musl_x64),
            ("x86_64-unknown-linux-musl", musl_x64),
            ("aarch64-unknown-linux-musl", musl_arm),
            ("windows-aarch64", "tool-aarch64-pc-windows-msvc.zip"),
            (
                "aarch64-pc-windows-msvc",
                "tool-aarch64-pc-windows-msvc.zip",
            ),
            ("windows-x86_64", "tool-x86_64-pc-windows-msvc.zip"),
            ("x86_64-pc-windows-msvc", "tool-x86_64-pc-windows-msvc.zip"),
            // `gnu` is the mingw ABI, so this must not select the Linux tarball.
            ("x86_64-pc-windows-gnu", "tool-x86_64-pc-windows-msvc.zip"),
        ];
        let cfg = config();
        let release = sample_release();
        for (target, want) in cases {
            let scored = assets_for(&cfg, &release, &AssetSelector::default(), Some(target))
                .unwrap_or_else(|e| panic!("{target}: {e}"));
            let got = scored.first().map(|s| s.asset.name.as_str());
            assert_eq!(got, Some(want), "{target}");
        }
    }

    #[test]
    fn a_manifest_pin_follows_the_asked_target() {
        // The pin names an asset scoring would reject for windows-aarch64.
        // It applies only when the config target is that platform; leaving
        // the host target in place would pick the aarch64 zip instead.
        let mut cfg = config();
        cfg.target = TargetSpec {
            os: Os::Linux,
            arch: Arch::X86_64,
        };
        let selector = AssetSelector {
            target: BTreeMap::from([(
                "windows-aarch64".to_string(),
                "*-x86_64-pc-windows-msvc.zip".to_string(),
            )]),
            ..Default::default()
        };
        let release = release(&[
            "tool-aarch64-pc-windows-msvc.zip",
            "tool-x86_64-pc-windows-msvc.zip",
        ]);
        let scored = assets_for(&cfg, &release, &selector, Some("windows-aarch64")).unwrap();
        assert_eq!(scored.len(), 1);
        assert_eq!(scored[0].asset.name, "tool-x86_64-pc-windows-msvc.zip");
        assert_eq!(scored[0].score.score, i32::MAX);
    }

    #[test]
    fn an_unknown_target_is_refused_before_scoring() {
        let err = assets_for(
            &config(),
            &release(&[]),
            &AssetSelector::default(),
            Some("beos-m68k"),
        )
        .unwrap_err();
        assert!(err.to_string().contains("beos-m68k"), "{err}");
    }

    /// A source that already knows its one release, so `explain_with` can run
    /// without the network. Download is never the point of `why`.
    struct FixedRelease(Release);

    impl crate::source::Source for FixedRelease {
        fn scheme(&self) -> &str {
            "github"
        }
        fn list_releases(
            &self,
            _id: &str,
            _opts: &crate::source::ListOpts,
        ) -> Result<Vec<Release>> {
            Ok(vec![self.0.clone()])
        }
        fn download(
            &self,
            _asset: &ReleaseAsset,
            _dest: &Path,
            _progress: &dyn crate::report::ProgressSink,
            _cancel: &crate::cancel::Cancel,
        ) -> Result<String> {
            Err(crate::error::Error::msg("why does not download"))
        }
    }

    #[test]
    fn why_for_another_target_names_that_target_and_its_asset() {
        let mut cfg = config();
        cfg.target = TargetSpec {
            os: Os::Linux,
            arch: Arch::X86_64,
        };
        let report = crate::report::Report::silent();
        let cx = crate::report::Ctx::new(&cfg, &report);
        let manifest =
            crate::model::Manifest::inferred(crate::model::PackageRef::github("acme/tool"));
        let source = FixedRelease(release(&[
            "tool-x86_64-unknown-linux-gnu.tar.gz",
            "tool-aarch64-apple-darwin.tar.gz",
        ]));
        let spec = PackageSpec::parse("tool");
        let trace = super::explain_with(
            &cx,
            &source,
            &spec,
            manifest,
            crate::model::ManifestOrigin::Inferred,
            Some(crate::model::AskedTarget::parse("macos-aarch64").unwrap()),
        )
        .unwrap();
        assert_eq!(trace.target, "macos-aarch64");
        assert_eq!(
            trace.candidate.as_ref().map(|c| c.name.as_str()),
            Some("tool-aarch64-apple-darwin.tar.gz")
        );
        assert!(trace
            .assets
            .rejected
            .iter()
            .any(|a| a.name == "tool-x86_64-unknown-linux-gnu.tar.gz"));
    }

    #[test]
    fn origin_tiers_name_the_four_manifest_sources() {
        use std::path::PathBuf;
        assert_eq!(
            vec![
                ManifestOrigin::User(PathBuf::from("manifests/tool.toml")).tier(),
                ManifestOrigin::Registry(PathBuf::from("registry/tool/ketch.toml")).tier(),
                ManifestOrigin::Builtin.tier(),
                ManifestOrigin::Inferred.tier(),
            ],
            vec!["user", "registry", "builtin", "inferred"]
        );
    }
}
