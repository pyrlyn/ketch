---
id: B118
title: An upgrade silently drops checksum verification
status: done
created_at: 1792583218
updated_at: 1792583218
priority: 3
---

Row B118 in the 2026-10-12 findings. Done: a package installed checksum-verified refuses an unverified upgrade unless `--yes` or an explicit flag.

Plan: consult `checksum_verified` in `install.rs` `verify_checksum`; the flag in `cli.rs`. Check: an end-to-end test of both paths.

Status: done. `refuse_checksum_downgrade` refuses the upgrade unless `--allow-unverified`, `--yes`, or a lockfile hash is set. `an_upgrade_that_drops_the_checksum_is_refused_without_consent` and `yes_consents_to_an_upgrade_that_drops_the_checksum` cover both paths. See `done.md`.
