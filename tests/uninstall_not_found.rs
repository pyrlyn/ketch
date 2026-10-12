// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `ketch uninstall` of a name that is not installed: one line per name, exit
//! 4, nothing removed.

mod support;

use support::Sandbox;

fn stderr_of(out: &std::process::Output) -> String {
    String::from_utf8_lossy(&out.stderr).into_owned()
}

#[test]
fn a_name_that_is_not_installed_prints_one_line_and_exits_4() {
    let sandbox = Sandbox::new();

    let out = sandbox.ketch(&["uninstall", "nope", "--yes"]);

    assert_eq!(out.status.code(), Some(4));
    assert_eq!(stderr_of(&out), "nope: not found\n");
    assert!(out.stdout.is_empty());
}

#[test]
fn every_missing_name_is_reported() {
    let sandbox = Sandbox::new();

    let out = sandbox.ketch(&["uninstall", "one", "two", "--yes"]);

    assert_eq!(out.status.code(), Some(4));
    assert_eq!(stderr_of(&out), "one: not found\ntwo: not found\n");
}

#[test]
fn a_store_folder_with_no_record_is_removed_and_still_not_found() {
    let sandbox = Sandbox::new();
    let leftover = sandbox.store().join("ghost");
    std::fs::create_dir_all(leftover.join("1.0.0.old")).expect("plant leftover");

    let out = sandbox.ketch(&["uninstall", "ghost", "--yes"]);

    assert_eq!(out.status.code(), Some(4));
    assert_eq!(stderr_of(&out), "ghost: not found\n");
    assert!(!leftover.exists(), "the leftover folder survived");
}

#[test]
fn a_name_that_differs_only_in_case_never_removes_the_real_folder() {
    let sandbox = Sandbox::new();
    let installed = sandbox.store().join("ghost");
    std::fs::create_dir_all(installed.join("1.0.0")).expect("plant folder");

    // On a case-insensitive file system `store/GHOST` is `store/ghost`.
    let out = sandbox.ketch(&["uninstall", "GHOST", "--yes"]);

    assert_eq!(out.status.code(), Some(4));
    assert_eq!(stderr_of(&out), "GHOST: not found\n");
    assert!(
        installed.join("1.0.0").is_dir(),
        "the real folder was removed"
    );
}

#[test]
fn a_leftover_folder_is_kept_unless_the_removal_is_confirmed() {
    let sandbox = Sandbox::new();
    let leftover = sandbox.store().join("ghost");
    std::fs::create_dir_all(leftover.join("1.0.0.old")).expect("plant leftover");

    let out = sandbox.ketch(&["uninstall", "ghost"]);

    assert_eq!(out.status.code(), Some(4));
    assert!(
        stderr_of(&out).contains("ghost: not found"),
        "{}",
        stderr_of(&out)
    );
    assert!(leftover.is_dir(), "removed without confirmation");
}
