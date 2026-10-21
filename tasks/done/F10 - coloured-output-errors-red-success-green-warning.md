---
id: F10
title: "Coloured output: errors red, success green, warnings yellow"
status: done
created_at: 1792583854
updated_at: 1792583854
---

Most of this exists. `src/ui.rs` paints the `error` label red, `warning` yellow, success verbs green, steps blue and notes dim. It honours `--no-color`, `NO_COLOR`, `CLICOLOR_FORCE` and non-TTY output, and there is no `println!` outside `ui`. F10 is an audit plus the gaps:

1. Only the label is coloured today. Decide with Ivan whether the error headline and warning text are coloured as well.
2. Route every remaining status string through the helpers: prompts (`confirm`, `select`), `completed`, table markers (M9's yellow `update available`).
3. Windows: make sure ANSI works on legacy conhost (enable VT processing, or fall back to plain text).
4. Optional: a `color = "auto" | "always" | "never"` config key and `KETCH_COLOR`, next to `--no-color`.

Check: insta snapshots of each line kind with `CLICOLOR_FORCE=1`; no escape bytes with `NO_COLOR=1`, `--no-color`, or when piped; `just check`.

Decisions (Ivan): the whole error headline is red and the whole warning text yellow, not only the label; success lines green. Step 4 is skipped.

Execution plan (done):
1. `src/ui.rs`: paint the full error headline red and the full warning text yellow; success lines green.
2. `src/ui.rs` and callers: route `confirm`/`select` prompts, `completed` and table markers through the style helpers.
3. Windows: enable VT processing on legacy conhost through a maintained crate without `unsafe`, or fall back to plain text when it cannot be enabled.
4. Tests beside `ui.rs`: insta snapshots of each line kind with colour forced; no escape bytes with `NO_COLOR`, `--no-color` or a pipe.
5. `cargo fmt`, `cargo clippy --all-targets -- -D warnings`, `cargo nextest run`.

Result: `ui::Tone` names the meaning of status text (step, success, warning, error, note, hint) and is the one place a meaning picks its colour. Success lines are green end to end, warnings yellow, error headlines red; details stay dim and the hint cyan. Prompts (`confirm`, `answer`, `choose`, `cancelled`), the `fetched` line, the counter label, `list`'s update marker, `doctor`'s ok/warn/fail and `lock`'s +/~/- markers go through the same helpers; the log-open warning no longer bypasses `ui`. On Windows `console` switches on virtual terminal processing, and colour falls back to plain text when it cannot. `completed` prints nothing outside the TUI, whose status colours already honour `--no-color`, so it was left as is.
