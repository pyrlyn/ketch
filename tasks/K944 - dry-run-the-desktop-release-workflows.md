---
id: K944
title: Dry-run the desktop release workflows
status: open
created_at: 1792603910
updated_at: 1792603910
---

`release-apple-desktop.yml` and `release-windows-desktop.yml` have never run; `SUPublicEDKey` is a placeholder and the Windows certificate secrets do not exist. The first real run will meet months of drift.

Done when each workflow has a `dry-run` input that builds and signs with a throwaway certificate and Sparkle key, skips notarisation and publishing, and runs weekly or on changes to its files.
