// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Ctrl-C while a command is in progress (R8).
//!
//! The first signal cancels the run's token. A download blocked in `read` is
//! woken with EINTR and stops with `cancelled` (exit 130), leaving nothing
//! installed. A plugin that is still running is not at a check, so that same
//! first signal leaves the process up; a second one exits 130 at once.

#![cfg(unix)]

mod support;

use std::io::{Read, Write};
use std::net::TcpListener;
use std::process::{Child, Command, ExitStatus};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use support::{native_name, Sandbox};

struct Running {
    sandbox: Sandbox,
    child: Child,
    stderr: Option<JoinHandle<String>>,
    stdout: Option<JoinHandle<String>>,
    /// Let one body byte through, so a read blocked in the download returns.
    release: Arc<AtomicBool>,
    stop: Arc<AtomicBool>,
}

impl Drop for Running {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        self.release.store(true, Ordering::SeqCst);
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn interrupt(pid: u32) {
    let status = Command::new("kill")
        .args(["-INT", &pid.to_string()])
        .status()
        .expect("run kill");
    assert!(status.success(), "kill -INT {pid}");
}

fn wait_flag(flag: &AtomicBool, limit: Duration) -> bool {
    let start = Instant::now();
    while !flag.load(Ordering::SeqCst) {
        if start.elapsed() > limit {
            return false;
        }
        thread::sleep(Duration::from_millis(20));
    }
    true
}

fn wait_exit(child: &mut Child, limit: Duration) -> Option<ExitStatus> {
    let start = Instant::now();
    loop {
        match child.try_wait() {
            Ok(Some(status)) => return Some(status),
            Ok(None) if start.elapsed() > limit => return None,
            Ok(None) => thread::sleep(Duration::from_millis(30)),
            Err(err) => panic!("wait: {err}"),
        }
    }
}

/// A plugin whose only asset is `url`, fetched by ketch rather than the plugin.
fn write_plugin(sandbox: &Sandbox, url: &str) {
    let name = native_name("slowtool", "1.0.0");
    let caps = r#"{"protocol":1,"scheme":"test","download":false,"search":false}"#;
    let releases = format!(
        r#"[{{"version":"1.0.0","tag":"v1.0.0","prerelease":false,"draft":false,"assets":[{{"name":"{name}","url":"{url}"}}]}}]"#
    );
    let script = format!(
        "#!/bin/sh\n\
         case \"$1\" in\n\
         capabilities) printf '%s' '{caps}' ;;\n\
         describe) printf 'null' ;;\n\
         releases) printf '%s' '{releases}' ;;\n\
         search) printf '[]' ;;\n\
         *) echo \"unsupported subcommand: $1\" >&2; exit 1 ;;\n\
         esac\n",
        caps = caps,
        releases = releases,
    );
    let path = sandbox.root().join("plugins").join("ketch-source-test");
    std::fs::write(&path, script).expect("write plugin");
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).expect("chmod plugin");
}

/// Accept one download, send headers, and hold the body until `release`.
fn serve(
    listener: TcpListener,
    ready: Arc<AtomicBool>,
    release: Arc<AtomicBool>,
    stop: Arc<AtomicBool>,
) {
    let Ok((mut stream, _)) = listener.accept() else {
        return;
    };
    let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
    let mut buf = [0u8; 2048];
    let mut got = Vec::new();
    while !got.windows(4).any(|w| w == b"\r\n\r\n") {
        match stream.read(&mut buf) {
            Ok(0) | Err(_) => break,
            Ok(n) => got.extend_from_slice(&buf[..n]),
        }
    }
    let _ = stream.write_all(
        b"HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n",
    );
    let _ = stream.flush();
    ready.store(true, Ordering::SeqCst);
    while !release.load(Ordering::SeqCst) && !stop.load(Ordering::SeqCst) {
        thread::sleep(Duration::from_millis(20));
    }
    if release.load(Ordering::SeqCst) {
        let _ = stream.write_all(&[0]);
        let _ = stream.flush();
    }
    // Keep the socket until the client goes away, so a missed cancel stays blocked.
    while !stop.load(Ordering::SeqCst) {
        thread::sleep(Duration::from_millis(20));
    }
}

fn start() -> Running {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
    let addr = listener.local_addr().expect("addr");
    let ready = Arc::new(AtomicBool::new(false));
    let release = Arc::new(AtomicBool::new(false));
    let stop = Arc::new(AtomicBool::new(false));
    {
        let ready = Arc::clone(&ready);
        let release = Arc::clone(&release);
        let stop = Arc::clone(&stop);
        thread::spawn(move || serve(listener, ready, release, stop));
    }

    let sandbox = Sandbox::new();
    write_plugin(&sandbox, &format!("http://127.0.0.1:{}/slow", addr.port()));
    let mut child = sandbox.spawn(&["install", "test:slowtool@1.0.0", "--yes"]);
    let stdout = child.stdout.take().expect("stdout");
    let stderr = child.stderr.take().expect("stderr");
    let stdout = thread::spawn(move || {
        let mut buf = String::new();
        let mut stdout = stdout;
        let _ = stdout.read_to_string(&mut buf);
        buf
    });
    let stderr = thread::spawn(move || {
        let mut buf = String::new();
        let mut stderr = stderr;
        let _ = stderr.read_to_string(&mut buf);
        buf
    });

    if !wait_flag(&ready, Duration::from_secs(20)) {
        let _ = child.kill();
        let _ = child.wait();
        let err = stderr.join().unwrap_or_default();
        panic!("download never started\n{err}");
    }

    Running {
        sandbox,
        child,
        stderr: Some(stderr),
        stdout: Some(stdout),
        release,
        stop,
    }
}

