// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Draft releases through the real binary and a stand-in GitHub API.
//!
//! Drafts are GitHub's alone — source plugins never see them — so the fixture
//! plugin cannot play this part: a local server answers as the API does for a
//! token with push access, and records every request, so the tests can see
//! where the token went.

mod support;

use serde_json::{json, Value};
use support::remote::Remote;
use support::{native_name, Archive, Entry, Sandbox};

const REPO: &str = "acme/demo";
const TOKEN: &str = "draft-test-token";

struct Fixture {
    sandbox: Sandbox,
    remote: Remote,
}

impl Fixture {
    fn new(push: bool) -> Fixture {
        let fixture = Fixture {
            sandbox: Sandbox::new(),
            remote: Remote::start(),
        };
        fixture.remote.serve(
            &format!("/gh/repos/{REPO}"),
            json!({"full_name": REPO, "permissions": {"push": push}}).to_string(),
        );
        fixture
    }

    /// One release in the API's shape, its asset served from both URLs a
    /// published release has; a draft's public URL is left a 404, as it is
    /// on GitHub until the draft is published.
    fn release(&self, version: &str, id: u32, draft: bool) -> Value {
        let file = native_name("demo", version);
        let path = self.sandbox.fixture(&file);
        Archive::TarGz(vec![Entry::program(
            &format!("demo-{version}/demo"),
            &format!("demo {version}"),
        )])
        .write_to(&path);
        let bytes = std::fs::read(&path).expect("read asset");
        let sha = {
            use sha2::Digest;
            hex::encode(sha2::Sha256::digest(&bytes))
        };
        let api_url = format!(
            "{}/repos/{REPO}/releases/assets/{id}",
            self.remote.github_api()
        );
        self.remote.serve(
            &format!("/gh/repos/{REPO}/releases/assets/{id}"),
            bytes.clone(),
        );
        let public = format!("/dl/v{version}/{file}");
        if draft {
            self.remote.forget(&public);
        } else {
            self.remote.serve(&public, bytes);
        }
        json!({
            "tag_name": format!("v{version}"),
            "draft": draft,
            "prerelease": false,
            "published_at": if draft { Value::Null } else { json!("2026-10-10T00:00:00Z") },
            "assets": [{
                "name": file,
                "browser_download_url": format!("{}{public}", self.remote.base),
                "url": api_url,
                "size": 1,
                "digest": format!("sha256:{sha}"),
            }],
        })
    }

    /// What the release listing returns, newest first. The newest published
    /// one is also `latest`, as GitHub answers it.
    fn list(&self, releases: &[Value]) {
        // 30 is a resolve's page, 20 a listing's.
        for per_page in [30, 20] {
            self.remote.serve(
                &format!("/gh/repos/{REPO}/releases?per_page={per_page}&page=1"),
                Value::Array(releases.to_vec()).to_string(),
            );
        }
        match releases.iter().find(|r| r["draft"] == false) {
            Some(latest) => self.remote.serve(
                &format!("/gh/repos/{REPO}/releases/latest"),
                latest.to_string(),
            ),
            None => self
                .remote
                .forget(&format!("/gh/repos/{REPO}/releases/latest")),
        }
    }

    fn run(&self, args: &[&str], token: bool) -> std::process::Output {
        let api = self.remote.github_api();
        let mut env = vec![("KETCH_GITHUB_API", api.as_str())];
        if token {
            env.push(("KETCH_GITHUB_TOKEN", TOKEN));
        }
        self.sandbox.ketch_overrides(args, &env)
    }

    fn ok(&self, args: &[&str], token: bool) -> (String, String) {
        let out = self.run(args, token);
        let (stdout, stderr) = (
            String::from_utf8_lossy(&out.stdout).to_string(),
            String::from_utf8_lossy(&out.stderr).to_string(),
        );
        assert!(
            out.status.success(),
            "`ketch {}` failed\n--- stdout ---\n{stdout}\n--- stderr ---\n{stderr}",
            args.join(" ")
        );
        (stdout, stderr)
    }

