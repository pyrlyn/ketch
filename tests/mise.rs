// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! A ketch that `mise use -g github:pyrlyn/ketch` installed: the real binary,
//! copied into a sandboxed mise tree and run from there. Copies installed
//! before the move from listepo still live in `github-listepo-ketch`, and
//! mise only forgets them under that name.
//!
//! Every OS, because the part most likely to break is Windows: mise has to
//! delete the directory holding the very image that asked it to.

mod support;

use support::{Sandbox, MISE_TOOL_DIRS};

#[test]
fn a_mise_owned_ketch_will_not_rewrite_itself_in_place() {
    let sandbox = Sandbox::new();
    let exe = sandbox.install_with_mise();
    let (mise_bin, mise_log) = sandbox.fake_mise();

    let out = sandbox.ketch_from(&exe, &["self", "upgrade", "--yes"], &mise_bin);
    let stderr = String::from_utf8_lossy(&out.stderr);

    assert!(!out.status.success(), "the upgrade went ahead:\n{stderr}");
    assert!(stderr.contains("managed by mise"), "{stderr}");
    assert!(stderr.contains("mise upgrade"), "{stderr}");
    assert!(exe.exists(), "the mise-owned binary was touched");
    assert!(!mise_log.exists(), "mise was run for an upgrade");
}

/// The tool name mise knows for the directory `tool`.
fn mise_tool(tool: &str) -> String {
    tool.replacen('-', ":", 1).replacen('-', "/", 1)
}

#[test]
fn self_uninstall_hands_a_mise_install_back_to_mise() {
    for tool in MISE_TOOL_DIRS {
        let sandbox = Sandbox::new();
        let exe = sandbox.install_with_mise_as(tool);
        let (mise_bin, mise_log) = sandbox.fake_mise();

        let out = sandbox.ketch_from(&exe, &["self", "uninstall", "--yes"], &mise_bin);
        let stderr = String::from_utf8_lossy(&out.stderr);

        assert!(out.status.success(), "{tool}: uninstall failed:\n{stderr}");
        // `--yes` in front: the user has already answered, and mise would
        // otherwise ask again for every version it prunes.
        assert_eq!(
            std::fs::read_to_string(&mise_log).expect("mise was never run"),
            format!("--yes unuse -g {}", mise_tool(tool))
        );
        assert!(
            !stderr.contains("mise:"),
            "{tool}: mise could not remove the install:\n{stderr}"
        );
        assert!(
            !sandbox.mise_tool_dir().exists(),
            "{tool}: the mise install survived:\n{stderr}"
        );
        assert!(
            !sandbox.root().exists(),
            "{tool}: the root survived:\n{stderr}"
        );
    }
}

#[test]
fn a_dry_run_names_the_mise_command_and_runs_nothing() {
    for tool in MISE_TOOL_DIRS {
        let sandbox = Sandbox::new();
        let exe = sandbox.install_with_mise_as(tool);
        let (mise_bin, mise_log) = sandbox.fake_mise();

        let out = sandbox.ketch_from(&exe, &["self", "uninstall", "--dry-run"], &mise_bin);
        let stderr = String::from_utf8_lossy(&out.stderr);

        assert!(out.status.success(), "{tool}: {stderr}");
        assert!(
            stderr.contains(&format!("mise unuse -g {}", mise_tool(tool))),
            "{tool}: {stderr}"
        );
        assert!(!mise_log.exists(), "{tool}: mise was run on a dry run");
        assert!(exe.exists());
        assert!(sandbox.root().exists());
    }
}
