// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Linux pipeline coverage: the real binary against a throwaway root.
//!
//! Compiles and runs only on Linux (`cargo test` on a Linux host or runner).
#![cfg(target_os = "linux")]

mod support;

use support::{host_arch, Archive, Entry, Release, Sandbox};

fn tool_archive(version: &str) -> Archive {
    Archive::TarGz(vec![
        Entry::program(
            &format!("testtool-{version}/bin/testtool"),
            &format!("testtool {version}"),
        ),
        Entry::file(&format!("testtool-{version}/README.md"), "# testtool\n"),
    ])
}

fn publish_tool(sandbox: &Sandbox, version: &str) {
    let arch = host_arch();
    let native = sandbox.asset(
        &format!("testtool-{version}-{arch}-unknown-linux-gnu.tar.gz"),
        tool_archive(version),
    );
    let macos = sandbox.asset(
        &format!("testtool-{version}-{arch}-apple-darwin.tar.gz"),
        tool_archive("macos-decoy"),
    );
    sandbox.publish("testtool", &[Release::new(version, vec![macos, native])]);
}

fn run(path: &std::path::Path) -> String {
    let out = std::process::Command::new(path)
        .output()
        .expect("run installed program");
    assert!(out.status.success(), "{} did not run", path.display());
    String::from_utf8_lossy(&out.stdout).trim().to_string()
}

