---
id: K938
title: Publish ketch to a Scoop bucket
status: open
created_at: 1792603906
updated_at: 1792603906
---

ketch ships only through its Homebrew tap and the scripts; Windows developers expect `scoop install`. The `ketch` crate name on crates.io is still free.

Done when the release publish step generates `ketch.json` with its checksum into a `pyrlyn/scoop-bucket`, and the creator's decision on reserving the crates.io name is recorded.
