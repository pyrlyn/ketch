---
id: K951
title: Decide how to stop desktop logic drifting apart
status: open
created_at: 1792603917
updated_at: 1792603930
blocked_by:
- K919
---

Three fakes and three scenario decoders (about 2,000 lines) re-implement one replay and have already drifted from the real core (B111, a declined choice, `stopProcesses` missing on macOS). The progress fold, update notices and busy/retry policy exist per app; the revisit trigger of research decision 5 has fired. Options: a scripted `KetchCore` inside `ketch-ffi` that replays the scenarios (apps test their live adapters on it; plain-.NET tests then need the native library); exporting policy (`ActivityFold`, `UpdateNotices`) from `ketch-ffi`; or a shared store-behaviour spec every app's tests carry.

Done when the creator has picked, and a spike lands the chosen piece in Rust with one app adopting it.
