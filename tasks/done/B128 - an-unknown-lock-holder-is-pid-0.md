---
id: B128
title: An unknown lock holder is pid 0
status: done
created_at: 1792583854
updated_at: 1792583854
---

A `Busy` error with no pid became `busy(pid: 0)`, and the banner read "pid 0". The pid is optional. The log and the banner name the holder only when there is one, which is what the Windows app already did.

Test: `aLockWithNoKnownHolderStillOffersRetry`.
