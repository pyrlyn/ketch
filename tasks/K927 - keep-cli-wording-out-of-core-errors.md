---
id: K927
title: Keep CLI wording out of core errors
status: open
created_at: 1792603880
updated_at: 1792603880
---

`error.rs:195-231` hints name CLI flags ("Use --force", "Pass --yes") and `ketch-ffi` appends them to every desktop message. Already installed, update available, pinned, no compatible asset, source conflict and rate-limited all collapse to `Other`, so an app cannot offer "Update" or "Unpin".

Done when hints and exit codes belong to the binary (or `hint()` takes an audience), `KetchError` gains `AlreadyInstalled`, `UpdateAvailable`, `Pinned`, `NoCompatibleAsset`, `SourceConflict` and `RateLimited`, contract scenarios are updated, and a test asserts no FFI message contains `--`. Mapping the new kinds in each app may be a follow-up.
