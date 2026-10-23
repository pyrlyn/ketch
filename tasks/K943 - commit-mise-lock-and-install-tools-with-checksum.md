---
id: K943
title: Commit mise.lock and install tools with checksums
status: open
created_at: 1792603909
updated_at: 1792603909
---

There is no `mise.lock`; about 20 tools, some git-sourced, install unverified, and the jactionlint baseline accepts unverified `sh` runs of the cargo-dist installer and rustup. The release build runs on these tools.

Done when `mise.lock` is committed, CI installs in locked mode, and the two baseline entries are gone.
