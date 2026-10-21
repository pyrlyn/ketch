---
id: B59
title: Config table omits keys
status: done
created_at: 1792583218
updated_at: 1792583218
---

The config table omitted `self_repo` / `KETCH_SELF_REPO`, the env-only `KETCH_GITHUB_API` override, and the 16-job cap on `jobs` / `--jobs`. README now lists `self_repo`, notes the API override and cap in prose, and marks `jobs` as capped at 16.
