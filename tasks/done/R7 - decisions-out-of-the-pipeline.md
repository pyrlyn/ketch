---
id: R7
title: Decisions out of the pipeline
status: done
created_at: 1792583218
updated_at: 1792583218
---

`ui::confirm`, `ui::prompt` and `ui::prompt_required` read the terminal from inside the pipeline (`install.rs` calls `confirm`). A GUI has no stdin. Each decision becomes an up-front option (`yes`, the chosen binary, …) or a `Decider` the frontend implements. The CLI keeps its current prompts and non-TTY behaviour.

Done when the core never reads stdin, and a unit test drives each decision through a scripted `Decider`.

Correction after surveying the code: every `confirm`/`prompt` call already sits in `cmd/` (`pkg.rs`, `system.rs`, `lock.rs`, `registry.rs`, `config.rs`). The one decision inside the pipeline is the binary choice: `install.rs` (around line 466) calls `ui::select` when `InstallRequest::interactive` is set. So this task is small, and depends on B64 (which reshapes that choice) being merged.

Plan:
1. Core: `pub trait Decider: Send + Sync { fn choose_binary(&self, package: &str, candidates: &[String]) -> Option<usize>; }` plus a `NoDecider` (always `None`, today's non-interactive path). `InstallRequest::interactive: bool` is replaced by the decider in the context from R6; `--yes` and non-person installs pass `NoDecider`.
2. Binary: a `TerminalDecider` in `ui.rs` wrapping today's `ui::select` (TTY checks and TUI pause unchanged).
3. Confirmations that stay in `cmd/` stay there: they are frontend decisions made before calling the core, which is what a GUI does with its own dialogs. Document that rule in AGENTS.md next to "keep `cmd/` thin".
4. Audit that nothing in the core reads stdin (`grep` for `stdin()`, `read_line`, `IsTerminal` outside `ui.rs`/`tui/`).

Check: unit tests with a scripted decider (picks the second candidate; declines → the existing ambiguity error); the B64 end-to-end tests pass unchanged.

Status: done 2026-10-01, PR https://github.com/pyrlyn/ketch/pull/204. `decide::Decider` (`choose_binary`, `stop_processes`) sits in `Ctx`; `Reporter::choose`/`offer` and `InstallRequest::interactive` are gone. `process::offer_to_stop`, the other mid-run question, went through the same trait. `ui::ctx_asking` opts the CLI in (install, upgrade, rollback, link, self upgrade; off under `--yes`). Nothing in `crates/` reads stdin.
