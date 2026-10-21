// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Processes holding a file we are about to replace.
//!
//! `ketch upgrade` and `ketch self upgrade` ask before stopping those
//! processes. Listing shells out (`lsof`, `/proc`, PowerShell) the same way
//! the lock file does, so this crate stays free of a libc or Windows-sys
//! dependency. A listing failure is treated as "nobody": replacement then
//! proceeds as it does today.

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::mpsc;
use std::time::Duration;

use crate::report::{Ctx, Report};

/// How long a listing subprocess may run before it is stopped. The listing
/// is best-effort ("could not list" and "nobody" are the same answer), so a
/// stuck WMI service or antivirus filter must not freeze the upgrade.
/// Linux reads `/proc` and does not spawn a listing process.
#[cfg(any(windows, target_os = "macos"))]
const LIST_TIMEOUT: Duration = Duration::from_secs(30);

/// A process whose executable or command line names a file being replaced.
pub struct Occupant {
    /// OS process id.
    pub pid: u32,
    /// The replacement path that matched this process.
    pub path: PathBuf,
}

/// Ask to stop processes using `paths`. `--yes` stops them without asking;
/// a decline leaves them running and the caller continues as before. The
/// question is the [`Decider`](crate::decide::Decider)'s to answer: a front end
/// that cannot ask declines.
pub fn offer_to_stop(paths: &[PathBuf], yes: bool, cx: &Ctx<'_>) {
    let report = cx.report;
    let me = std::process::id();
    let mut seen = BTreeSet::new();
    let occupants: Vec<Occupant> = using(paths, report)
        .into_iter()
        .filter(|o| o.pid != me && seen.insert(o.pid))
        .collect();
    if occupants.is_empty() {
        return;
    }
    for occupant in &occupants {
        report.step(
            "in use",
            &format!("pid {} {}", occupant.pid, occupant.path.display()),
        );
    }
    if !(yes || cx.decider.stop_processes(&occupants)) {
        return;
    }
    for occupant in occupants {
        report.step("stopping", &format!("pid {}", occupant.pid));
        terminate(occupant.pid);
    }
}

/// Processes running one of `paths`: as their executable, or as the script
/// an interpreter's command line names.
/// A listing that had to be stopped is a warning on `report`.
pub fn using(paths: &[PathBuf], report: &Report) -> Vec<Occupant> {
    let keys = unique_keys(paths);
    if keys.is_empty() {
        return Vec::new();
    }
    list(&keys, report)
}

fn unique_keys(paths: &[PathBuf]) -> Vec<(PathBuf, String)> {
    let mut seen = BTreeSet::new();
    let mut out = Vec::new();
    for path in paths {
        let key = path_key(path);
        if key.is_empty() || !seen.insert(key.clone()) {
            continue;
        }
        out.push((path.clone(), key));
    }
    out
}

fn path_key(p: &Path) -> String {
    let p = fs_kit::path::canon(p);
    // `windows_path_key` exists only on Windows (and in tests). A runtime
    // `cfg!` would still name it on Unix.
    #[cfg(windows)]
    let key = crate::shell::windows_path_key(&p);
    #[cfg(not(windows))]
    let key = p.to_string_lossy().into_owned();
    key
}

/// What [`run_bounded`] observed.
#[derive(Debug)]
pub(crate) enum Bounded {
    /// The child exited within the deadline.
    Done(std::process::Output),
    /// The child could not be spawned.
    Failed(std::io::Error),
    /// The child did not finish in time. `pid` is set when the process had
    /// been created and was asked to exit.
    Stopped { program: String, pid: Option<u32> },
}

/// Sentence for a [`Bounded::Stopped`] outcome, naming the program and pid.
pub(crate) fn stopped_detail(program: &str, pid: Option<u32>, timeout: Duration) -> String {
    match pid {
        Some(pid) => format!(
            "{program} (pid {pid}) did not finish within {}s and was stopped",
            timeout.as_secs()
        ),
        None => format!(
            "{program} did not start within {}s; the wait was ended",
            timeout.as_secs()
        ),
    }
}

