---
id: K963
title: Run install hooks outside the batch state lock
status: open
created_at: 1792739904
updated_at: 1792739904
---

In `install::batch` (`install.rs:923-933`) each worker calls `commit` while holding the `live` state `Mutex`, and `commit` runs the `before_install` and `after_install` hooks (`install.rs:730`, `:849`) and places the files. One slow hook or a large copy stalls every other worker, so `-j` only helps the download half.

Done when `commit` takes the lock only to re-read and update the state, hooks and placement run outside it, and a test with a sleeping hook shows two packages committing concurrently.
