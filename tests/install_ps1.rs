// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `install.ps1`: argument handling, and the paths it installs into.
//!
//! Like `install.sh`, this script runs before any ketch binary exists, so its
//! flags are exercised here instead of through the CLI. Text checks run on every
//! host; `pwsh` cases are skipped when PowerShell is not installed. The full
//! stub-release run is Windows-only.

#[cfg(windows)]
use assert_cmd::Command;
#[cfg(windows)]
use predicates::prelude::*;

fn pwsh_bin() -> Option<std::path::PathBuf> {
    // Resolve before tests clear PATH — otherwise `Command::new("pwsh")` cannot
    // spawn even when PowerShell is installed on the runner.
    std::env::var_os("PATH").and_then(|path| {
        std::env::split_paths(&path).find_map(|dir| {
            let candidate = dir.join(if cfg!(windows) { "pwsh.exe" } else { "pwsh" });
            candidate.is_file().then_some(candidate)
        })
    })
    // A version-manager shim (mise, asdf) can sit on PATH without a PowerShell
    // behind it; treat a pwsh that cannot start as absent.
    .filter(|pwsh| {
        std::process::Command::new(pwsh)
            .args(["-NoProfile", "-NonInteractive", "-Command", "exit 0"])
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .is_ok_and(|status| status.success())
    })
}

#[cfg(windows)]
fn pwsh_available() -> bool {
    pwsh_bin().is_some()
}

#[cfg(windows)]
fn install_ps1() -> Command {
    let pwsh = pwsh_bin().expect("pwsh_available checked");
    let mut cmd = Command::new(pwsh);
    cmd.args([
        "-NoProfile",
        "-File",
        concat!(env!("CARGO_MANIFEST_DIR"), "/install.ps1"),
    ])
    // Absolute pwsh survives this; the script's own PATH lookups stay isolated.
    .env(
        "PATH",
        if cfg!(windows) {
            r"C:\nonexistent-ketch-path"
        } else {
            "/nonexistent"
        },
    )
    .env(
        "USERPROFILE",
        std::env::temp_dir()
            .join("ketch-ps1-test-home")
            .to_string_lossy()
            .as_ref(),
    );
    cmd
}