/// Run `command` to completion, or stop it after `timeout`.
///
/// Spawning sits on another thread because an antivirus filter can hold
/// process creation itself, past any deadline the waiting thread could
/// enforce. When that happens the caller returns [`Bounded::Stopped`] with
/// no pid, and the spawn thread stops the child if creation later succeeds.
/// A child that is created but does not exit is stopped by pid, so a stuck
/// `ketch --version` or process listing cannot keep the upgrade waiting and
/// cannot keep holding the new binary.
pub(crate) fn run_bounded(mut command: Command, timeout: Duration) -> Bounded {
    let program = command.get_program().to_string_lossy().into_owned();
    command
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());
    detach_from_console(&mut command);

    let deadline = std::time::Instant::now() + timeout;
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        let spawned = command.spawn();
        if let Err(mpsc::SendError(Ok(mut child))) = tx.send(spawned) {
            // The caller already gave up. Stop the child it will never see.
            stop_child(&mut child);
        }
    });

    let mut child =
        match rx.recv_timeout(deadline.saturating_duration_since(std::time::Instant::now())) {
            Ok(Ok(child)) => child,
            Ok(Err(err)) => return Bounded::Failed(err),
            Err(_) => return Bounded::Stopped { program, pid: None },
        };
    let pid = child.id();
    let stdout = child.stdout.take();
    let stderr = child.stderr.take();
    let reading_out = std::thread::spawn(move || read_pipe(stdout));
    let reading_err = std::thread::spawn(move || read_pipe(stderr));

    let Some(status) = wait_child(&mut child, deadline) else {
        stop_child(&mut child);
        let _ = join_bounded(reading_out, Duration::from_millis(500));
        let _ = join_bounded(reading_err, Duration::from_millis(500));
        return Bounded::Stopped {
            program,
            pid: Some(pid),
        };
    };

    let stdout = join_bounded(reading_out, Duration::from_secs(2)).unwrap_or_default();
    let stderr = join_bounded(reading_err, Duration::from_secs(2)).unwrap_or_default();
    Bounded::Done(std::process::Output {
        status,
        stdout,
        stderr,
    })
}

/// Run `command` to completion, or give up after `timeout`.
///
/// A listing is best-effort, so a child that does not finish is warned about,
/// stopped, and reported as "no listing". Linux reads `/proc` instead.
#[cfg(any(windows, target_os = "macos"))]
fn output_bounded(
    command: Command,
    timeout: Duration,
    report: &Report,
) -> Option<std::process::Output> {
    match run_bounded(command, timeout) {
        Bounded::Done(out) => Some(out),
        Bounded::Failed(_) => None,
        Bounded::Stopped { program, pid } => {
            report.warn(&stopped_detail(&program, pid, timeout));
            None
        }
    }
}

fn read_pipe<R: std::io::Read>(pipe: Option<R>) -> Vec<u8> {
    let mut buf = Vec::new();
    if let Some(mut pipe) = pipe {
        let _ = std::io::Read::read_to_end(&mut pipe, &mut buf);
    }
    buf
}

fn wait_child(
    child: &mut std::process::Child,
    deadline: std::time::Instant,
) -> Option<std::process::ExitStatus> {
    loop {
        match child.try_wait() {
            Ok(Some(status)) => return Some(status),
            Ok(None) if std::time::Instant::now() >= deadline => return None,
            Ok(None) => std::thread::sleep(Duration::from_millis(20)),
            Err(_) => return None,
        }
    }
}

fn join_bounded<T: Send + 'static>(
    handle: std::thread::JoinHandle<T>,
    timeout: Duration,
) -> Option<T> {
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        let _ = tx.send(handle.join());
    });
    match rx.recv_timeout(timeout) {
        Ok(Ok(value)) => Some(value),
        _ => None,
    }
}

/// Ask `child` and its descendants to exit. A process stuck inside an
/// antivirus filter may outlive the call; the caller has already stopped waiting.
fn stop_child(child: &mut std::process::Child) {
    let pid = child.id();
    kill_tree(pid);
    let _ = child.kill();
    let give_up = std::time::Instant::now() + Duration::from_millis(500);
    while std::time::Instant::now() < give_up {
        match child.try_wait() {
            Ok(Some(_)) | Err(_) => return,
            Ok(None) => std::thread::sleep(Duration::from_millis(20)),
        }
    }
}

#[cfg(unix)]
fn detach_from_console(command: &mut Command) {
    use std::os::unix::process::CommandExt;
    command.process_group(0);
}

#[cfg(windows)]
fn detach_from_console(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    // No console window, and a new group so a Ctrl+C aimed at ketch is not
    // delivered to the child. CREATE_NO_WINDOW keeps the child off the parent
    // console, which otherwise deadlocks under a pseudoconsole.
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
    command.creation_flags(CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP);
}

