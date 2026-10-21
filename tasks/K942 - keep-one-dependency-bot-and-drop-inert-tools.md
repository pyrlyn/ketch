---
id: K942
title: Keep one dependency bot and drop inert tools
status: open
created_at: 1792603908
updated_at: 1792603908
---

Renovate's preset covers cargo, actions, npm and mise, overlapping Dependabot (one onboarding PR against 17). CodeRabbit has auto-review off and its issue workflow has filed nothing. release-plz runs nowhere but keeps a config, a test and 11 AGENTS.md mentions alive.

Done when Renovate is limited to `mise` (or Dependabot dropped), CodeRabbit is either on or removed, and release-plz is retired with its mentions or pinned and used.
