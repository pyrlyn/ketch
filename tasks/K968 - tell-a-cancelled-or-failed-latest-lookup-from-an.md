---
id: K968
title: Tell a cancelled or failed latest lookup from an unreachable one
status: open
created_at: 1792739907
updated_at: 1792739907
priority: 3
---

`listing::latest` (`listing.rs:440`, `:452`) marks every row without an answer `Latest::Unreachable`, whether the lookup failed, was rate-limited or was skipped because `Cancel` fired in `ask_all`; the error itself goes to `debug` only, so `ketch outdated` shows unreachable with no reason.

Done when a skipped lookup reads `NotChecked`, a failed one carries its error (rate limit named as such) in the row, and a test covers cancel mid-batch.
