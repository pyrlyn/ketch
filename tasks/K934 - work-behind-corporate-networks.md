---
id: K934
title: Work behind corporate networks
status: open
created_at: 1792603891
updated_at: 1792603891
---

ureq is built on `webpki-roots` only, so a TLS-intercepting corporate CA fails every request; proxy support (`proxy-from-env`) and `KETCH_GITHUB_API` for GitHub Enterprise exist but are undocumented, and private-repo token scopes are mentioned only in a source comment.

Done when the OS trust store is used (platform verifier), CONFIGURATION.md has a "Corporate networks" section (proxy, GHE, private repos) in en/ru/uk, and a test covers a custom root.
