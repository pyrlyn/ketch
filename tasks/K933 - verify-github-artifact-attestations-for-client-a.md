---
id: K933
title: Verify GitHub artifact attestations for client apps
status: open
created_at: 1792603889
updated_at: 1792603929
blocked_by:
- K932
---

Publisher identity is checked only when a manifest declares a `trust` table. GitHub artifact attestations exist for many repositories (`GET /repos/{o}/{r}/attestations/sha256:…`), and sigstore is already a dependency; `ideas.md` covers only ketch's own release.

Done when, for an asset whose digest has an attestation, ketch verifies the bundle against the repository identity and records the result in state, `ketch info` and `ketch audit`; it degrades gracefully offline and fails closed only under `require`.