    fn fails(&self, args: &[&str], token: bool) -> String {
        let out = self.run(args, token);
        assert!(
            !out.status.success(),
            "`ketch {}` was expected to fail",
            args.join(" ")
        );
        String::from_utf8_lossy(&out.stderr).to_string()
    }

    /// `ketch list --json`'s row for the package, asked afresh: the latest
    /// cache would otherwise answer from the previous listing.
    fn listed(&self, args: &[&str]) -> Value {
        let _ = std::fs::remove_file(self.sandbox.root().join("cache").join("latest.json"));
        let mut all = vec!["list", "--json"];
        all.extend_from_slice(args);
        let out: Value = serde_json::from_str(&self.ok(&all, true).0).expect("list json");
        out["packages"]
            .as_array()
            .and_then(|rows| rows.iter().find(|r| r["name"] == "demo"))
            .cloned()
            .unwrap_or_else(|| panic!("demo is listed: {out}"))
    }

    fn tags(&self, args: &[&str], token: bool) -> Vec<String> {
        let out: Value = serde_json::from_str(&self.ok(args, token).0).expect("releases json");
        let rows = out.as_array().cloned().unwrap_or_default();
        rows.iter()
            .map(|r| {
                format!(
                    "{} {}",
                    r["tag"].as_str().unwrap_or("?"),
                    r["kind"].as_str().unwrap_or("?")
                )
            })
            .collect()
    }

    fn info(&self) -> Value {
        serde_json::from_str(&self.ok(&["info", "demo", "--json"], false).0).expect("info json")
    }
}

#[test]
fn asking_for_a_draft_without_a_token_names_the_token() {
    let fx = Fixture::new(true);
    let draft = fx.release("2.0.0", 2, true);
    fx.list(&[draft]);
    let err = fx.fails(&["install", REPO, "--draft", "-y"], false);
    assert!(err.contains("GITHUB_TOKEN"), "{err}");
    assert!(err.contains("push access"), "{err}");
    assert!(!fx.sandbox.state().contains("\"demo\""));
}

#[test]
fn a_token_without_push_access_is_refused_for_that_reason() {
    let fx = Fixture::new(false);
    let draft = fx.release("2.0.0", 2, true);
    fx.list(&[draft]);
    let err = fx.fails(&["install", REPO, "--draft", "-y"], true);
    assert!(err.contains("no push access"), "{err}");
}

#[test]
fn a_draft_installs_through_the_api_and_its_publication_is_an_upgrade() {
    let fx = Fixture::new(true);
    let stable = fx.release("1.0.0", 1, false);
    let draft = fx.release("2.0.0", 2, true);
    fx.list(&[draft, stable.clone()]);

    // Without --draft the draft is never chosen, token or not.
    fx.ok(&["install", REPO, "-y"], true);
    assert_eq!(fx.info()["installed"], "v1.0.0");

    let before = fx.remote.seen().len();
    fx.ok(&["install", REPO, "--draft", "-y"], true);
    let info = fx.info();
    assert_eq!(info["installed"], "v2.0.0");
    assert_eq!(info["channel"], "draft");
    assert!(fx.sandbox.state().contains("\"draft\": true"));

    let seen = &fx.remote.seen()[before..];
    let asset = seen
        .iter()
        .find(|r| r.target == format!("/gh/repos/{REPO}/releases/assets/2"))
        .expect("the draft's asset came through the API");
    assert_eq!(
        asset.authorization.as_deref(),
        Some(&*format!("Bearer {TOKEN}"))
    );
    assert_eq!(asset.accept.as_deref(), Some("application/octet-stream"));
    assert!(
        seen.iter().all(|r| r.target.starts_with("/gh/")),
        "a draft install talks to the API host only: {seen:#?}"
    );

    // Published under the same tag: the release the draft previewed.
    let published = fx.release("2.0.0", 2, false);
    fx.list(&[published, stable]);
    let (out, _) = fx.ok(&["outdated", "--json"], true);
    let report: Value = serde_json::from_str(&out).expect("outdated json");
    assert_eq!(report["outdated"][0]["name"], "demo", "{report}");
    fx.ok(&["upgrade", "-y"], true);
    let info = fx.info();
    assert_eq!(info["installed"], "v2.0.0");
    assert_eq!(info["channel"], "draft", "the channel is kept");
    assert!(!fx.sandbox.state().contains("\"draft\": true"));
    let (out, _) = fx.ok(&["outdated", "--json"], true);
    let report: Value = serde_json::from_str(&out).expect("outdated json");
    assert_eq!(report["outdated"], json!([]), "{report}");
}

