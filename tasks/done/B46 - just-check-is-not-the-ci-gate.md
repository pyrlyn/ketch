---
id: B46
title: "`just check` is not the CI gate"
status: done
created_at: 1792583854
updated_at: 1792583854
---

`just check` ran `cargo test --locked` where CI runs `--all-targets`, and it skipped `scripts/package.sh` and the cask `brew style` gate while `AGENTS.md` called it "the whole CI gate".

`just test` and `just check` now use `cargo test --all-targets --locked`. Added `just lint-shell`, `just package`, and `just lint-cask` (macOS only) to `just check`, matching CI's `package` job on this host. `AGENTS.md` describes the local gate accurately.
