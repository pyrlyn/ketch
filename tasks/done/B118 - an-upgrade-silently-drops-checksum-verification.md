---
id: B118
title: An upgrade silently drops checksum verification
status: done
created_at: 1792583854
updated_at: 1792583854
---

A package installed against a published checksum used to accept a later release that published none, with only the first-use warning. `refuse_checksum_downgrade` now refuses that upgrade. `--allow-unverified` or `--yes` consents, and a lockfile hash stands in for consent because it is a stronger pin than either.

Tests: `an_upgrade_that_drops_the_checksum_is_refused_without_consent`, `yes_consents_to_an_upgrade_that_drops_the_checksum`.
`gzip`, `xz`, `bzip2` and `zip` are cargo features of `ketch-core`. Plain tar stays on. `portable_extractors()` is the one list macOS, Linux and Windows extend, so a platform copy of the codec list is gone. A build that leaves a codec out reports `UnsupportedArchive` instead of treating the bytes as a program. `an_archive_whose_codec_is_left_out_is_unsupported` covers that, and `scripts/core-features.sh` builds each feature alone. The stacked pull request #322 was closed; the change came in with #354.