#[cfg(not(any(unix, windows)))]
fn detach_from_console(_command: &mut Command) {}

#[cfg(unix)]
fn kill_tree(pid: u32) {
    if pid == 0 {
        return;
    }
    let group = format!("-{pid}");
    let pid_s = pid.to_string();
    for target in [group.as_str(), pid_s.as_str()] {
        let _ = Command::new("kill")
            .args(["-s", "KILL", "--", target])
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status();
    }
}

#[cfg(windows)]
fn kill_tree(pid: u32) {
    if pid == 0 {
        return;
    }
    let _ = Command::new("taskkill")
        .args(["/PID", &pid.to_string(), "/T", "/F"])
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status();
}

#[cfg(not(any(unix, windows)))]
fn kill_tree(_pid: u32) {}

#[cfg(any(target_os = "linux", windows))]
fn matches_exe(exe: &Path, candidate: &Path, key: &str) -> bool {
    if path_key(exe) == key {
        return true;
    }
    let is_bundle = candidate.is_dir()
        || candidate
            .extension()
            .is_some_and(|ext| ext.eq_ignore_ascii_case("app"));
    if !is_bundle {
        return false;
    }
    let exe_key = path_key(exe);
    exe_key.starts_with(&format!("{key}/")) || exe_key.starts_with(&format!("{key}\\"))
}

/// Whether `candidate` is a compiled program. The OS then runs it as the
/// process's own executable, so the executable (and on Linux an open file)
/// is the evidence; a command line that merely names it — an editor, a
/// shell — is not. A script is run by an interpreter, and only its command
/// line says which.
#[cfg(any(target_os = "linux", windows, test))]
fn is_native(candidate: &Path) -> bool {
    use std::io::Read;
    let mut head = [0u8; 4];
    let read = std::fs::File::open(candidate).and_then(|mut f| f.read(&mut head));
    let head = &head[..read.unwrap_or(0)];
    head.starts_with(b"MZ")
        || head == b"\x7fELF"
        || matches!(
            head,
            [0xfe, 0xed, 0xfa, 0xce | 0xcf]
                | [0xce | 0xcf, 0xfa, 0xed, 0xfe]
                | [0xca, 0xfe, 0xba, 0xbe]
        )
}

/// Command-line evidence counts only for a script file, and only for an
/// argument that is exactly its path: `…/bin/rga` and `…/bin/rg-helper` are
/// not `…/bin/rg`.
#[cfg(any(target_os = "linux", windows, test))]
fn script_named(candidate: &Path, names: impl FnOnce() -> bool) -> bool {
    candidate.is_file() && !is_native(candidate) && names()
}

/// Windows hands over one command line, quoted however the launcher chose —
/// cmd wraps a script in two pairs of quotes — so instead of splitting it,
/// a match must be bounded by whitespace, a quote or either end.
#[cfg(any(windows, test))]
fn cmd_hits(cmdline: &str, candidate: &Path, key: &str) -> bool {
    script_named(candidate, || {
        // Windows CommandLine casing is arbitrary; keys from `path_key` are folded.
        let cmdline = cmdline.to_ascii_lowercase();
        let raw = candidate
            .to_string_lossy()
            .to_ascii_lowercase()
            .replace('/', "\\");
        whole_arg_in(&cmdline, &raw) || whole_arg_in(&cmdline, key)
    })
}

#[cfg(any(windows, test))]
fn whole_arg_in(cmdline: &str, needle: &str) -> bool {
    let bound = |c: Option<char>| c.is_none_or(|c| c.is_whitespace() || c == '"');
    !needle.is_empty()
        && cmdline.match_indices(needle).any(|(i, m)| {
            bound(cmdline[..i].chars().next_back()) && bound(cmdline[i + m.len()..].chars().next())
        })
}

/// `args` is `/proc/<pid>/cmdline`: the arguments, each ended by a NUL.
#[cfg(any(target_os = "linux", test))]
fn args_hit(args: &str, candidate: &Path, key: &str) -> bool {
    script_named(candidate, || {
        let raw = candidate.to_string_lossy();
        args.split('\0')
            .any(|arg| !arg.is_empty() && (arg == raw || arg == key))
    })
}

