// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! End-to-end: `ketch import` against recorded catalogues and a stand-in
//! GitHub, in a throwaway root.
//!
//! One local HTTP server plays every remote: the package catalogue (a
//! Homebrew cask on macOS, an AUR package on Linux, each host importing the
//! source that has builds for it), the GitHub releases API and the asset
//! download. Nothing reaches the network and the real ketch root is never
//! read. winget packages carry Windows builds only, so winget is covered by
//! the converter's unit tests rather than here.
#![cfg(any(target_os = "macos", target_os = "linux"))]

mod support;

use sha2::{Digest, Sha256};
use support::remote::Remote;
use support::{Archive, Entry, Sandbox};

/// The variables that point every remote at the one server.
fn remote_env(remote: &Remote) -> Vec<(&'static str, String)> {
    let base = &remote.base;
    vec![
        ("KETCH_IMPORT_BREW", format!("{base}/brew")),
        ("KETCH_IMPORT_ARCH", format!("{base}/arch")),
        ("KETCH_IMPORT_ARCH_GITLAB", format!("{base}/gitlab")),
        ("KETCH_IMPORT_AUR", format!("{base}/aur")),
        ("KETCH_IMPORT_WINGET_API", format!("{base}/winget-api")),
        ("KETCH_IMPORT_WINGET_RAW", format!("{base}/winget-raw")),
        ("KETCH_GITHUB_API", remote.github_api()),
    ]
}

const REPO: &str = "acme/demo";

/// One `demo` release: the archive, served for download, and its tag in
/// the releases API. Returns the asset's file name and SHA-256.
fn publish(sandbox: &Sandbox, remote: &Remote, version: &str) -> (String, String) {
    let file = format!("demo-{version}-{}.tar.gz", host_tokens());
    let path = sandbox.fixture(&file);
    Archive::TarGz(vec![Entry::program(
        &format!("demo-{version}/demo"),
        &format!("demo {version}"),
    )])
    .write_to(&path);
    let bytes = std::fs::read(&path).expect("read asset");
    let sha = hex::encode(Sha256::digest(&bytes));
    remote.serve(&format!("/dl/{file}"), bytes);
    let release = serde_json::json!({
        "tag_name": format!("v{version}"),
        "name": format!("v{version}"),
        "prerelease": false,
        "draft": false,
        "assets": [{
            "name": file,
            "browser_download_url": format!("{}/dl/{file}", remote.base),
            "size": 1,
            "digest": format!("sha256:{sha}"),
        }],
    });
    remote.serve(
        &format!("/gh/repos/{REPO}/releases/tags/v{version}"),
        release.to_string(),
    );
    (file, sha)
}

fn host_tokens() -> String {
    let os = if cfg!(target_os = "macos") {
        "darwin"
    } else {
        "linux"
    };
    format!("{os}-{}", support::host_arch())
}

/// The catalogue entry for this host's source, describing `version`.
fn catalogue(remote: &Remote, version: &str, file: &str, sha: Option<&str>) {
    let url = format!("https://github.com/{REPO}/releases/download/v{version}/{file}");
    if cfg!(target_os = "macos") {
        let cask = serde_json::json!({
            "token": "demo",
            "version": version,
            "url": url,
            "sha256": sha.unwrap_or("no_check"),
            "artifacts": [{"binary": ["demo"]}],
            "variations": {},
        });
        remote.serve("/brew/cask/demo.json", cask.to_string());
    } else {
        remote.serve(
            "/aur/rpc/v5/info?arg[]=demo&arg[]=demo-bin",
            r#"{"resultcount":1,"results":[{"Name":"demo-bin","PackageBase":"demo-bin"}],"type":"multiinfo","version":5}"#,
        );
        let sums = match sha {
            Some(sha) => format!("\tsha256sums = {sha}\n"),
            None => "\tsha256sums = SKIP\n".to_string(),
        };
        remote.serve(
            "/aur/cgit/aur.git/plain/.SRCINFO?h=demo-bin",
            format!(
                "pkgbase = demo-bin\n\tpkgver = {version}\n\tpkgrel = 1\n\
                 \turl = https://github.com/{REPO}\n\tarch = x86_64\n\tarch = aarch64\n\
                 \tsource = {url}\n{sums}\npkgname = demo-bin\n"
            ),
        );
    }
}

fn source() -> &'static str {
    if cfg!(target_os = "macos") {
        "brew"
    } else {
        "linux"
    }
}

fn run(sandbox: &Sandbox, remote: &Remote, extra: &[&str]) -> std::process::Output {
    let mut args = vec!["import", source(), "demo"];
    args.extend_from_slice(extra);
    let env = remote_env(remote);
    let env: Vec<(&str, &str)> = env.iter().map(|(k, v)| (*k, v.as_str())).collect();
    sandbox.ketch_overrides(&args, &env)
}

fn ok(out: &std::process::Output) -> String {
    let text = format!(
        "{}{}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr)
    );
    assert!(out.status.success(), "import failed: {text}");
    text
}

fn manifest(sandbox: &Sandbox) -> std::path::PathBuf {
    sandbox.root().join("manifests").join("demo.toml")
}

fn installed_says(sandbox: &Sandbox) -> String {
    let out = std::process::Command::new(sandbox.bin().join("demo"))
        .output()
        .expect("run demo");
    String::from_utf8_lossy(&out.stdout).trim().to_string()
}

