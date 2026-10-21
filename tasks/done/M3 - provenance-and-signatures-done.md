---
id: M3
title: Provenance and signatures — done
status: done
created_at: 1792583854
updated_at: 1792583854
---

`trust` table on Manifest (verifier sigstore|minisign|gpg, mode require|warn, sidecar templates, issuer + repository/identity, public_key, fingerprint), checked in `Manifest::validate`; docs/MANIFESTS.md. `src/trust.rs`: sigstore offline against an embedded trusted root plus Rekor SET check, minisign-verify with the pinned key, pgp with an inline key pinned by fingerprint (never a keyring); fail closed unless `mode = "warn"`. Results in `info` (text + JSON), the install report and the log. Fixtures in tests/fixtures/trust, unit tests in trust.rs, e2e in tests/trust.rs.
