---
id: B14
title: "`--tui` confirmation hangs the terminal"
status: done
created_at: 1792583854
updated_at: 1792583854
---

The session entered raw mode before the command was dispatched, and in raw mode Enter is a carriage return while `ui::ask` reads a line — so `ketch upgrade --tui` hung at its confirmation with no way out. Reachable only from a source build with `--features tui`.

`confirm`/`prompt`/`prompt_required` now leave the alternate screen and `disable_raw_mode()` around the line read (via `with_tui_input_paused` → `Controller::pause_for_input`), then re-enter. Event polling is skipped while paused so a TUI `send` cannot steal Enter. Tests: `confirm_pauses_an_active_tui_session_before_reading`, `send_does_not_poll_input_while_paused_for_a_prompt`.
