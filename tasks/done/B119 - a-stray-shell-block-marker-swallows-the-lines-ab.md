---
id: B119
title: A stray shell-block marker swallows the lines above the real block
status: done
created_at: 1792583854
updated_at: 1792583854
---

Row B119 in the 2026-10-12 findings. Done: an END pairs with the nearest BEGIN still before it, so an orphan BEGIN does not take the next block's END. Uninstall leaves the lines between them, and the next install rewrites the closed block instead of appending another.
