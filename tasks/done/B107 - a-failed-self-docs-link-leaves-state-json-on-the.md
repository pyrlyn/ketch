---
id: B107
title: A failed self-docs link leaves `state.json` on the old version
status: done
created_at: 1792583854
updated_at: 1792583854
---

Row B107 in the 2026-10-12 findings. `expose_self_docs(...)?` ran after the new version was placed but before `state.save`, so a user-owned completion or man file at the destination left the new binary on disk and `state.json` naming the old one, and every later self upgrade repeated it. Done: self install and self upgrade save the new package before linking man pages and completions, and a failure there is a warning.
