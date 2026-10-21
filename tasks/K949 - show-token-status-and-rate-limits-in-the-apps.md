---
id: K949
title: Show token status and rate limits in the apps
status: open
created_at: 1792603916
updated_at: 1792603930
blocked_by:
- K912
- K927
---

A GUI launched from the dock has no `GITHUB_TOKEN` and usually no `gh` on PATH, and hourly update checks spend anonymous quota; the rate-limit hint tells it to set an environment variable. `config()` already reports whether a token is set; no app reads it.

Done when each app's Settings shows whether a token is set and where it comes from, and a rate-limit error renders as an actionable banner with the reset time.
