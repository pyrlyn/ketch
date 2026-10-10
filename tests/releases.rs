// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `ketch releases`, `ketch info --releases` and the channel in `ketch list`,
//! through the real binary and the offline test source.
//!
//! The kind of a release (stable, pre, dev) is derived from what the source
//! reports, and "installed" from the recorded tag, so only an install followed
//! by a listing shows the two agree.

mod support;

use serde_json::Value;
use support::{native_name, Archive, Entry, Release, Sandbox};

fn release(sandbox: &Sandbox, version: &str) -> Release {
    Release::new(
        version,
        vec![sandbox.asset(
            &native_name("relpkg", version),
            Archive::TarGz(vec![Entry::program(
                &format!("relpkg-{version}/bin/relpkg"),
                &format!("relpkg {version}"),
            )]),
        )],
    )
}

/// Newest first, as a source reports them: a floating tag that is also flagged
/// prerelease, a release candidate, and two stable releases.
fn sandbox() -> Sandbox {
    let sandbox = Sandbox::new();
    let dir = sandbox.root().join("manifests");
    std::fs::create_dir_all(&dir).expect("manifests dir");
    std::fs::write(
        dir.join("relpkg.toml"),
        "name = \"relpkg\"\nsource = \"test:relpkg\"\n",
    )
    .expect("write user manifest");
    sandbox.publish(
        "relpkg",
        &[
            release(&sandbox, "nightly")
                .with_tag("nightly")
                .published("2026-10-09T01:00:00Z")
                .into_prerelease(),
            release(&sandbox, "2.0.0-rc.1")
                .published("2026-09-01T00:00:00Z")
                .into_prerelease(),
            release(&sandbox, "1.1.0").published("2026-03-04T05:06:07Z"),
            release(&sandbox, "1.0.0").published("2026-01-02T00:00:00Z"),
        ],
    );
    sandbox
}

fn json(text: &str) -> Value {
    serde_json::from_str(text).expect("valid json")
}

/// Whitespace-separated cells of each table row, header excluded.
fn rows(out: &str) -> Vec<Vec<String>> {
    out.lines()
        .skip(1)
        .map(|line| line.split_whitespace().map(str::to_string).collect())
        .collect()
}

fn tags(value: &Value) -> Vec<&str> {
    value
        .as_array()
        .expect("an array")
        .iter()
        .map(|r| r["tag"].as_str().expect("tag"))
        .collect()
}

#[test]
fn releases_lists_stable_ones_newest_first_and_marks_the_installed_one() {
    let sandbox = sandbox();
    sandbox.ok(&["install", "relpkg@1.0.0", "-y"]);

    let out = sandbox.ok(&["releases", "relpkg"]);
    assert_eq!(
        rows(&out),
        vec![
            vec!["v1.1.0", "1.1.0", "2026-03-04", "stable"],
            vec!["*", "v1.0.0", "1.0.0", "2026-01-02", "stable"],
        ]
    );
}

#[test]
fn pre_widens_the_listing_to_prereleases_and_floating_tags() {
    let sandbox = sandbox();
    let out = sandbox.ok(&["releases", "relpkg", "--pre"]);
    assert_eq!(
        rows(&out),
        vec![
            vec!["nightly", "-", "2026-10-09", "dev"],
            vec!["v2.0.0-rc.1", "2.0.0-rc.1", "2026-09-01", "pre"],
            vec!["v1.1.0", "1.1.0", "2026-03-04", "stable"],
            vec!["v1.0.0", "1.0.0", "2026-01-02", "stable"],
        ]
    );
}

#[test]
fn json_names_the_kind_and_whether_it_is_installed() {
    let sandbox = sandbox();
    sandbox.ok(&["install", "relpkg@1.0.0", "-y"]);
    let listed = json(&sandbox.ok(&["releases", "relpkg", "--pre", "--json"]));
    let entries = listed.as_array().expect("an array");
    assert_eq!(
        tags(&listed),
        ["nightly", "v2.0.0-rc.1", "v1.1.0", "v1.0.0"]
    );
    assert_eq!(entries[0]["version"], Value::Null);
    assert_eq!(entries[0]["kind"], "dev");
    assert_eq!(entries[0]["published_at"], "2026-10-09T01:00:00Z");
    assert_eq!(entries[1]["kind"], "pre");
    assert_eq!(entries[3]["version"], "1.0.0");
    assert_eq!(entries[3]["installed"], true);
    assert_eq!(entries[2]["installed"], false);
}