#[cfg(target_os = "linux")]
fn list(keys: &[(PathBuf, String)], _report: &Report) -> Vec<Occupant> {
    let mut found = Vec::new();
    let Ok(dir) = std::fs::read_dir("/proc") else {
        return found;
    };
    for entry in dir.flatten() {
        let pid: u32 = match entry.file_name().to_str().and_then(|s| s.parse().ok()) {
            Some(pid) => pid,
            None => continue,
        };
        let base = entry.path();
        let exe = std::fs::read_link(base.join("exe")).ok();
        let cmdline = std::fs::read(base.join("cmdline")).unwrap_or_default();
        let cmdline = String::from_utf8_lossy(&cmdline);
        for (path, key) in keys {
            let exe_hit = exe
                .as_deref()
                .is_some_and(|exe| matches_exe(exe, path, key));
            if exe_hit || linux_fd_hits(&base, path, key) || args_hit(&cmdline, path, key) {
                found.push(Occupant {
                    pid,
                    path: path.clone(),
                });
                break;
            }
        }
    }
    found
}

#[cfg(target_os = "linux")]
fn linux_fd_hits(proc_dir: &Path, candidate: &Path, key: &str) -> bool {
    let Ok(fds) = std::fs::read_dir(proc_dir.join("fd")) else {
        return false;
    };
    fds.flatten().any(|fd| {
        std::fs::read_link(fd.path()).is_ok_and(|target| matches_exe(&target, candidate, key))
    })
}

#[cfg(target_os = "macos")]
fn list(keys: &[(PathBuf, String)], report: &Report) -> Vec<Occupant> {
    let mut found = Vec::new();
    for (path, _) in keys {
        let mut command = Command::new("lsof");
        command
            .args(["-t", "--"])
            .arg(path)
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::null());
        let Some(out) = output_bounded(command, LIST_TIMEOUT, report) else {
            continue;
        };
        for line in String::from_utf8_lossy(&out.stdout).lines() {
            if let Ok(pid) = line.trim().parse::<u32>() {
                found.push(Occupant {
                    pid,
                    path: path.clone(),
                });
            }
        }
    }
    found
}

#[cfg(windows)]
pub(crate) fn powershell_exe() -> PathBuf {
    std::env::var_os("SystemRoot")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(r"C:\Windows"))
        .join(r"System32\WindowsPowerShell\v1.0\powershell.exe")
}

#[cfg(windows)]
fn list(keys: &[(PathBuf, String)], report: &Report) -> Vec<Occupant> {
    let mut command = Command::new(powershell_exe());
    command
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "Get-CimInstance Win32_Process | ForEach-Object { '{0}\t{1}\t{2}' -f $_.ProcessId, $_.ExecutablePath, $_.CommandLine }",
        ])
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null());
    let Some(out) = output_bounded(command, LIST_TIMEOUT, report) else {
        return Vec::new();
    };
    let mut found = Vec::new();
    for line in String::from_utf8_lossy(&out.stdout).lines() {
        let mut cols = line.splitn(3, '\t');
        let Some(pid) = cols.next().and_then(|s| s.trim().parse::<u32>().ok()) else {
            continue;
        };
        let exe = cols.next().unwrap_or("");
        let cmdline = cols.next().unwrap_or("");
        for (path, key) in keys {
            let exe_hit = !exe.is_empty() && matches_exe(Path::new(exe), path, key);
            if exe_hit || cmd_hits(cmdline, path, key) {
                found.push(Occupant {
                    pid,
                    path: path.clone(),
                });
                break;
            }
        }
    }
    found
}

#[cfg(not(any(target_os = "linux", target_os = "macos", windows)))]
fn list(_keys: &[(PathBuf, String)], _report: &Report) -> Vec<Occupant> {
    Vec::new()
}

/// How long a process may take to leave after a polite stop. A fixed 200 ms
/// returns while a program is still flushing, and the replace that follows
/// then finds the file locked. Five seconds is the cap so an upgrade does not
/// wait on a process that is ignoring the request.
const STOP_GRACE: Duration = Duration::from_secs(5);

fn terminate(pid: u32) {
    terminate_within(pid, STOP_GRACE);
}