#[test]
fn an_import_writes_a_minimal_manifest_and_installs_the_package() {
    let sandbox = Sandbox::new();
    let remote = Remote::start();
    let (file, sha) = publish(&sandbox, &remote, "1.0.0");
    catalogue(&remote, "1.0.0", &file, Some(&sha));

    ok(&run(&sandbox, &remote, &[]));

    let written = std::fs::read_to_string(manifest(&sandbox)).expect("manifest written");
    assert!(
        written.starts_with("# Written by `ketch import"),
        "{written}"
    );
    assert!(
        written.contains(&format!("source = \"github:{REPO}\"")),
        "{written}"
    );
    assert!(
        !written.contains("sha256"),
        "checksums stay out of the file"
    );
    assert_eq!(installed_says(&sandbox), "demo 1.0.0");
    assert!(
        sandbox.state().contains(&sha),
        "the catalogue's checksum was used"
    );
}

#[test]
fn a_second_run_with_nothing_new_changes_nothing() {
    let sandbox = Sandbox::new();
    let remote = Remote::start();
    let (file, sha) = publish(&sandbox, &remote, "1.0.0");
    catalogue(&remote, "1.0.0", &file, Some(&sha));
    ok(&run(&sandbox, &remote, &[]));
    let before = std::fs::read_to_string(manifest(&sandbox)).unwrap();
    let state = sandbox.state();

    let text = ok(&run(&sandbox, &remote, &[]));

    assert!(text.contains("Everything is up to date"), "{text}");
    assert_eq!(std::fs::read_to_string(manifest(&sandbox)).unwrap(), before);
    assert_eq!(sandbox.state(), state, "nothing was reinstalled");
}

#[test]
fn a_new_upstream_version_updates_the_manifest_and_the_install() {
    let sandbox = Sandbox::new();
    let remote = Remote::start();
    let (file, sha) = publish(&sandbox, &remote, "1.0.0");
    catalogue(&remote, "1.0.0", &file, Some(&sha));
    ok(&run(&sandbox, &remote, &[]));

    let (file, sha) = publish(&sandbox, &remote, "1.1.0");
    catalogue(&remote, "1.1.0", &file, Some(&sha));
    let text = ok(&run(&sandbox, &remote, &[]));

    assert!(!text.contains("Everything is up to date"), "{text}");
    assert_eq!(installed_says(&sandbox), "demo 1.1.0");
    assert!(sandbox.state().contains("v1.1.0"));
}

#[test]
fn a_package_off_github_releases_is_refused_and_nothing_is_written() {
    let sandbox = Sandbox::new();
    let remote = Remote::start();
    let (file, sha) = publish(&sandbox, &remote, "1.0.0");
    catalogue(&remote, "1.0.0", &file, Some(&sha));
    // The same catalogue entry, but downloading from a vendor's own host.
    for (target, body) in remote.routes() {
        let text = String::from_utf8_lossy(&body).replace(
            &format!("https://github.com/{REPO}/releases/download/v1.0.0/"),
            "https://downloads.acme.example/",
        );
        remote.serve(&target, text.into_bytes());
    }

    let out = run(&sandbox, &remote, &[]);

    assert!(!out.status.success());
    assert_eq!(
        String::from_utf8_lossy(&out.stderr).trim(),
        "demo can't be converted: it is not distributed through GitHub Releases, \
         and that is not supported yet."
    );
    assert!(!manifest(&sandbox).exists());
    assert!(!sandbox.state().contains("demo"));
}

#[test]
fn a_name_no_catalogue_knows_fails_without_writing() {
    let sandbox = Sandbox::new();
    let remote = Remote::start();
    remote.clear();

    let out = run(&sandbox, &remote, &[]);

    assert!(!out.status.success());
    assert!(
        String::from_utf8_lossy(&out.stderr).contains("demo"),
        "{}",
        String::from_utf8_lossy(&out.stderr)
    );
    assert!(!manifest(&sandbox).exists());
}

#[test]
fn a_dry_run_prints_the_manifest_and_touches_nothing() {
    let sandbox = Sandbox::new();
    let remote = Remote::start();
    let (file, sha) = publish(&sandbox, &remote, "1.0.0");
    catalogue(&remote, "1.0.0", &file, Some(&sha));

    let out = run(&sandbox, &remote, &["--dry-run"]);

    let stdout = String::from_utf8_lossy(&out.stdout).to_string();
    ok(&out);
    assert!(
        stdout.contains(&format!("source = \"github:{REPO}\"")),
        "{stdout}"
    );
    assert!(!manifest(&sandbox).exists());
    assert!(!sandbox.bin().join("demo").exists());
}

#[test]
fn a_manifest_written_by_hand_is_left_alone() {
    let sandbox = Sandbox::new();
    let remote = Remote::start();
    let (file, sha) = publish(&sandbox, &remote, "1.0.0");
    catalogue(&remote, "1.0.0", &file, Some(&sha));
    let own = "name = \"demo\"\nsource = \"github:me/my-demo\"\n";
    std::fs::create_dir_all(manifest(&sandbox).parent().unwrap()).unwrap();
    std::fs::write(manifest(&sandbox), own).unwrap();

    let out = run(&sandbox, &remote, &[]);

    assert!(!out.status.success());
    assert!(String::from_utf8_lossy(&out.stderr).contains("was not written by `ketch import`"));
    assert_eq!(std::fs::read_to_string(manifest(&sandbox)).unwrap(), own);
}

#[test]
fn a_definition_without_a_checksum_still_installs_with_a_warning() {
    let sandbox = Sandbox::new();
    let remote = Remote::start();
    let (file, _) = publish(&sandbox, &remote, "1.0.0");
    catalogue(&remote, "1.0.0", &file, None);

    let text = ok(&run(&sandbox, &remote, &[]));

    assert!(text.contains("records no checksum"), "{text}");
    assert_eq!(installed_says(&sandbox), "demo 1.0.0");
}
