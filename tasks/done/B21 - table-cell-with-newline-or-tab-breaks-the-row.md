---
id: B21
title: Table cell with newline or tab breaks the row
status: done
created_at: 1792583854
updated_at: 1792583854
---

A registry `ketch.toml` with a multi-line `description` prints its second line unindented under `ketch search`'s table, and a literal tab shifts every later column. `table` has to fold its cells onto one line.

`table_lines` now folds each cell onto one line after `printable`: `fold_line` runs `split_whitespace().join(" ")` so newlines and tabs cannot break a row or shift later columns. Regression: `a_table_row_stays_on_one_line_when_a_cell_has_newlines_or_tabs`.
