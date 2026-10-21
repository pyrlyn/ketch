---
id: S8
title: "`doctor --json`, leftover-cask/orphan/stale-lock checks, `outdated -j`"
status: done
created_at: 1792583854
updated_at: 1792583854
---

The JSON flag was already on the CLI and printed text; it now emits a report object. Doctor names a Homebrew cask left after uninstall, store prefixes with no state entry, and a `.lock` a crashed run left behind. `outdated` checks packages concurrently, like install. The `tap` job fetches `ketch-*.tar.gz` from the published release so a cask failure can be re-run without rebuilding.
