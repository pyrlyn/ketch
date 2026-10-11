// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The release channel an installed package follows, through the real binary.
//!
//! The channel is recorded at install and read back by `outdated`, `upgrade`
//! and `info` in later runs, so only a sequence of separate invocations shows
//! whether it survives: no unit test sees state cross a process boundary.

mod support;

use serde_json::Value;
use support::{native_name, Archive, Entry, Release, Sandbox};

fn archive(version: &str) -> Archive {
    Archive::TarGz(vec![Entry::program(
        &format!("chanpkg-{version}/bin/chanpkg"),
        &format!("chanpkg {version}"),
    )])
}

fn release(sandbox: &Sandbox, version: &str) -> Release {
    Release::new(
        version,
        vec![sandbox.asset(&native_name("chanpkg", version), archive(version))],
    )
}

fn sandbox() -> Sandbox {
    let sandbox = Sandbox::new();
    let dir = sandbox.root().join("manifests");
    std::fs::create_dir_all(&dir).expect("manifests dir");
    std::fs::write(
        dir.join("chanpkg.toml"),
        "name = \"chanpkg\"\nsource = \"test:chanpkg\"\n",
    )
    .expect("write user manifest");
    sandbox
}

fn json(text: &str) -> Value {
    serde_json::from_str(text).expect("valid json")
}

fn outdated_latest(sandbox: &Sandbox) -> Vec<String> {
    json(&sandbox.ok(&["outdated", "--json"]))["outdated"]
        .as_array()
        .expect("outdated array")
        .iter()
        .map(|row| row["latest"].as_str().expect("latest").to_string())
        .collect()
}

fn channel(sandbox: &Sandbox) -> Value {
    json(&sandbox.ok(&["info", "chanpkg", "--json"]))["channel"].clone()
}

#[test]
fn a_package_installed_with_pre_keeps_following_prereleases() {
    let sandbox = sandbox();
    sandbox.publish(
        "chanpkg",
        &[
            release(&sandbox, "2.0.0-rc.1").into_prerelease(),
            release(&sandbox, "1.0.0"),
        ],
    );
    sandbox.ok(&["install", "chanpkg", "--pre", "-y"]);
    assert_eq!(channel(&sandbox), "pre");

    sandbox.publish(
        "chanpkg",
        &[
            release(&sandbox, "2.0.0-rc.2").into_prerelease(),
            release(&sandbox, "2.0.0-rc.1").into_prerelease(),
            release(&sandbox, "1.0.0"),
        ],
    );
    assert_eq!(outdated_latest(&sandbox), ["2.0.0-rc.2"]);

    sandbox.publish(
        "chanpkg",
        &[
            release(&sandbox, "2.0.0"),
            release(&sandbox, "2.0.0-rc.2").into_prerelease(),
        ],
    );
    sandbox.ok(&["upgrade", "chanpkg", "-y"]);
    assert_eq!(
        json(&sandbox.ok(&["info", "chanpkg", "--json"]))["installed"],
        "2.0.0"
    );
    // Landing on a stable release is not leaving the channel.
    assert_eq!(channel(&sandbox), "pre");

    sandbox.publish(
        "chanpkg",
        &[
            release(&sandbox, "2.1.0-rc.1").into_prerelease(),
            release(&sandbox, "2.0.0"),
        ],
    );
    assert_eq!(outdated_latest(&sandbox), ["2.1.0-rc.1"]);
}

#[test]
fn a_package_installed_without_pre_stays_on_stable_releases() {
    let sandbox = sandbox();
    sandbox.publish(
        "chanpkg",
        &[
            release(&sandbox, "2.0.0-rc.1").into_prerelease(),
            release(&sandbox, "1.0.0"),
        ],
    );
    sandbox.ok(&["install", "chanpkg", "-y"]);
    assert_eq!(channel(&sandbox), "stable");
    assert!(
        !sandbox.state().contains("\"channel\""),
        "stable is the default and stays out of state.json"
    );
    assert!(outdated_latest(&sandbox).is_empty());
}