fn output(running: &mut Running) -> (Option<ExitStatus>, String) {
    let status = wait_exit(&mut running.child, Duration::from_secs(15));
    running.stop.store(true, Ordering::SeqCst);
    if status.is_none() {
        let _ = running.child.kill();
        let _ = running.child.wait();
    }
    let err = running
        .stderr
        .take()
        .expect("stderr")
        .join()
        .unwrap_or_default();
    let _ = running.stdout.take().expect("stdout").join();
    (status, err)
}

fn assert_nothing_installed(sandbox: &Sandbox) {
    let state = sandbox.root().join("state.json");
    if state.exists() {
        let text = std::fs::read_to_string(&state).expect("read state");
        assert!(!text.contains("slowtool"), "{text}");
    }
    assert!(!sandbox.store().join("slowtool").exists());
    assert!(!sandbox.bin().join("slowtool").exists());
}

#[test]
fn ctrl_c_during_a_download_exits_130_and_installs_nothing() {
    let mut running = start();
    let pid = running.child.id();
    // The read is blocked. Set the flag first, then let the read return, so
    // the next check sees the cancel instead of blocking again.
    interrupt(pid);
    thread::sleep(Duration::from_millis(200));
    running.release.store(true, Ordering::SeqCst);

    let (status, err) = output(&mut running);
    let status = status.unwrap_or_else(|| panic!("ketch kept running after Ctrl-C\n{err}"));
    assert_eq!(status.code(), Some(130), "{err}");
    assert!(err.contains("cancelled"), "{err}");
    assert_nothing_installed(&running.sandbox);
}

/// `releases` touches `ready` and then sleeps. The parent waits out that
/// sleep without checking the token, which is the case a second Ctrl-C is for.
fn start_sleeper() -> Running {
    let sandbox = Sandbox::new();
    let ready = sandbox.fixture("plugin-ready");
    let caps = r#"{"protocol":1,"scheme":"test","download":false,"search":false}"#;
    let script = format!(
        "#!/bin/sh\n\
         case \"$1\" in\n\
         capabilities) printf '%s' '{caps}' ;;\n\
         describe) printf 'null' ;;\n\
         releases)\n\
           : > '{ready}'\n\
           sleep 60\n\
           printf '%s' '[]'\n\
           ;;\n\
         search) printf '[]' ;;\n\
         *) echo \"unsupported subcommand: $1\" >&2; exit 1 ;;\n\
         esac\n",
        caps = caps,
        ready = ready.display(),
    );
    let path = sandbox.root().join("plugins").join("ketch-source-test");
    std::fs::write(&path, script).expect("write plugin");
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).expect("chmod plugin");

    let mut child = sandbox.spawn(&["install", "test:slowtool@1.0.0", "--yes"]);
    let stdout = child.stdout.take().expect("stdout");
    let stderr = child.stderr.take().expect("stderr");
    let stdout = thread::spawn(move || {
        let mut buf = String::new();
        let mut stdout = stdout;
        let _ = stdout.read_to_string(&mut buf);
        buf
    });
    let stderr = thread::spawn(move || {
        let mut buf = String::new();
        let mut stderr = stderr;
        let _ = stderr.read_to_string(&mut buf);
        buf
    });

    if !wait_flag_file(&ready, Duration::from_secs(20)) {
        let _ = child.kill();
        let _ = child.wait();
        let err = stderr.join().unwrap_or_default();
        panic!("plugin never started\n{err}");
    }

    Running {
        sandbox,
        child,
        stderr: Some(stderr),
        stdout: Some(stdout),
        release: Arc::new(AtomicBool::new(false)),
        stop: Arc::new(AtomicBool::new(false)),
    }
}

fn wait_flag_file(path: &std::path::Path, limit: Duration) -> bool {
    let start = Instant::now();
    while !path.exists() {
        if start.elapsed() > limit {
            return false;
        }
        thread::sleep(Duration::from_millis(20));
    }
    true
}

#[test]
fn a_second_ctrl_c_exits_while_a_plugin_is_still_running() {
    let mut running = start_sleeper();
    let pid = running.child.id();
    interrupt(pid);
    thread::sleep(Duration::from_millis(300));
    assert!(
        running.child.try_wait().expect("poll").is_none(),
        "the first Ctrl-C must leave a plugin wait running"
    );
    interrupt(pid);

    let (status, err) = output(&mut running);
    let status = status.unwrap_or_else(|| panic!("the second Ctrl-C did not exit\n{err}"));
    assert_eq!(status.code(), Some(130), "{err}");
    assert_nothing_installed(&running.sandbox);
}