/// GitHub release tags are v-prefixed; `--version` must accept either form.
/// The status line is printed before the download, so a missing release still
/// shows the tag the URL will use.
#[test]
fn a_bare_version_is_printed_with_the_release_tag_prefix() {
    let Some(pwsh) = pwsh_bin() else {
        return;
    };
    let work = std::env::temp_dir().join(format!("ketch-ps1-ver-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&work);
    std::fs::create_dir_all(&work).unwrap();
    for (given, shown) in [
        ("0.0.0-ketch-flag-probe", "v0.0.0-ketch-flag-probe"),
        ("v0.0.0-ketch-flag-probe", "v0.0.0-ketch-flag-probe"),
    ] {
        let output = std::process::Command::new(&pwsh)
            .args([
                "-NoProfile",
                "-File",
                concat!(env!("CARGO_MANIFEST_DIR"), "/install.ps1"),
                "--no-modify-path",
                "--version",
                given,
                "--root",
            ])
            .arg(work.join("root"))
            .env("USERPROFILE", &work)
            .env("PROCESSOR_ARCHITECTURE", "AMD64")
            .output()
            .expect("pwsh");
        let stdout = String::from_utf8_lossy(&output.stdout);
        assert!(
            stdout.contains(&format!("Installing ketch version {shown}")),
            "given {given}: {stdout}"
        );
        assert!(
            !stdout.contains("Installing ketch version vv"),
            "given {given}: {stdout}"
        );
    }
    let _ = std::fs::remove_dir_all(&work);
}

/// Reaching the release fetch means the flags were accepted.
#[cfg(windows)]
fn fails_after_flag_parse() -> impl predicates::Predicate<str> {
    predicate::str::contains("Fetching latest release")
        .or(predicate::str::contains("Installing ketch"))
        .or(predicate::str::contains("Failed to fetch"))
}

#[test]
fn the_script_names_the_windows_release_tarball() {
    let src = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/install.ps1"));
    assert!(src.contains("pc-windows-msvc"), "{src}");
    assert!(
        src.contains("ketch-$TarballArch-pc-windows-msvc.tar.gz"),
        "{src}"
    );
}

#[test]
fn the_script_defaults_to_userprofile_not_home() {
    let src = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/install.ps1"));
    assert!(src.contains("USERPROFILE"), "{src}");
    assert!(
        !src.contains("$HOME"),
        "Windows dirs::home_dir ignores HOME; the script must not default to it: {src}"
    );
}

#[test]
fn the_script_prefers_system32_tar_over_git_bash_tar() {
    let src = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/install.ps1"));
    assert!(
        src.contains("System32") && src.contains("tar.exe"),
        "install.ps1 must prefer System32 tar.exe so Git Bash GNU tar does not misparse C: paths"
    );
    assert!(
        src.contains("--force-local"),
        "install.ps1 must pass --force-local when falling back to a PATH tar"
    );
}

/// `GetEnvironmentVariable('Path','User')` expands `%VAR%`, and writing that
/// back freezes every such entry; `setx` also truncates. The registry, read
/// unexpanded and written with the value's kind, does neither.
#[test]
fn the_script_edits_path_unexpanded_through_the_registry_not_setx() {
    let src = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/install.ps1"));
    assert!(src.contains("'DoNotExpandEnvironmentNames'"), "{src}");
    assert!(src.contains("$key.SetValue('Path', $next, $kind)"), "{src}");
    assert!(!src.contains("GetEnvironmentVariable('Path'"), "{src}");
    assert!(!src.contains("SetEnvironmentVariable('Path'"), "{src}");
    assert!(!src.contains("setx"), "{src}");
}

/// Under `irm … | iex` an `exit` closes the user's PowerShell window, so the
/// only one left is the one that runs when the script is a file.
#[test]
fn the_script_exits_only_when_run_as_a_file() {
    let src = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/install.ps1"));
    let exits: Vec<(usize, &str)> = src
        .lines()
        .enumerate()
        .map(|(n, line)| (n, line.split('#').next().unwrap_or_default()))
        .filter(|(_, code)| code.split_whitespace().any(|word| word == "exit"))
        .collect();
    assert_eq!(exits.len(), 1, "every `exit` in install.ps1: {exits:?}");
    let (at, _) = exits[0];
    let guard = src.lines().nth(at - 1).unwrap_or_default();
    assert!(
        guard.contains("if ($PSCommandPath)"),
        "the one `exit` must be guarded by $PSCommandPath, not: {guard}"
    );
}

/// Quoted registry PATH entries must match the unquoted bin dir (same rule as
/// `shell::windows_path_key`), and `;` inside quotes must stay one entry.
#[test]
fn the_script_parses_quoted_path_entries_like_ketch_shell() {
    let src = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/install.ps1"));
    assert!(
        src.contains("Get-UserPathEntries"),
        "install.ps1 must split PATH with quote awareness: {src}"
    );
    assert!(
        src.contains("Trim('\"')") || src.contains("Trim(\"\"\")"),
        "Normalize-PathKey must strip surrounding quotes: {src}"
    );
    assert!(
        !src.contains("$PathString -split ';'"),
        "naive -split on ';' would shatter quoted entries with a semicolon: {src}"
    );
}

/// `--pre`'s tag choice, run under pwsh on any host that has it: the first
/// CLI tag in GitHub's newest-first listing, prerelease or not, and never the
/// macOS app's `desktop-v*` tags or its `desktop-appcast` feed.
#[test]
fn pre_picks_the_newest_cli_tag_past_desktop_releases() {
    // A version-manager shim answers to the name without a PowerShell behind
    // it; only a pwsh that actually starts can run the helper.
    let Some(pwsh) = pwsh_bin().filter(|pwsh| {
        std::process::Command::new(pwsh)
            .args(["-NoProfile", "-Command", "exit 0"])
            .output()
            .is_ok_and(|out| out.status.success())
    }) else {
        return;
    };
    let src = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/install.ps1"));
    let start = src
        .find("function Select-NewestCliTag")
        .expect("Select-NewestCliTag");
    let end = src.find("function Install-Ketch").expect("Install-Ketch");
    let helper = &src[start..end];
    let script = format!(
        r#"
{helper}
$releases = '[{{"tag_name":"desktop-appcast","prerelease":true,"draft":false}},{{"tag_name":"desktop-v99.0.0","prerelease":false,"draft":false}},{{"tag_name":"v9.9.9-rc.1","prerelease":true,"draft":false}},{{"tag_name":"v1.0.0","prerelease":false,"draft":false}}]' | ConvertFrom-Json
$got = Select-NewestCliTag @($releases)
if ($got -ne 'v9.9.9-rc.1') {{
    Write-Error "picked '$got'"
    exit 1
}}
exit 0
"#
    );
    let out = std::process::Command::new(pwsh)
        .args(["-NoProfile", "-Command", &script])
        .output()
        .expect("run pwsh");
    assert!(
        out.status.success(),
        "{}{}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr)
    );
}

/// Exercise the PATH helpers from `install.ps1` under pwsh: a quoted bin dir
/// and a folder name containing `;` must both count as already on PATH.
#[test]
#[cfg(windows)]
fn path_helpers_match_quoted_and_semicolon_entries() {
    if !pwsh_available() {
        return;
    }
    let src = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/install.ps1"));
    let start = src
        .find("function Normalize-PathKey")
        .expect("Normalize-PathKey");
    let end = src.find("function Write-Status").expect("Write-Status");
    let helpers = &src[start..end];
    let script = format!(
        r#"
{helpers}
if (-not (Test-UserPathHas 'C:\Windows;"C:\Users\u\.ketch\bin";C:\Other' 'C:\Users\u\.ketch\bin')) {{
    Write-Error 'quoted bin dir should match'
    exit 1
}}
if (-not (Test-UserPathHas 'C:\Windows;"C:\weird;name";C:\Other' 'C:\weird;name')) {{
    Write-Error 'semicolon inside quotes should stay one entry'
    exit 1
}}
if (Test-UserPathHas 'C:\Windows;C:\Other' 'C:\Users\u\.ketch\bin') {{
    Write-Error 'missing bin dir must not match'
    exit 1
}}
exit 0
"#
    );
    let pwsh = pwsh_bin().expect("pwsh_available checked");
    Command::new(pwsh)
        .args(["-NoProfile", "-Command", &script])
        .assert()
        .success();
}

/// `--install-dir` is a bootstrap location, not a second way to name the root:
/// it may sit outside `--root`, need not be called `bin`, and needs no `--root`
/// beside it. Only the retired coupling check refused these before anything ran.
///
/// A probe `--version` forces failure after flag parse so the assertion does not
/// depend on GitHub being unreachable (CI can otherwise install for real).
#[test]
#[cfg(windows)]
fn an_install_dir_need_not_sit_under_the_root() {
    if !pwsh_available() {
        return;
    }
    for args in [
        vec![
            "--root",
            "/tmp/a",
            "--install-dir",
            "/tmp/b/bin",
            "--version",
            "v0.0.0-ketch-flag-probe",
        ],
        vec![
            "--install-dir",
            "/tmp/myapp/tools",
            "--version",
            "v0.0.0-ketch-flag-probe",
        ],
    ] {
        install_ps1()
            .args(&args)
            .assert()
            .failure()
            .stdout(fails_after_flag_parse())
            .stdout(predicate::str::contains("is not").not());
    }
}

#[test]
#[cfg(windows)]
fn help_describes_root_and_install_dir_without_naming_the_root() {
    if !pwsh_available() {
        return;
    }
    let pwsh = pwsh_bin().expect("pwsh_available checked");
    Command::new(pwsh)
        .args([
            "-NoProfile",
            "-File",
            concat!(env!("CARGO_MANIFEST_DIR"), "/install.ps1"),
            "--help",
        ])
        // Linux/mac runners have no USERPROFILE; the script Join-Paths it at load.
        .env("USERPROFILE", "/tmp/ketch-ps1-help-home")
        .assert()
        .success()
        .stdout(predicate::str::contains("--root"))
        .stdout(predicate::str::contains("--install-dir"))
        .stdout(predicate::str::contains("names the root").not())
        .stdout(predicate::str::contains("the root becomes its parent").not());
}

/// `irm … | iex` runs the script in the caller's session: a failure must print
/// its message and hand the session back with its preference, variables and
/// environment as they were, rather than closing the window.
#[test]
#[cfg(windows)]
fn a_failure_under_iex_leaves_the_callers_session_running() {
    if !pwsh_available() {
        return;
    }
    let script = format!(
        r#"
$ErrorActionPreference = 'Continue'
$env:PROCESSOR_ARCHITECTURE = 'ARM64'
Remove-Item Env:PROCESSOR_ARCHITEW6432 -ErrorAction SilentlyContinue
Remove-Item Env:KETCH_ROOT -ErrorAction SilentlyContinue
Get-Content -Raw -LiteralPath '{}' | Invoke-Expression
"survived exit=$LASTEXITCODE preference=$ErrorActionPreference"
if (Get-Command Install-Ketch -ErrorAction SilentlyContinue) {{ 'leaked a function' }}
if (Get-Variable Root -ErrorAction SilentlyContinue) {{ 'leaked a variable' }}
if ($env:KETCH_ROOT) {{ 'leaked KETCH_ROOT' }}
"#,
        concat!(env!("CARGO_MANIFEST_DIR"), "/install.ps1")
    );
    let pwsh = pwsh_bin().expect("pwsh_available checked");
    Command::new(pwsh)
        .args(["-NoProfile", "-Command", &script])
        .env(
            "USERPROFILE",
            std::env::temp_dir().join("ketch-ps1-iex-home"),
        )
        .assert()
        .stdout(predicate::str::contains("Unsupported architecture: ARM64"))
        .stdout(predicate::str::contains(
            "survived exit=1 preference=Continue",
        ))
        .stdout(predicate::str::contains("leaked").not());
}

/// The whole script, with the network replaced by a local release tree and the
/// archive by a stub `ketch.exe`.
#[cfg(windows)]
mod against_a_stub_release {
    use assert_cmd::Command;
    use assert_fs::prelude::*;
    use assert_fs::TempDir;
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use predicates::prelude::*;
    use sha2::{Digest, Sha256};
    use std::path::{Path, PathBuf};

    const STUB_RUST: &str = r#"
use std::path::PathBuf;
fn main() {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("self") => {
            let root = std::env::var("KETCH_ROOT").expect("KETCH_ROOT");
            let dest = PathBuf::from(&root).join("bin").join("ketch.exe");
            std::fs::create_dir_all(dest.parent().expect("bin parent")).expect("mkdir");
            std::fs::copy(std::env::current_exe().expect("exe"), &dest).expect("copy");
            let mut link_dir = None;
            let mut i = 2;
            while i < args.len() {
                if args[i] == "--link-dir" {
                    i += 1;
                    if i < args.len() {
                        link_dir = Some(args[i].clone());
                    }
                }
                i += 1;
            }
            if let Some(dir) = link_dir {
                std::fs::create_dir_all(&dir).expect("bootstrap dir");
                let dest_key = std::fs::canonicalize(dest.parent().unwrap())
                    .unwrap()
                    .to_string_lossy()
                    .to_ascii_lowercase();
                let dir_key = std::fs::canonicalize(&dir)
                    .unwrap()
                    .to_string_lossy()
                    .to_ascii_lowercase();
                if dest_key != dir_key {
                    std::fs::copy(&dest, PathBuf::from(dir).join("ketch.exe")).expect("bootstrap");
                }
            }
        }
        Some("--help") | Some("-h") => println!("ketch (stub)"),
        _ => println!("ketch (stub)"),
    }
}
"#;

    struct StubRelease {
        dir: TempDir,
        // Kept so the compiled stub path stays live for the payload write;
        // readers only need `dir`.
        _stub_exe: PathBuf,
    }

    impl StubRelease {
        fn new() -> Self {
            let dir = TempDir::new().unwrap();
            let stub_dir = dir.child("stub-src");
            stub_dir.create_dir_all().unwrap();
            let src = stub_dir.child("main.rs");
            src.write_str(STUB_RUST).unwrap();
            let stub_exe = dir.child("ketch.exe");
            let status = std::process::Command::new("rustc")
                .arg(src.path())
                .arg("-o")
                .arg(stub_exe.path())
                .status()
                .expect("compile stub ketch");
            assert!(status.success(), "rustc could not build the stub ketch.exe");

            let payload = dir.child("payload.tar.gz");
            write_payload(payload.path(), stub_exe.path());
            let digest = hex::encode(Sha256::digest(std::fs::read(payload.path()).unwrap()));
            dir.child("SHA256SUMS")
                .write_str(&format!("{digest}  ketch-x86_64-pc-windows-msvc.tar.gz\n"))
                .unwrap();

            StubRelease {
                dir,
                _stub_exe: stub_exe.path().to_path_buf(),
            }
        }
    }

    fn write_payload(path: &Path, stub_exe: &Path) {
        let gz = GzEncoder::new(std::fs::File::create(path).unwrap(), Compression::default());
        let mut tar = tar::Builder::new(gz);
        let bytes = std::fs::read(stub_exe).unwrap();
        let mut header = tar::Header::new_gnu();
        header.set_size(bytes.len() as u64);
        header.set_mode(0o755);
        header.set_cksum();
        tar.append_data(&mut header, "ketch.exe", bytes.as_slice())
            .unwrap();
        tar.into_inner().unwrap().finish().unwrap();
    }

    fn run(release: &StubRelease, work: &TempDir, args: &[&Path]) -> assert_cmd::assert::Assert {
        let mut cmd = Command::new("pwsh");
        cmd.args([
            "-NoProfile",
            "-File",
            concat!(env!("CARGO_MANIFEST_DIR"), "/install.ps1"),
            "--no-modify-path",
        ])
        .current_dir(work.path())
        .env("KETCH_INSTALL_RELEASE_DIR", release.dir.path())
        .env("USERPROFILE", work.path());
        for arg in args {
            cmd.arg(arg);
        }
        cmd.assert()
    }

    #[test]
    fn an_explicit_install_dir_gets_a_copy_of_the_installed_binary() {
        let release = StubRelease::new();
        let work = TempDir::new().unwrap();
        let root = work.child("root");
        let bootstrap = work.child("bootstrap");

        run(
            &release,
            &work,
            &[
                Path::new("--root"),
                root.path(),
                Path::new("--install-dir"),
                bootstrap.path(),
            ],
        )
        .success();

        let installed = root.child("bin/ketch.exe");
        installed.assert(predicate::path::is_file());
        let bootstrap_bin = bootstrap.child("ketch.exe");
        bootstrap_bin.assert(predicate::path::is_file());
        assert_ne!(
            std::fs::read(installed.path()).unwrap(),
            b"",
            "the store binary must be installed"
        );
        assert_eq!(
            std::fs::read(bootstrap_bin.path()).unwrap(),
            std::fs::read(installed.path()).unwrap(),
            "the bootstrap path must follow the store binary"
        );
    }

    #[test]
    fn a_relative_root_lands_beside_the_caller_not_in_the_temp_dir() {
        let release = StubRelease::new();
        let work = TempDir::new().unwrap();

        run(
            &release,
            &work,
            &[Path::new("--root"), Path::new("relative-root")],
        )
        .success();

        work.child("relative-root/bin/ketch.exe")
            .assert(predicate::path::is_file());
    }

    #[test]
    fn an_install_dir_that_only_respells_the_root_bin_dir_is_left_alone() {
        let release = StubRelease::new();
        let work = TempDir::new().unwrap();
        let root = work.child("root");

        run(
            &release,
            &work,
            &[
                Path::new("--root"),
                root.path(),
                Path::new("--install-dir"),
                root.child("bin").path(),
            ],
        )
        .success();

        let installed = root.child("bin/ketch.exe");
        installed.assert(predicate::path::is_file());
        assert!(
            !bootstrap_exists_beside_store(&root),
            "the installed binary must still be the binary, not a bootstrap copy beside it"
        );
    }

    fn bootstrap_exists_beside_store(root: &assert_fs::fixture::ChildPath) -> bool {
        let bin = root.child("bin");
        let entries = std::fs::read_dir(bin.path()).unwrap();
        entries.count() > 1
    }
}