fn terminate_within(pid: u32, grace: Duration) {
    #[cfg(unix)]
    {
        signal(pid, "-TERM");
        if !wait_until_gone(pid, grace) {
            signal(pid, "-KILL");
            let _ = wait_until_gone(pid, Duration::from_secs(2));
        }
    }
    #[cfg(windows)]
    {
        let _ = Command::new("taskkill")
            .args(["/PID", &pid.to_string()])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status();
        if !wait_until_gone(pid, grace) {
            let _ = Command::new("taskkill")
                .args(["/PID", &pid.to_string(), "/F"])
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::null())
                .status();
            let _ = wait_until_gone(pid, Duration::from_secs(2));
        }
    }
    #[cfg(not(any(unix, windows)))]
    {
        let _ = (pid, grace);
    }
}

#[cfg(unix)]
fn signal(pid: u32, sig: &str) {
    let _ = Command::new("kill")
        .arg(sig)
        .arg(pid.to_string())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status();
}

/// True once `pid` is gone, or false when `bound` elapses first.
fn wait_until_gone(pid: u32, bound: Duration) -> bool {
    let deadline = std::time::Instant::now() + bound;
    loop {
        if !crate::state::process_alive(pid) {
            return true;
        }
        if std::time::Instant::now() >= deadline {
            return false;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::process::Stdio;

    struct ChildGuard(std::process::Child);

    impl Drop for ChildGuard {
        fn drop(&mut self) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }

    /// Set in the environment of a macOS sleeper, which is a copy of this
    /// test binary told to run `sleeps_only_when_spawned_as_the_sleeper`.
    #[cfg(target_os = "macos")]
    const SLEEPER_ENV: &str = "KETCH_TEST_SLEEPER";

    // macOS launch constraints SIGKILL a copied platform binary such as
    // `/bin/sleep` within milliseconds of exec, so a copy of it only looked
    // listed when `lsof` won that race — and under load it never did. This
    // test binary is not a platform binary, and on APFS the copy is a clone.
    #[cfg(target_os = "macos")]
    fn sleeper_src() -> PathBuf {
        std::env::current_exe().expect("current test binary")
    }

    #[cfg(all(unix, not(target_os = "macos")))]
    fn sleeper_src() -> PathBuf {
        PathBuf::from("/bin/sleep")
    }

    #[cfg(windows)]
    fn sleeper_src() -> PathBuf {
        PathBuf::from(r"C:\Windows\System32\PING.EXE")
    }

    fn copy_sleeper(dir: &Path) -> PathBuf {
        let copy = dir.join(if cfg!(windows) {
            "sleeper.exe"
        } else {
            "sleeper"
        });
        std::fs::copy(sleeper_src(), &copy).expect("copy sleeper");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mut perms = std::fs::metadata(&copy).expect("stat copy").permissions();
            perms.set_mode(0o755);
            std::fs::set_permissions(&copy, perms).expect("chmod copy");
        }
        copy
    }

    fn spawn_sleeper(copy: &Path) -> ChildGuard {
        let mut command = Command::new(copy);
        #[cfg(target_os = "macos")]
        command
            .args([
                "--exact",
                "process::tests::sleeps_only_when_spawned_as_the_sleeper",
            ])
            .env(SLEEPER_ENV, "1");
        #[cfg(all(unix, not(target_os = "macos")))]
        command.arg("30");
        #[cfg(windows)]
        command.args(["-n", "30", "127.0.0.1"]);
        let child = command
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .expect("spawn sleeper");
        ChildGuard(child)
    }

    /// The occupant listed for `path`. `spawn` returns only after the child
    /// has exec'd, so the first scan is expected to find it; the retry is a
    /// wall-clock backstop, and a child that already exited fails at once
    /// with its status instead of polling a scan that can never succeed.
    fn wait_for(path: &Path, child: &mut ChildGuard) -> Occupant {
        let paths = [path.to_path_buf()];
        let deadline = std::time::Instant::now() + Duration::from_secs(30);
        loop {
            if let Some(status) = child.0.try_wait().expect("poll sleeper") {
                panic!("sleeper exited before it was listed: {status:?}");
            }
            if let Some(found) = using(&paths, &Report::silent()).into_iter().next() {
                return found;
            }
            if std::time::Instant::now() >= deadline {
                panic!("no occupant for {}", path.display());
            }
            std::thread::sleep(Duration::from_millis(20));
        }
    }

    /// Not a claim about ketch: the body a macOS sleeper runs. Run normally,
    /// without the variable, it returns at once.
    #[cfg(target_os = "macos")]
    #[test]
    fn sleeps_only_when_spawned_as_the_sleeper() {
        if std::env::var_os(SLEEPER_ENV).is_some() {
            std::thread::sleep(Duration::from_secs(30));
        }
    }

    #[test]
    fn lists_a_child_running_from_a_copied_file() {
        let tmp = tempfile::tempdir().unwrap();
        let copy = copy_sleeper(tmp.path());
        let mut child = spawn_sleeper(&copy);
        let found = wait_for(&copy, &mut child);
        assert_eq!(found.pid, child.0.id());
        assert_eq!(
            child.0.try_wait().unwrap(),
            None,
            "sleeper exited while being listed"
        );
    }

    #[test]
    fn yes_stops_the_child_holding_the_file() {
        let tmp = tempfile::tempdir().unwrap();
        let copy = copy_sleeper(tmp.path());
        let mut child = spawn_sleeper(&copy);
        let found = wait_for(&copy, &mut child);
        assert_eq!(found.pid, child.0.id());
        let recorder = std::sync::Arc::new(crate::report::Recorder::default());
        let report = Report::shared(recorder.clone());
        let cfg = crate::config::Config::load(Some(tmp.path().to_path_buf()), &report).unwrap();
        offer_to_stop(&[copy], true, &Ctx::new(&cfg, &report));
        let pid = format!("pid {}", found.pid);
        assert!(
            recorder.events().contains(&crate::report::Event::Status {
                verb: "stopping".into(),
                detail: pid,
            }),
            "`--yes` must say which process it stops"
        );
        // Blocking, not polled: a child `offer_to_stop` missed runs out its
        // 30 s sleep and exits successfully, which the assertion rejects.
        let status = child.0.wait().unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::process::ExitStatusExt;
            assert!(
                status.signal().is_some(),
                "pid {} was not stopped by a signal: {status:?}",
                found.pid
            );
        }
        #[cfg(windows)]
        assert!(
            !status.success(),
            "pid {} ran to completion instead of being stopped: {status:?}",
            found.pid
        );
    }
    #[cfg(windows)]
    #[test]
    fn lists_a_child_running_a_cmd_script() {
        let tmp = tempfile::tempdir().unwrap();
        let script = tmp.path().join("tool.cmd");
        std::fs::write(&script, b"@echo off\r\nping -n 30 127.0.0.1 >nul\r\n").unwrap();
        let child = Command::new(&script)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .expect("spawn cmd script");
        let mut child = ChildGuard(child);
        let found = wait_for(&script, &mut child);
        assert_eq!(found.pid, child.0.id());
    }

    #[cfg(windows)]
    #[test]
    fn cmd_hits_folds_command_line_case() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("tool.cmd");
        std::fs::write(&path, "@echo off\r\n").unwrap();
        let key = path_key(&path);
        let shouted = path.display().to_string().to_ascii_uppercase();
        let cmdline = format!(r#"C:\WINDOWS\system32\cmd.exe /c "{shouted}""#);
        assert!(cmd_hits(&cmdline, &path, &key));
    }

    /// A script, with its path spelled the way Windows command lines are.
    fn script(dir: &Path, name: &str) -> (PathBuf, String) {
        let path = dir.join(name);
        std::fs::write(&path, "#!/bin/sh\n").unwrap();
        let spelled = path.display().to_string().replace('/', "\\");
        (path, spelled)
    }

    #[test]
    fn a_neighbour_whose_name_extends_the_path_is_not_an_occupant() {
        let tmp = tempfile::tempdir().unwrap();
        let (rg, spelled) = script(tmp.path(), "rg");
        let key = path_key(&rg);
        for cmdline in [
            format!(r#""{spelled}a" --files"#),
            format!("{spelled}-helper"),
            format!(r"{spelled}.exe"),
            format!(r"{spelled}\sub"),
        ] {
            assert!(!cmd_hits(&cmdline, &rg, &key), "{cmdline}");
        }
        let raw = rg.display().to_string();
        for args in [format!("{raw}a\0--files\0"), format!("sh\0{raw}-helper\0")] {
            assert!(!args_hit(&args, &rg, &key), "{args:?}");
        }
    }

    #[test]
    fn a_script_named_as_a_whole_argument_is_an_occupant() {
        let tmp = tempfile::tempdir().unwrap();
        let (tool, spelled) = script(tmp.path(), "tool.cmd");
        let key = path_key(&tool);
        // How Rust's `Command` starts a batch file: the path in two pairs of quotes.
        for cmdline in [
            format!(r#"C:\WINDOWS\system32\cmd.exe /e:ON /v:OFF /d /c ""{spelled}" arg""#),
            format!("cmd /c {spelled} arg"),
            format!(r#"cmd /c "{spelled}""#),
        ] {
            assert!(cmd_hits(&cmdline, &tool, &key), "{cmdline}");
        }
        let raw = tool.display().to_string();
        assert!(args_hit(&format!("/bin/sh\0{raw}\0--flag\0"), &tool, &key));
    }

    #[test]
    fn a_program_named_on_another_command_line_is_not_an_occupant() {
        let tmp = tempfile::tempdir().unwrap();
        let rg = tmp.path().join("rg");
        let spelled = rg.display().to_string().replace('/', "\\");
        let key = path_key(&rg);
        for magic in [&b"\x7fELF"[..], b"MZ\x90\0", &[0xcf, 0xfa, 0xed, 0xfe]] {
            std::fs::write(&rg, magic).unwrap();
            // An editor or a shell holding the path, not running it.
            assert!(!cmd_hits(&format!("notepad.exe {spelled}"), &rg, &key));
            let raw = rg.display().to_string();
            assert!(!args_hit(&format!("vim\0{raw}\0"), &rg, &key));
        }
    }

    #[cfg(windows)]
    #[test]
    fn a_listing_that_finishes_is_returned() {
        let mut command = Command::new("cmd.exe");
        command.args(["/c", "echo found"]);
        let out = output_bounded(command, Duration::from_secs(10), &Report::silent())
            .expect("cmd finishes");
        assert!(String::from_utf8_lossy(&out.stdout).contains("found"));
    }

    #[cfg(windows)]
    #[test]
    fn a_command_that_outlives_the_timeout_is_stopped() {
        let mut command = Command::new("cmd.exe");
        command.args(["/c", "ping -n 30 127.0.0.1 >nul"]);
        let start = std::time::Instant::now();
        let pid = match run_bounded(command, Duration::from_millis(300)) {
            Bounded::Stopped { pid: Some(pid), .. } => pid,
            other => panic!("expected the child to be stopped, got {other:?}"),
        };
        assert!(
            start.elapsed() < Duration::from_secs(10),
            "the wait must stay bounded"
        );
        let detail = stopped_detail("cmd.exe", Some(pid), Duration::from_millis(300));
        assert!(detail.contains("was stopped"), "{detail}");
        let deadline = std::time::Instant::now() + Duration::from_secs(2);
        while crate::state::process_alive(pid) && std::time::Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(50));
        }
        assert!(
            !crate::state::process_alive(pid),
            "pid {pid} was not stopped"
        );
    }

    #[cfg(unix)]
    fn detached_pid(script: &str) -> u32 {
        let out = Command::new("sh")
            .args(["-c", script])
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .output()
            .expect("start a detached process");
        String::from_utf8_lossy(&out.stdout)
            .trim()
            .parse()
            .expect("the script prints a pid")
    }

    #[cfg(unix)]
    #[test]
    fn a_process_that_exits_on_term_is_not_held_for_the_whole_grace() {
        let pid = detached_pid("nohup sleep 30 >/dev/null 2>&1 & echo $!");
        assert!(crate::state::process_alive(pid), "pid {pid} never started");
        let start = std::time::Instant::now();
        terminate_within(pid, Duration::from_secs(5));
        assert!(
            start.elapsed() < Duration::from_secs(2),
            "a process that exits was held for {:?}",
            start.elapsed()
        );
        assert!(!crate::state::process_alive(pid));
    }

    #[cfg(unix)]
    #[test]
    fn a_process_that_ignores_term_is_killed_when_the_grace_ends() {
        // TERM is ignored before the child starts, so it inherits the
        // disposition: a `trap` inside the child races the signal sent next.
        let pid = detached_pid(
            "trap '' TERM; nohup sh -c 'while :; do :; done' >/dev/null 2>&1 & echo $!",
        );
        assert!(crate::state::process_alive(pid), "pid {pid} never started");
        let grace = Duration::from_millis(700);
        let start = std::time::Instant::now();
        terminate_within(pid, grace);
        let elapsed = start.elapsed();
        assert!(
            elapsed + Duration::from_millis(50) >= grace,
            "returned in {elapsed:?}, before the grace"
        );
        assert!(
            elapsed < Duration::from_secs(4),
            "the forceful stop was not bounded: {elapsed:?}"
        );
        assert!(!crate::state::process_alive(pid), "pid {pid} still running");
    }
}
