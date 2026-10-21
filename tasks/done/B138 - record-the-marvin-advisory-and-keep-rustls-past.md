---
id: B138
title: Record the Marvin advisory and keep rustls past its fix
status: done
created_at: 1792583854
updated_at: 1792583854
---

Row B138 in the 2026-10-12 findings. rustls 0.23.43 (RUSTSEC-2026-0285) is already 0.23.46 in `Cargo.lock`, so it was not bumped again. rsa 0.9.10 (RUSTSEC-2023-0071, Marvin) has no upstream fix and is only reached to verify signatures.

Done: `deny.toml` ignores that advisory and says why. `cargo deny` is not in CI.
Row B123 in the 2026-10-12 findings. Done: the store is restored only when this call made the swap. `move_into_store` reports whether it retired a tree, and `restore_retired_store` runs only then. A stale `.old` left by an earlier interrupted swap stays aside when relink fails: `a_failed_relink_keeps_the_live_prefix_over_a_stale_old_copy` on macOS and on Linux.
