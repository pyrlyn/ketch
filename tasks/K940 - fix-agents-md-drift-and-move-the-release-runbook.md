---
id: K940
title: Fix AGENTS.md drift and move the release runbook out
status: open
created_at: 1792603907
updated_at: 1792603907
---

AGENTS.md (798 lines) promises an auto-revert path that does not exist, says CI installs Rust with jdx/mise-action (it uses pyrlyn/ci `setup-rust`), says `release.yml` tags (dist never tags), lists "Seven things" and gives six, keeps the obsolete untagged-v0.10.0 paragraph, calls org secrets repository secrets, and still says R9 does not exist. About 250 lines are release runbook. Agents follow this file literally.

Done when each item is fixed (together with the `ideas.md` docs-drift entry) and the Releasing and app-release sections move to a maintainer doc linked from AGENTS.md, exempt from ru/uk like `docs/qa/`.
