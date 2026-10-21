---
id: B43
title: "`force` dispatch republishes HEAD, not the repaired tag"
status: done
created_at: 1792583854
updated_at: 1792583854
---

The `version` job derived `TAG`/`VERSION` from `cargo metadata` at the checked-out commit and never looked at the dispatch inputs, while publish did `gh release upload "$TAG" dist/* --clobber`. After main moved, a force re-run could clobber a different release.

`workflow_dispatch` now takes a `tag` input. Force requires it, uses it as `TAG`, and fails if `GITHUB_SHA` is not that tag's commit or if `Cargo.toml` at that commit disagrees. It no longer guesses the tag from HEAD.
