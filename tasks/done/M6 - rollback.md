---
id: M6
title: Rollback
status: done
created_at: 1792583854
updated_at: 1792583854
---

Upgrade used to delete the previous prefix, so a rollback command would have promised versions that were already gone. State now keeps a retained-version record (version, prefix, asset digest, links, trust) plus a visible `retention.keep` policy (default 1). Old `state.json` files still load: missing fields mean keep-1 and no retained prefixes.

The previous prefix is kept only after the new version is placed; `ketch prune` is the only command that deletes retained prefixes. `ketch rollback <pkg> [--to <version>]` relinks a retained prefix without redownloading, preflights destinations, refuses pinned packages, and leaves the working version in place on failure. `list`/`info` show retained versions and the policy. Uninstall removes current and retained prefixes.

Tests: `a_v1_state_without_retention_fields_still_loads`, `retain_replaced_keeps_an_eligible_prefix_and_skips_the_same_one`, `select_retained_defaults_to_the_previous_and_names_a_miss`; e2e success, missing retained, occupied destination, pinned, uninstall after rollback.