#[test]
fn a_tool_is_downloaded_verified_linked_and_runnable() {
    let sandbox = Sandbox::new();
    publish_tool(&sandbox, "1.0.0");
    sandbox.ok(&["install", "test:testtool", "--yes"]);

    let link = sandbox.bin().join("testtool");
    assert!(
        link.is_symlink(),
        "expected a symlink at {}",
        link.display()
    );
    assert_eq!(run(&link), "testtool 1.0.0");

    let listed = sandbox.state();
    assert!(listed.contains(r#""name": "testtool""#), "{listed}");
    assert!(listed.contains(r#""checksum_verified": true"#), "{listed}");
    assert!(
        listed.contains(&format!(
            "testtool-1.0.0-{}-unknown-linux-gnu.tar.gz",
            host_arch()
        )),
        "{listed}"
    );
}

#[test]
fn an_upgrade_replaces_the_payload_and_the_link_still_works() {
    let sandbox = Sandbox::new();
    publish_tool(&sandbox, "1.0.0");
    sandbox.ok(&["install", "test:testtool@1.0.0", "--yes"]);
    let link = sandbox.bin().join("testtool");
    assert_eq!(run(&link), "testtool 1.0.0");

    publish_tool(&sandbox, "2.0.0");
    sandbox.ok(&["upgrade", "--yes"]);
    assert_eq!(run(&link), "testtool 2.0.0");
    assert!(
        sandbox.store().join("testtool").join("1.0.0").is_dir(),
        "upgrade must keep the previous prefix"
    );
}

#[test]
fn rollback_restores_the_previous_prefix_without_redownloading() {
    let sandbox = Sandbox::new();
    publish_tool(&sandbox, "1.0.0");
    sandbox.ok(&["install", "test:testtool@1.0.0", "--yes"]);
    publish_tool(&sandbox, "2.0.0");
    sandbox.ok(&["upgrade", "--yes"]);
    publish_tool(&sandbox, "2.0.0");
    sandbox.ok(&["rollback", "testtool"]);
    assert_eq!(run(&sandbox.bin().join("testtool")), "testtool 1.0.0");
}

#[test]
fn rollback_without_a_retained_version_leaves_the_install_alone() {
    let sandbox = Sandbox::new();
    publish_tool(&sandbox, "1.0.0");
    sandbox.ok(&["install", "test:testtool", "--yes"]);
    let stderr = sandbox.fails(&["rollback", "testtool"]);
    assert!(stderr.contains("no retained version"), "{stderr}");
}

#[test]
fn rollback_refuses_a_pinned_package() {
    let sandbox = Sandbox::new();
    publish_tool(&sandbox, "1.0.0");
    sandbox.ok(&["install", "test:testtool@1.0.0", "--yes"]);
    publish_tool(&sandbox, "2.0.0");
    sandbox.ok(&["upgrade", "--yes"]);
    sandbox.ok(&["pin", "testtool"]);
    let stderr = sandbox.fails(&["rollback", "testtool"]);
    assert!(stderr.to_lowercase().contains("pinned"), "{stderr}");
}

#[test]
fn uninstall_after_rollback_removes_every_retained_prefix() {
    let sandbox = Sandbox::new();
    publish_tool(&sandbox, "1.0.0");
    sandbox.ok(&["install", "test:testtool@1.0.0", "--yes"]);
    publish_tool(&sandbox, "2.0.0");
    sandbox.ok(&["upgrade", "--yes"]);
    sandbox.ok(&["rollback", "testtool"]);
    sandbox.ok(&["uninstall", "testtool", "--yes"]);
    assert!(!sandbox.store().join("testtool").exists());
}

#[test]
fn a_download_that_does_not_match_its_checksum_installs_nothing() {
    let sandbox = Sandbox::new();
    let arch = host_arch();
    let asset = sandbox
        .asset(
            &format!("testtool-1.0.0-{arch}-unknown-linux-gnu.tar.gz"),
            tool_archive("1.0.0"),
        )
        .with_wrong_digest();
    sandbox.publish("testtool", &[Release::new("1.0.0", vec![asset])]);
    sandbox.fails(&["install", "test:testtool", "--yes"]);
    assert!(!sandbox.bin().join("testtool").exists());
}

#[test]
fn a_binary_the_user_put_there_is_never_overwritten() {
    let sandbox = Sandbox::new();
    publish_tool(&sandbox, "1.0.0");
    std::fs::create_dir_all(sandbox.bin()).unwrap();
    std::fs::write(sandbox.bin().join("testtool"), b"mine").unwrap();
    let err = sandbox.fails(&["install", "test:testtool", "--yes"]);
    assert!(err.contains("already exists"), "{err}");
    assert_eq!(
        std::fs::read(sandbox.bin().join("testtool")).unwrap(),
        b"mine"
    );
}

#[test]
fn relink_rebuilds_a_link_that_was_deleted_by_hand() {
    let sandbox = Sandbox::new();
    publish_tool(&sandbox, "1.0.0");
    sandbox.ok(&["install", "test:testtool", "--yes"]);
    let link = sandbox.bin().join("testtool");
    std::fs::remove_file(&link).unwrap();
    sandbox.ok(&["link", "testtool"]);
    assert_eq!(run(&link), "testtool 1.0.0");
}

#[test]
fn uninstall_removes_every_trace_of_a_tool() {
    let sandbox = Sandbox::new();
    publish_tool(&sandbox, "1.0.0");
    sandbox.ok(&["install", "test:testtool", "--yes"]);
    sandbox.ok(&["uninstall", "testtool", "--yes"]);
    assert!(!sandbox.bin().join("testtool").exists());
    assert!(sandbox.ok(&["list", "local"]).contains("nothing installed"));
}

fn appimage_bytes() -> Vec<u8> {
    let squashfs = include_bytes!("fixtures/appimage-diricon.sqfs");
    let mut bytes = vec![0u8; 120];
    bytes[0..4].copy_from_slice(b"\x7fELF");
    bytes[4] = 2;
    bytes[5] = 1;
    bytes[6] = 1;
    bytes[8..11].copy_from_slice(b"AI\x02");
    bytes[16] = 2;
    bytes[18] = 0x3e;
    bytes[20] = 1;
    bytes[32] = 64;
    bytes[52] = 64;
    bytes[54] = 56;
    bytes[56] = 1;
    bytes[64] = 1;
    bytes[68] = 5;
    bytes[96] = 120;
    bytes[104] = 120;
    bytes.extend_from_slice(squashfs);
    bytes
}

#[test]
fn an_appimage_declared_as_an_app_is_placed_and_removed() {
    let sandbox = Sandbox::new();
    let xdg = sandbox.fixture("xdg");
    std::fs::create_dir_all(&xdg).unwrap();
    sandbox.registry_package(
        "demo",
        "name = \"demo\"\nsource = \"test:demo\"\nkind = \"app\"\n",
    );
    let image = sandbox.fixture("Demo.AppImage");
    std::fs::write(&image, appimage_bytes()).unwrap();
    let arch = host_arch();
    let asset = sandbox.file_asset(&format!("demo-1.0.0-{arch}.AppImage"), &image);
    let decoy = sandbox.asset(
        &format!("demo-1.0.0-{arch}-apple-darwin.tar.gz"),
        Archive::TarGz(vec![Entry::program("demo", "decoy")]),
    );
    sandbox.publish("demo", &[Release::new("1.0.0", vec![decoy, asset])]);

    let xdg_text = xdg.to_str().expect("xdg path is unicode");
    sandbox.ok_env(
        &["install", "test:demo", "--yes"],
        &[("XDG_DATA_HOME", xdg_text)],
    );

    let desktop = xdg.join("applications/demo.desktop");
    let text = std::fs::read_to_string(&desktop).unwrap();
    assert!(text.contains("Type=Application"), "{text}");
    assert!(text.contains("X-Ketch-Package=demo"), "{text}");
    assert!(text.contains(".AppImage"), "{text}");
    let icon = xdg.join("icons/hicolor/256x256/apps/demo.png");
    assert_eq!(
        std::fs::read(&icon).unwrap(),
        include_bytes!("fixtures/appimage-diricon.png")
    );
    let stored = sandbox
        .store()
        .join("demo/1.0.0")
        .join(format!("demo-1.0.0-{arch}.AppImage"));
    assert!(stored.is_file(), "missing {}", stored.display());
    assert!(!sandbox.bin().join("demo").exists());
    let state = sandbox.state();
    assert!(state.contains("demo.desktop"), "{state}");
    assert!(state.contains("\"role\": \"desktop\""), "{state}");
    assert!(state.contains("\"role\": \"icon\""), "{state}");

    let other = xdg.join("applications/other.desktop");
    std::fs::write(&other, "[Desktop Entry]\nName=Other\n").unwrap();
    sandbox.ok_env(
        &["uninstall", "demo", "--yes"],
        &[("XDG_DATA_HOME", xdg_text)],
    );
    assert!(!desktop.exists());
    assert!(!icon.exists());
    assert!(other.is_file());
    assert!(!sandbox.store().join("demo").exists());
}

#[test]
fn doctor_reports_a_healthy_tree() {
    let sandbox = Sandbox::new();
    let out = sandbox.ok(&["doctor"]);
    assert!(out.contains("writable"), "{out}");
    assert!(!out.to_ascii_lowercase().contains("macos only"), "{out}");
}

#[test]
fn path_install_edits_a_shell_config_and_can_undo_itself() {
    let sandbox = Sandbox::new();
    sandbox.ok(&["path", "install", "--shell", "bash"]);
    let bashrc = sandbox.home().join(".bashrc");
    let text = std::fs::read_to_string(&bashrc).unwrap();
    assert!(text.contains("# >>> ketch >>>"), "{text}");
    sandbox.ok(&["path", "uninstall", "--shell", "bash"]);
    let text = std::fs::read_to_string(&bashrc).unwrap();
    assert!(!text.contains("# >>> ketch >>>"), "{text}");
}
