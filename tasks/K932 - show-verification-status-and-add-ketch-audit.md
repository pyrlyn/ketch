---
id: K932
title: Show verification status and add `ketch audit`
status: open
created_at: 1792603888
updated_at: 1792603888
---

Verification is the selling point but invisible: `ketch list` never shows whether a checksum or signature was verified, and `require_checksums` defaults to off. Teams need to find unverified installs.

Done when `ketch list` shows verified, signed or unverified per package, and `ketch audit [--json]` exits non-zero when any package is unverified.
