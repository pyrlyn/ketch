---
id: B41
title: Terminal filter and nested tables have no failure-path test
status: done
created_at: 1792583218
updated_at: 1792583218
---

`ui.rs` covered `table_lines` only: `step`, `success`, `warn`, `error`, `note`, and `debug` all filter client text through `ui::printable`, but nothing failed if one of them stopped calling it. `Registry`'s `deny_unknown_fields` had no test showing that a `[[package]]` file with a stray top-level key is refused.

Line builders (`step_line`, `success_line`, `warn_line`, `note_line`, `debug_line`, `error_lines`) mirror what the status helpers emit so tests can read the filtered text without touching stderr. Regression tests: `step_detail_cannot_redraw_the_terminal`, `success_detail_cannot_redraw_the_terminal`, `a_warning_cannot_redraw_the_terminal`, `a_note_cannot_redraw_the_terminal`, `debug_output_cannot_redraw_the_terminal`, `an_error_headline_cannot_redraw_the_terminal`, `error_details_cannot_redraw_the_terminal`, `an_error_hint_cannot_redraw_the_terminal`. `a_registry_file_with_a_stray_top_level_key_is_refused` checks `parse_registry` rejects an unknown top-level key beside `[[package]]`.
