// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The workspace split: the `ketch` binary over the `ketch-core` library.
//!
//! What the split promises and nothing in the code enforces on its own: the
//! core reports the binary's version (user agent, `--version`, statistics), it
//! is never packaged or published by itself, and both crates keep the same
//! lint floor. A drift in any of these would build and pass every other test.

use std::path::Path;

fn manifest(rel: &str) -> toml::Table {
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join(rel);
    let text = std::fs::read_to_string(&path).expect("manifest is readable");
    toml::from_str(&text).expect("manifest parses")
}

fn get<'a>(table: &'a toml::Table, path: &[&str]) -> Option<&'a toml::Value> {
    let (last, parents) = path.split_last()?;
    let mut at = table;
    for key in parents {
        at = at.get(*key)?.as_table()?;
    }
    at.get(*last)
}

#[test]
fn the_core_reports_the_version_the_binary_was_built_as() {
    assert_eq!(
        ketch_core::self_update::current_version().to_string(),
        env!("CARGO_PKG_VERSION")
    );
}

#[test]
fn both_crates_inherit_one_workspace_version() {
    let root = manifest("Cargo.toml");
    let core = manifest("crates/ketch-core/Cargo.toml");
    for (name, table) in [("ketch", &root), ("ketch-core", &core)] {
        assert_eq!(
            get(table, &["package", "version", "workspace"]).and_then(|v| v.as_bool()),
            Some(true),
            "{name} must take its version from [workspace.package]"
        );
    }
    assert!(get(&root, &["workspace", "package", "version"]).is_some());
}

#[test]
fn the_core_is_never_published_or_packaged_by_dist() {
    let core = manifest("crates/ketch-core/Cargo.toml");
    assert_eq!(
        get(&core, &["package", "publish"]).and_then(|v| v.as_bool()),
        Some(false)
    );
    assert_eq!(
        get(&core, &["package", "metadata", "dist", "dist"]).and_then(|v| v.as_bool()),
        Some(false)
    );
}

#[test]
fn both_crates_forbid_unsafe_code_through_the_workspace_lints() {
    let root = manifest("Cargo.toml");
    assert_eq!(
        get(&root, &["workspace", "lints", "rust", "unsafe_code"]).and_then(|v| v.as_str()),
        Some("forbid")
    );
    let core = manifest("crates/ketch-core/Cargo.toml");
    for (name, table) in [("ketch", &root), ("ketch-core", &core)] {
        assert_eq!(
            get(table, &["lints", "workspace"]).and_then(|v| v.as_bool()),
            Some(true),
            "{name} must inherit [workspace.lints]"
        );
    }
}

/// Each optional subsystem of the core and the dependencies only it pulls in.
/// A dependency listed here that is not optional, or a feature that does not
/// name it, links it into every front end again.
const CORE_FEATURES: &[(&str, &[&str])] = &[
    ("stats", &["diesel", "diesel_migrations", "libsqlite3-sys"]),
    ("push", &["octocrab", "tokio"]),
    ("trust", &["sigstore", "minisign-verify", "pgp"]),
    ("gzip", &["flate2"]),
    ("xz", &["lzma-rust2"]),
    ("bzip2", &["bzip2"]),
    ("zip", &["zip"]),
];

#[test]
fn each_heavy_dependency_of_the_core_is_optional_behind_its_feature() {
    let core = manifest("crates/ketch-core/Cargo.toml");
    for (feature, deps) in CORE_FEATURES {
        let enables: Vec<&str> = get(&core, &["features", feature])
            .and_then(|v| v.as_array())
            .unwrap_or_else(|| panic!("ketch-core has no `{feature}` feature"))
            .iter()
            .filter_map(|v| v.as_str())
            .collect();
        for dep in *deps {
            assert_eq!(
                get(&core, &["dependencies", dep, "optional"]).and_then(|v| v.as_bool()),
                Some(true),
                "{dep} must be optional"
            );
            assert!(
                enables.contains(&format!("dep:{dep}").as_str()),
                "`{feature}` must enable {dep}"
            );
        }
    }
}

#[test]
fn the_binary_keeps_every_core_feature_and_the_ffi_chooses_its_own() {
    let core = manifest("crates/ketch-core/Cargo.toml");
    let defaults: Vec<&str> = get(&core, &["features", "default"])
        .and_then(|v| v.as_array())
        .expect("ketch-core has default features")
        .iter()
        .filter_map(|v| v.as_str())
        .collect();
    for (feature, _) in CORE_FEATURES {
        assert!(defaults.contains(feature), "`{feature}` must be a default");
    }

    let root = manifest("Cargo.toml");
    assert_ne!(
        get(&root, &["dependencies", "ketch-core", "default-features"]).and_then(|v| v.as_bool()),
        Some(false),
        "the CLI takes the core's defaults, which are everything"
    );
    let ffi = manifest("crates/ketch-ffi/Cargo.toml");
    assert_eq!(
        get(&ffi, &["dependencies", "ketch-core", "default-features"]).and_then(|v| v.as_bool()),
        Some(false),
        "ketch-ffi names the features the desktop apps use"
    );
}

#[test]
fn the_desktop_apps_do_not_link_registry_push() {
    let ffi = manifest("crates/ketch-ffi/Cargo.toml");
    let features: Vec<&str> = get(&ffi, &["dependencies", "ketch-core", "features"])
        .and_then(|v| v.as_array())
        .expect("ketch-ffi names its core features")
        .iter()
        .filter_map(|v| v.as_str())
        .collect();
    assert!(
        !features.contains(&"push"),
        "no app pushes to the registry; octocrab and tokio stay out of ketch-ffi"
    );
}

/// The move into `ketch-core` left doc examples as `ignore`, so they stopped
/// compiling and nothing noticed. A new one would do the same.
#[test]
fn core_examples_are_not_marked_ignore() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("crates");
    let mut ignored = Vec::new();
    fn visit(dir: &Path, ignored: &mut Vec<String>) {
        let entries = std::fs::read_dir(dir).expect("crates tree is readable");
        for entry in entries {
            let entry = entry.expect("crates entry");
            let path = entry.path();
            if path.is_dir() {
                visit(&path, ignored);
                continue;
            }
            if path.extension().and_then(|e| e.to_str()) != Some("rs") {
                continue;
            }
            let text = std::fs::read_to_string(&path).expect("source is readable");
            for (n, line) in text.lines().enumerate() {
                if line.trim_start().contains("```ignore") {
                    ignored.push(format!("{}:{}", path.display(), n + 1));
                }
            }
        }
    }
    visit(&root, &mut ignored);
    assert!(
        ignored.is_empty(),
        "rewrite ignored examples so they compile:\n{}",
        ignored.join("\n")
    );
}
