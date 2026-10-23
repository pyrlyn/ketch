---
id: K957
title: Find a lighter Sigstore verifier
status: open
created_at: 1792603927
updated_at: 1792603927
priority: 3
---

The `trust` feature adds 177 crates: sigstore 0.14's `verify` pulls fulcio and rekor clients (reqwest, tokio, hyper, webbrowser, openidconnect, rsa with the ignored B138 advisory) for what is an offline bundle check against an embedded root, and `ketch-ffi` carries it into every app.

Done when a research note with primary sources compares options, and either an upstream change splits offline verification from the network clients or a replacement is chosen, with crate count and binary size measured.
