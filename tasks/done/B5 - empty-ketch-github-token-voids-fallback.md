---
id: B5
title: Empty `KETCH_GITHUB_TOKEN` voids fallback
status: done
created_at: 1792583218
updated_at: 1792583218
---

`KETCH_GITHUB_TOKEN=` returned `Some("")`, which short-circuited the `.or_else` chain to `GITHUB_TOKEN` and `GH_TOKEN` before the trailing filter. Each env var is now filtered with `.filter(|t| !t.trim().is_empty())` before the next fallback, matching `KETCH_APPS_DIR` and `KETCH_REGISTRY`. Unit test `an_empty_ketch_github_token_falls_back_to_the_next_token_variable` verifies empty `KETCH_GITHUB_TOKEN` still picks up `GITHUB_TOKEN`.
