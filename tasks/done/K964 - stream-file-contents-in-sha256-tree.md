---
id: K964
title: Stream file contents in sha256_tree
status: done
created_at: 1792739904
updated_at: 1792970046
priority: 3
assignee: d2a34757-1247-4b58-beb9-8a90d064e359
---

`source::local::sha256_tree` (`source/local.rs:434`) reads every file with `fs::read` before hashing, so locking or verifying a local bundle holds its largest file in memory at once; `http::sha256_file` already streams through a 128 KiB buffer.

Done when the length comes from the metadata, the payload is fed to the hasher through a reused buffer, and the existing `sha256_tree` hashes are unchanged.
