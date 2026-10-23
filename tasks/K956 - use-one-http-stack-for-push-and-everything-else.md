---
id: K956
title: Use one HTTP stack for push and everything else
status: open
created_at: 1792603926
updated_at: 1792603926
priority: 3
---

`ketch registry push` runs octocrab on its own tokio runtime, without `http.rs`'s timeouts, body cap or `--verbose` log, and its errors lose exit code 7. `Error::Network` carries `Box<ureq::Error>`, tying the public error to ureq 2. 16 call sites build their own agent although `http.rs` says one per process. `push` already sits behind an `Api` trait.

Done when the GitHub `Api` implementation runs on `Http`, octocrab and tokio leave the `push` feature, `Error::Network` no longer names ureq, and agents are shared per operation. Bumping ureq stays a separate decision.
