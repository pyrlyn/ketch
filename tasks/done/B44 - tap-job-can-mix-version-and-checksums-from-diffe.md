---
id: B44
title: "`tap` job can mix version and checksums from different releases"
status: done
created_at: 1792583218
updated_at: 1792583218
---

`VERSION` came from `needs.version` (HEAD) while the tarballs were fetched by `TAG` from the published release, so a cask could advertise one version with checksums from another.

Added `scripts/tap-release-version.sh` to strip the `v` prefix from the published release tag and fail when it disagrees with the workflow version. The `tap` job now resolves the version right after downloading assets, passes it through `steps.release.outputs.version` to cask generation and the tap push, and CI exercises the guard.
