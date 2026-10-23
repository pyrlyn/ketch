---
id: K912
title: Pick up the gh token and explain the rate limit
status: open
created_at: 1792603864
updated_at: 1792603864
priority: 1
---

Unauthenticated GitHub allows 60 requests an hour; `ketch outdated` over a dozen packages spends that quickly. `config.rs:231-246` reads only env vars and `config.toml`, and the error asks the user to run `gh auth token` themselves (`error.rs:198`). dra and mise read the GitHub CLI's token.

Done when, with no token configured, ketch asks `gh auth token` (with a timeout, switchable off in config), a 403/429 names the reset time from `x-ratelimit-reset`, `ketch doctor` shows which token source is used (masked), and unit tests cover each source.
