---
id: B17
title: Failed checksum fetch reported as missing
status: done
created_at: 1792583218
updated_at: 1792583218
---

`GitHubSource::checksums` swallows a failed sidecar request with a `--verbose`-only debug line, and the install then says "published no checksum; trusting <hash> on first use" — which is false. Fix: remember that a checksum file existed and could not be read, and say that.

`verify_checksum` no longer swallows `checksums()` fetch errors. When a sidecar exists but cannot be read, the failure is carried through `Prepared`/`Installed` as `checksum_unavailable`, and `report` warns that a checksum file could not be read instead of claiming none was published. When `require_checksum` is set, the fetch error fails the install. Added `install::tests::a_failed_checksum_fetch_is_not_reported_as_missing`; `github::tests::a_checksum_fetch_failure_is_not_reported_as_a_missing_file` already covered the source layer.
