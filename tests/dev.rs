// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Dev builds on a floating tag, through the real binary.
//!
//! A `nightly` tag is republished under the same name, so what is installed
//! is told apart from what the tag names now by the build, not the version.
//! Only separate runs against a fixture that republishes between them show
//! that the build recorded at install is the one `outdated`, `upgrade` and
//! `sync` compare against later.

mod support;

use serde_json::Value;
use support::{native_name, Archive, Entry, Release, Sandbox};

const FIRST: &str = "2026-10-01T00:00:00Z";
const SECOND: &str = "2026-10-09T00:00:00Z";

fn sandbox() -> Sandbox {
    let sandbox = Sandbox::new();
    let dir = sandbox.root().join("manifests");
    std::fs::create_dir_all(&dir).expect("manifests dir");
    std::fs::write(
        dir.join("devpkg.toml"),
        "name = \"devpkg\"\nsource = \"test:devpkg\"\n",
    )
    .expect("write user manifest");
    sandbox
}

/// The `nightly` tag pointing at build `build`, beside a stable `v1.0.0`.
/// The asset keeps its name across builds, so a republish replaces its bytes.
fn publish_nightly(sandbox: &Sandbox, build: &str, published: &str) {
    let nightly = Release::new(
        "nightly",
        vec![sandbox.asset(
            &native_name("devpkg", "nightly"),
            Archive::TarGz(vec![Entry::program(
                "devpkg-nightly/bin/devpkg",
                &format!("devpkg {build}"),
            )]),
        )],
    )
    .with_tag("nightly")
    .published(published);
    let stable = Release::new(
        "1.0.0",
        vec![sandbox.asset(
            &native_name("devpkg", "1.0.0"),
            Archive::TarGz(vec![Entry::program(
                "devpkg-1.0.0/bin/devpkg",
                "devpkg 1.0.0",
            )]),
        )],
    );
    sandbox.publish("devpkg", &[nightly, stable]);
}

fn json(text: &str) -> Value {
    serde_json::from_str(text).expect("valid json")
}

fn installed(sandbox: &Sandbox) -> Value {
    json(&sandbox.state())["packages"]["devpkg"].clone()
}

fn outdated(sandbox: &Sandbox) -> Vec<Value> {
    json(&sandbox.ok(&["outdated", "--json"]))["outdated"]
        .as_array()
        .expect("outdated array")
        .clone()
}

#[test]
fn a_republished_floating_tag_is_an_upgrade_and_a_stable_release_is_not() {
    let sandbox = sandbox();
    publish_nightly(&sandbox, "one", FIRST);
    sandbox.ok(&["install", "devpkg@nightly", "-y"]);

    let info = json(&sandbox.ok(&["info", "devpkg", "--json"]));
    assert_eq!(info["channel"], "dev", "{info}");
    let first = installed(&sandbox);
    assert_eq!(first["tag"], "nightly");
    assert_eq!(first["published_at"], FIRST);
    // `v1.0.0` is newer by any version comparison, but the package follows
    // its tag, not versions.
    assert_eq!(outdated(&sandbox), Vec::<Value>::new());

    publish_nightly(&sandbox, "two", SECOND);
    let rows = outdated(&sandbox);
    assert_eq!(rows.len(), 1, "{rows:?}");
    assert_eq!(rows[0]["name"], "devpkg");

    sandbox.ok(&["upgrade", "-y"]);
    let second = installed(&sandbox);
    assert_eq!(second["tag"], "nightly");
    assert_eq!(second["channel"], "dev");
    assert_eq!(second["published_at"], SECOND);
    assert_ne!(second["sha256"], first["sha256"], "the new build is placed");
    assert_eq!(outdated(&sandbox), Vec::<Value>::new());
}

#[test]
fn installing_the_tag_again_takes_its_new_build_and_not_before() {
    let sandbox = sandbox();
    publish_nightly(&sandbox, "one", FIRST);
    sandbox.ok(&["install", "devpkg@nightly", "-y"]);
    let err = sandbox.fails(&["install", "devpkg@nightly", "-y"]);
    assert!(err.contains("already installed"), "{err}");

    publish_nightly(&sandbox, "two", SECOND);
    sandbox.ok(&["install", "devpkg@nightly", "-y"]);
    assert_eq!(installed(&sandbox)["published_at"], SECOND);
}

#[test]
fn a_lockfile_holds_a_floating_tag_to_the_build_it_recorded() {
    let sandbox = sandbox();
    publish_nightly(&sandbox, "one", FIRST);
    sandbox.ok(&["install", "devpkg@nightly", "-y"]);
    let lock = sandbox.root().join("ketch.lock");
    let lock_arg = lock.to_string_lossy().to_string();
    sandbox.ok(&["lock", "--file", &lock_arg]);
    let text = std::fs::read_to_string(&lock).expect("read lock");
    assert!(text.contains(&format!("published = \"{FIRST}\"")), "{text}");

    publish_nightly(&sandbox, "two", SECOND);
    sandbox.ok(&["uninstall", "devpkg", "-y"]);

    // Here the recorded hash is the pin.
    let err = sandbox.fails(&["sync", "--file", &lock_arg, "-y"]);
    assert!(err.contains("does not match the lockfile"), "{err}");
    assert!(installed(&sandbox).is_null());

    // Elsewhere the hash names another file, and the publication time is
    // what is left to refuse the moved tag with.
    let target = text
        .lines()
        .find_map(|line| line.strip_prefix("target = "))
        .expect("target line")
        .trim_matches('"')
        .to_string();
    let other = if target == "linux-x86_64" {
        "linux-aarch64"
    } else {
        "linux-x86_64"
    };
    std::fs::write(
        &lock,
        text.replace(
            &format!("target = \"{target}\""),
            &format!("target = \"{other}\""),
        ),
    )
    .expect("write lock");
    let err = sandbox.fails(&["sync", "--file", &lock_arg, "-y"]);
    assert!(err.contains("now names another build"), "{err}");
    assert!(err.contains("ketch lock"), "{err}");
    assert!(installed(&sandbox).is_null());
}
