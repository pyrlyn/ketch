---
id: K967
title: Keep a binary at the ketch path during self-replace
status: done
created_at: 1792739906
updated_at: 1792970037
assignee: d2a34757-1247-4b58-beb9-8a90d064e359
---

`replace_binary` (`self_update.rs:613-630`) renames the running `exe` aside, then copies the new one in. Between the rename and the end of the copy there is no file at `exe`, so a shell, completion or second `ketch` started then fails with not-found, and a crash there leaves only `ketch.old`.

Done when, on Unix, the new binary is copied to a sibling temp file, made executable and renamed over `exe` (atomic), the aside stays for rollback, and Windows keeps its rename-aside path with a test for each.
