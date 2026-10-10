// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! One local HTTP server that plays every remote a test needs: a stand-in
//! GitHub API under `/gh` (point `KETCH_GITHUB_API` at [`Remote::github_api`]),
//! package catalogues and asset downloads, each answered from a table the
//! test can change between runs.
//!
//! It also keeps every request it was sent, so a test can prove where a
//! header — a token above all — did and did not go.

use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

type Routes = Arc<Mutex<HashMap<String, Vec<u8>>>>;

/// One request as the server saw it.
#[derive(Debug, Clone)]
pub struct Seen {
    /// Path and query, as sent.
    pub target: String,
    pub authorization: Option<String>,
    pub accept: Option<String>,
}

/// Answers each request target (path and query) from its table; anything
/// else is a 404, which every client reads as "no such thing".
pub struct Remote {
    pub base: String,
    addr: SocketAddr,
    routes: Routes,
    seen: Arc<Mutex<Vec<Seen>>>,
    stop: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl Remote {
    pub fn start() -> Remote {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind mock remote");
        let addr = listener.local_addr().expect("addr");
        let routes: Routes = Arc::default();
        let seen: Arc<Mutex<Vec<Seen>>> = Arc::default();
        let stop = Arc::new(AtomicBool::new(false));
        let (table, log, flag) = (Arc::clone(&routes), Arc::clone(&seen), Arc::clone(&stop));
        let handle = thread::spawn(move || {
            for stream in listener.incoming() {
                if flag.load(Ordering::Relaxed) {
                    break;
                }
                if let Ok(stream) = stream {
                    answer(stream, &table, &log);
                }
            }
        });
        Remote {
            base: format!("http://{addr}"),
            addr,
            routes,
            seen,
            stop,
            handle: Some(handle),
        }
    }

    /// The API base for `KETCH_GITHUB_API`.
    pub fn github_api(&self) -> String {
        format!("{}/gh", self.base)
    }

    pub fn serve(&self, target: &str, body: impl Into<Vec<u8>>) {
        self.routes
            .lock()
            .expect("routes")
            .insert(target.to_string(), body.into());
    }

    /// Stop answering `target`: it is a 404 from now on.
    pub fn forget(&self, target: &str) {
        self.routes.lock().expect("routes").remove(target);
    }

    /// Every target served now, with its body.
    pub fn routes(&self) -> Vec<(String, Vec<u8>)> {
        let routes = self.routes.lock().expect("routes");
        routes.iter().map(|(k, v)| (k.clone(), v.clone())).collect()
    }

    pub fn clear(&self) {
        self.routes.lock().expect("routes").clear();
    }

    /// Every request so far, oldest first.
    pub fn seen(&self) -> Vec<Seen> {
        self.seen.lock().expect("seen").clone()
    }
}

impl Drop for Remote {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        // Unblock the accept so the join cannot hang.
        let _ = TcpStream::connect_timeout(&self.addr, std::time::Duration::from_millis(200));
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

fn answer(mut stream: TcpStream, routes: &Routes, seen: &Mutex<Vec<Seen>>) {
    let mut head = Vec::new();
    let mut byte = [0u8; 1];
    while !head.ends_with(b"\r\n\r\n") {
        match stream.read(&mut byte) {
            Ok(1) => head.push(byte[0]),
            _ => return,
        }
    }
    let head = String::from_utf8_lossy(&head);
    let target = head.split_whitespace().nth(1).unwrap_or("/").to_string();
    let header = |name: &str| {
        head.lines().skip(1).find_map(|line| {
            let (key, value) = line.split_once(':')?;
            key.trim()
                .eq_ignore_ascii_case(name)
                .then(|| value.trim().to_string())
        })
    };
    seen.lock().expect("seen").push(Seen {
        target: target.clone(),
        authorization: header("authorization"),
        accept: header("accept"),
    });
    let body = routes.lock().expect("routes").get(&target).cloned();
    let (status, body) = match body {
        Some(body) => ("200 OK", body),
        None => ("404 Not Found", b"{\"message\":\"Not Found\"}".to_vec()),
    };
    let _ = write!(
        stream,
        "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    );
    let _ = stream.write_all(&body);
}