#[test]
fn limit_keeps_the_newest_releases() {
    let sandbox = sandbox();
    let listed = json(&sandbox.ok(&["releases", "relpkg", "--pre", "--json", "-n", "2"]));
    assert_eq!(tags(&listed), ["nightly", "v2.0.0-rc.1"]);
    assert!(!sandbox.fails(&["releases", "relpkg", "-n", "0"]).is_empty());
}

#[test]
fn a_package_installed_on_the_pre_channel_lists_prereleases_without_pre() {
    let sandbox = sandbox();
    sandbox.ok(&["install", "relpkg", "--pre", "-y"]);
    let listed = json(&sandbox.ok(&["releases", "relpkg", "--json"]));
    assert_eq!(
        tags(&listed),
        ["nightly", "v2.0.0-rc.1", "v1.1.0", "v1.0.0"]
    );
}

#[test]
fn an_unknown_package_is_an_error() {
    let sandbox = sandbox();
    assert!(!sandbox.fails(&["releases", "no-such-package"]).is_empty());
}

#[test]
fn info_releases_prints_the_same_list_under_the_details() {
    let sandbox = sandbox();
    sandbox.ok(&["install", "relpkg@1.0.0", "-y"]);

    let listing = sandbox.ok(&["releases", "relpkg"]);
    let info = sandbox.ok(&["info", "relpkg", "--releases"]);
    assert!(info.starts_with("relpkg\n"), "{info}");
    assert!(info.contains("installed"), "{info}");
    assert!(
        info.ends_with(&listing),
        "info --releases ends with the releases table:\n{info}\nvs\n{listing}"
    );
    assert!(
        !sandbox.ok(&["info", "relpkg"]).contains("published"),
        "without the flag there is no table"
    );

    let parsed = json(&sandbox.ok(&["info", "relpkg", "--releases", "--json"]));
    assert_eq!(tags(&parsed["releases"]), ["v1.1.0", "v1.0.0"]);
    assert!(json(&sandbox.ok(&["info", "relpkg", "--json"]))["releases"].is_null());
}

#[test]
fn list_shows_the_channel_of_a_package_that_is_not_stable() {
    let sandbox = sandbox();
    sandbox.ok(&["install", "relpkg", "--pre", "-y"]);

    let local = sandbox.ok(&["list", "local"]);
    assert!(
        local.contains("(pre)"),
        "the channel follows the version:\n{local}"
    );
    let parsed = json(&sandbox.ok(&["list", "local", "--json"]));
    assert_eq!(parsed[0]["channel"], "pre");
    let all = json(&sandbox.ok(&["list", "--json"]));
    assert_eq!(all["packages"][0]["channel"], "pre");

    sandbox.ok(&["install", "relpkg@1.0.0", "--force", "-y"]);
    let parsed = json(&sandbox.ok(&["list", "local", "--json"]));
    assert_eq!(parsed[0]["channel"], "stable");
    assert!(!sandbox.ok(&["list", "local"]).contains("(pre)"));
}

#[test]
fn list_pre_looks_for_prereleases_in_the_latest_column() {
    let sandbox = sandbox();
    sandbox.ok(&["install", "relpkg@1.0.0", "-y"]);

    let latest = |args: &[&str]| -> String {
        let all = json(&sandbox.ok(args));
        all["packages"][0]["latest"]
            .as_str()
            .expect("latest")
            .to_string()
    };
    assert_eq!(latest(&["list", "--json"]), "1.1.0");
    // A floating tag is never "the newest": it has no version to compare.
    assert_eq!(latest(&["list", "--pre", "--json"]), "2.0.0-rc.1");
}
