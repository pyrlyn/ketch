---
id: K966
title: Deduplicate crate versions in Cargo.lock
status: open
created_at: 1792739906
updated_at: 1792739906
priority: 3
---

`Cargo.lock` holds two or three versions of 48 crates (`cargo tree -d`): `digest` 0.10/0.11, `hmac`, `pbkdf2`, `cipher`, `base64` 0.21/0.22/0.23, `darling` 0.20/0.21/0.24, `nom` 7/8, `getrandom`, `hashbrown`, `itertools` and more, mostly pulled by sigstore, pgp and octocrab. Each copy adds compile time and binary size. Related to K957 and K958.

Done when the duplicates are listed by the crate that pulls each one, the ones fixable by a bump or a feature are fixed, the rest are recorded as accepted, and a `cargo deny` `multiple-versions` check warns on new ones.