#[test]
fn a_deleted_draft_is_a_warning_not_an_error() {
    let fx = Fixture::new(true);
    let stable = fx.release("1.0.0", 1, false);
    let draft = fx.release("2.0.0", 2, true);
    fx.list(&[draft, stable.clone()]);
    fx.ok(&["install", REPO, "--draft", "-y"], true);

    fx.list(&[stable]);
    let (out, err) = fx.ok(&["outdated", "--json"], true);
    let report: Value = serde_json::from_str(&out).expect("outdated json");
    assert_eq!(report["outdated"], json!([]), "{report}");
    assert!(err.contains("draft release v2.0.0"), "{err}");
    assert!(err.contains("is gone"), "{err}");
}

#[test]
fn releases_lists_drafts_only_when_asked_and_allowed() {
    let fx = Fixture::new(true);
    let stable = fx.release("1.0.0", 1, false);
    let draft = fx.release("2.0.0", 2, true);
    fx.list(&[draft, stable]);

    assert_eq!(
        fx.tags(&["releases", REPO, "--json"], true),
        vec!["v1.0.0 stable"]
    );
    let err = fx.fails(&["releases", REPO, "--draft"], false);
    assert!(err.contains("GITHUB_TOKEN"), "{err}");
    assert_eq!(
        fx.tags(&["releases", REPO, "--draft", "--json"], true),
        vec!["v2.0.0 draft", "v1.0.0 stable"]
    );
    let (out, _) = fx.ok(&["info", REPO, "--releases", "--draft"], true);
    assert!(out.contains("v2.0.0"), "{out}");

    // A draft install asks for drafts by itself.
    fx.ok(&["install", REPO, "--draft", "-y"], true);
    assert_eq!(
        fx.tags(&["releases", "demo", "--json"], true),
        vec!["v2.0.0 draft", "v1.0.0 stable"]
    );
    let err = fx.fails(&["releases", "demo"], false);
    assert!(err.contains("GITHUB_TOKEN"), "{err}");
}

#[test]
fn list_agrees_with_outdated_about_drafts() {
    let fx = Fixture::new(true);
    let stable = fx.release("1.0.0", 1, false);
    let draft = fx.release("2.0.0", 2, true);
    fx.list(&[draft, stable.clone()]);
    fx.ok(&["install", REPO, "-y"], true);

    let row = fx.listed(&[]);
    assert_eq!(row["latest"], "v1.0.0", "{row}");
    assert_eq!(row["update_available"], false, "{row}");
    let row = fx.listed(&["--draft"]);
    assert_eq!(row["latest"], "v2.0.0", "{row}");
    assert_eq!(row["update_available"], true, "{row}");

    // On the draft channel, the draft's own publication is the update.
    fx.ok(&["install", REPO, "--draft", "-y"], true);
    assert_eq!(fx.listed(&[])["update_available"], false);
    fx.list(&[fx.release("2.0.0", 2, false), stable]);
    let row = fx.listed(&[]);
    assert_eq!(row["update_available"], true, "{row}");
    let (out, _) = fx.ok(&["outdated", "--json"], true);
    let report: Value = serde_json::from_str(&out).expect("outdated json");
    assert_eq!(report["outdated"][0]["name"], "demo", "{report}");
}
